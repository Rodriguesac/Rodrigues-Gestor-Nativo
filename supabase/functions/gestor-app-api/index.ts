import {appActions} from "./actions.ts";
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.56.1";

const SITE_ORIGIN = "https://gestor20rac.netlify.app";
const BRANCH_ORIGIN = /^https:\/\/[a-z0-9-]+--gestor20rac\.netlify\.app$/i;
const LOCAL_ORIGIN = /^http:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?$/i;
const SESSION_HOURS = 12;
const MAX_LOGIN_ATTEMPTS = 5;
const LOGIN_WINDOW_MS = 10 * 60 * 1000;
const LOCK_MS = 15 * 60 * 1000;
const CUSTOMER_PUSH_URL = "https://rodriguesacaiecia.netlify.app/api/push-send";
const CUSTOMER_VAPID_PUBLIC_KEY = "BDYgQcOCn0z6bYHSvyuHa-0E5QbcXqRaapOQ-0Ujh4YYXZ6yZCKZu4u7xhlI90NNB_O6sYfbg7EUmdybmTLILhc";

const text = (v: unknown) => String(v ?? "").trim();
const allowedOrigin = (origin: string) =>
  origin === "https://appassets.androidplatform.net" || origin === SITE_ORIGIN || BRANCH_ORIGIN.test(origin) || LOCAL_ORIGIN.test(origin);

const responseHeaders = (req: Request) => {
  const origin = text(req.headers.get("origin"));
  return {
    "Access-Control-Allow-Origin": allowedOrigin(origin) ? origin : SITE_ORIGIN,
    "Access-Control-Allow-Headers": "content-type, apikey, authorization",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "Vary": "Origin",
  };
};

const json = (req: Request, body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: responseHeaders(req) });

async function sha256(value: string) {
  const encoded = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", encoded);
  return Array.from(new Uint8Array(hash))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function clientIp(req: Request) {
  return text(
    req.headers.get("cf-connecting-ip") ||
    req.headers.get("x-nf-client-connection-ip") ||
    req.headers.get("x-forwarded-for")?.split(",")[0]
  ) || "unknown";
}

async function readDoc(admin: any, collection: string, id: string) {
  const result = await admin
    .from("app_documents")
    .select("data")
    .eq("collection_name", collection)
    .eq("document_id", id)
    .maybeSingle();
  if (result.error) throw result.error;
  return result.data?.data || null;
}

async function writeDoc(admin: any, collection: string, id: string, data: any) {
  const result = await admin
    .from("app_documents")
    .upsert(
      { collection_name: collection, document_id: id, data, updated_at: new Date().toISOString() },
      { onConflict: "collection_name,document_id" }
    );
  if (result.error) throw result.error;
}

async function deleteDoc(admin: any, collection: string, id: string) {
  await admin
    .from("app_documents")
    .delete()
    .eq("collection_name", collection)
    .eq("document_id", id);
}

async function verifyPin(admin: any, req: Request, pin: string) {
  const now = Date.now();
  const ipHash = await sha256(clientIp(req));
  const attemptId = ipHash.slice(0, 40);
  const attempts = (await readDoc(admin, "security_gestor_attempts", attemptId)) || {};
  const lockedUntil = Number(attempts.lockedUntil || 0);
  if (lockedUntil > now) {
    return { ok: false, status: 429, message: "Muitas tentativas. Tente novamente mais tarde." };
  }

  const config = await readDoc(admin, "security_gestor", "gestor20rac_pin");
  if (!config?.active || !config?.sha256) {
    return { ok: false, status: 503, message: "Acesso do Gestor não configurado." };
  }

  const valid = (await sha256(pin)) === String(config.sha256);
  if (valid) {
    await deleteDoc(admin, "security_gestor_attempts", attemptId);
    return { ok: true, status: 200, message: "" };
  }

  const windowStart = Number(attempts.windowStart || 0);
  const inWindow = now - windowStart <= LOGIN_WINDOW_MS;
  const count = inWindow ? Number(attempts.count || 0) + 1 : 1;
  const next = {
    count,
    windowStart: inWindow ? windowStart : now,
    lockedUntil: count >= MAX_LOGIN_ATTEMPTS ? now + LOCK_MS : 0,
  };
  await writeDoc(admin, "security_gestor_attempts", attemptId, next);
  return {
    ok: false,
    status: count >= MAX_LOGIN_ATTEMPTS ? 429 : 401,
    message: count >= MAX_LOGIN_ATTEMPTS
      ? "Muitas tentativas. Acesso temporariamente bloqueado."
      : "Código incorreto.",
  };
}

async function createSession(admin: any, operator: any) {
  const token = randomToken();
  const tokenHash = await sha256(token);
  const now = Date.now();
  await writeDoc(admin, "security_gestor_app_sessions", tokenHash, {
    active: true,
    createdAt: new Date(now).toISOString(),
    expiresAt: new Date(now + SESSION_HOURS * 60 * 60 * 1000).toISOString(),
    source: "gestor-app", operator,
  });
  return token;
}

async function requireSession(admin: any, token: string) {
  if (!/^[A-Za-z0-9_-]{40,80}$/.test(token)) return false;
  const tokenHash = await sha256(token);
  const session = await readDoc(admin, "security_gestor_app_sessions", tokenHash);
  if (!session?.active) return false;
  const expiresAt = Date.parse(String(session.expiresAt || ""));
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    await deleteDoc(admin, "security_gestor_app_sessions", tokenHash);
    return false;
  }
  return tokenHash;
}

function pushMessage(status: string, order: any) {
  const code = text(order?.order_code || order?.raw_payload?.codigoPedido || "");
  const normalized = text(status).toUpperCase();
  const messages: Record<string,{title:string,body:string,hold?:boolean}> = {
    CONFIRMADO:{title:"Pedido aceito",body:"Seu pedido #"+code+" foi aceito pela loja."},
    EM_PREPARO:{title:"Seu pedido está em preparo",body:"Estamos preparando o pedido #"+code+"."},
    PRONTO:{title:"Pedido pronto",body:"Seu pedido #"+code+" está pronto.",hold:true},
    EM_ENTREGA:{title:"Saiu para entrega",body:"O pedido #"+code+" saiu para entrega.",hold:true},
    CONCLUIDO:{title:"Pedido entregue",body:"O pedido #"+code+" foi concluído.",hold:true},
    CANCELADO:{title:"Pedido cancelado",body:"O pedido #"+code+" foi cancelado.",hold:true},
  };
  return messages[normalized] || null;
}

async function sendCustomerPush(admin: any, order: any, status: string) {
  const message = pushMessage(status, order);
  if (!message || !order?.customer_id) return;
  try {
    const customer = await admin.from("customers").select("auth_user_id").eq("id",order.customer_id).maybeSingle();
    if (customer.error || !customer.data?.auth_user_id) return;
    const subscriptions = await admin.from("web_push_subscriptions")
      .select("endpoint,p256dh,auth")
      .eq("owner_id",customer.data.auth_user_id)
      .eq("active",true)
      .limit(20);
    if (subscriptions.error || !(subscriptions.data||[]).length) return;
    const config = await admin.from("push_private_config").select("value").eq("id","web_push").maybeSingle();
    const bridgeSecret = text(config.data?.value?.bridge_secret);
    if (!bridgeSecret) return;
    const orderRoute = text(order.firebase_id || order.id);
    const response = await fetch(CUSTOMER_PUSH_URL,{
      method:"POST",
      headers:{"Content-Type":"application/json","x-push-bridge":bridgeSecret},
      body:JSON.stringify({
        vapid_public_key:CUSTOMER_VAPID_PUBLIC_KEY,
        subscriptions:subscriptions.data,
        payload:{
          title:message.title,
          body:message.body,
          url:"/acompanhamento/"+encodeURIComponent(orderRoute),
          tag:"rodrigues-order-"+text(order.id),
          orderId:orderRoute,
          status:text(status).toUpperCase(),
          requireInteraction:!!message.hold,
          vibrate:[320,120,320,120,520]
        }
      }),
      signal:AbortSignal.timeout(6500)
    });
    if (!response.ok) {
      console.warn("[customer-push] upstream",response.status,await response.text().catch(()=>""));
      return;
    }
    const result = await response.json().catch(()=>({}));
    const expired=(Array.isArray(result?.failed)?result.failed:[])
      .filter((x:any)=>[404,410].includes(Number(x?.statusCode)))
      .map((x:any)=>text(x?.endpoint)).filter(Boolean);
    if (expired.length) await admin.from("web_push_subscriptions").update({active:false,updated_at:new Date().toISOString()}).in("endpoint",expired);
  } catch (error) {
    console.warn("[customer-push]",error instanceof Error?error.message:String(error));
  }
}

function cleanPatch(input: any) {
  const allowedStatus = new Set([
    "PENDENTE", "AGUARDANDO_PAGAMENTO", "CONFIRMADO", "EM_PREPARO",
    "PRONTO", "EM_ENTREGA", "CONCLUIDO", "CANCELADO"
  ]);
  const out: Record<string, unknown> = {};
  const status = text(input?.status).toUpperCase();
  if (status && allowedStatus.has(status)) out.status = status;

  for (const key of [
    "accepted_at", "preparing_at", "dispatched_at", "completed_at",
    "cancelled_at", "updated_at"
  ]) {
    if (input?.[key]) out[key] = text(input[key]);
  }

  if (input?.raw_payload && typeof input.raw_payload === "object") {
    out.raw_payload = input.raw_payload;
  }
  if (typeof input?.cancellation_reason === "string") {
    out.cancellation_reason = input.cancellation_reason.slice(0, 1000);
  }
  return out;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    const origin = text(req.headers.get("origin"));
    if (origin && !allowedOrigin(origin)) return json(req, { error: "origin_not_allowed" }, 403);
    return new Response(null, { status: 204, headers: responseHeaders(req) });
  }
  if (req.method !== "POST") return json(req, { error: "method_not_allowed" }, 405);

  const origin = text(req.headers.get("origin"));
  if (!allowedOrigin(origin)) return json(req, { error: "origin_not_allowed" }, 403);

  const url = Deno.env.get("SUPABASE_URL") || "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!url || !serviceKey) return json(req, { error: "server_not_configured" }, 500);

  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  let body: any = {};
  try {
    body = await req.json();
  } catch {
    return json(req, { error: "invalid_json", message: "Solicitação inválida." }, 400);
  }

  try {
    const action = text(body?.action).toLowerCase();

    if (action === "login") {
      const pin = text(body?.pin).replace(/\D/g, "").slice(0, 12);
      if(body.operator_id){
        const code=text(body.operator_id).toLowerCase();
        const record=await readDoc(admin,"security_gestor_app_staff",code);
        const attemptId=await sha256(clientIp(req)+code);
        const attempt=await readDoc(admin,"security_gestor_app_attempts",attemptId)||{};
        if(Number(attempt.lockedUntil)>Date.now())return json(req,{error:"login_denied",message:"Aguarde 15 minutos para tentar novamente."},429);
        if(!record?.active||await sha256(record.salt+pin)!==record.hash){
          const count=(Date.now()-Number(attempt.at)<900000?Number(attempt.count):0)+1;
          await writeDoc(admin,"security_gestor_app_attempts",attemptId,{count,at:Date.now(),lockedUntil:count>=5?Date.now()+900000:0});
          return json(req,{error:"login_denied",message:"Acesso da equipe inválido."},401);
        }
        const operator={id:code,name:record.name,role:record.role,version:record.version};
        const token=await createSession(admin,operator);
        await deleteDoc(admin,"security_gestor_app_attempts",attemptId);
        return json(req,{ok:true,session_token:token,operator});
      }
      const checked = await verifyPin(admin, req, pin);
      if (!checked.ok) return json(req, { error: "login_denied", message: checked.message }, checked.status);
      const operator={id:"owner",name:"Dono",role:"owner"};
      const sessionToken = await createSession(admin,operator);
      return json(req, { ok: true, session_token: sessionToken, expires_in_hours: SESSION_HOURS, operator });
    }

    const token = text(body?.session_token);
    const sessionHash = await requireSession(admin, token);
    if (!sessionHash) return json(req, { error: "session_expired", message: "Digite o código para entrar novamente." }, 401);

    const sessionRecord=await readDoc(admin,"security_gestor_app_sessions",sessionHash);
    const operator=sessionRecord?.operator;
    if(!operator?.role)return json(req,{error:"session_expired"},401);
    if(operator.id!=="owner"){
      const account=await readDoc(admin,"security_gestor_app_staff",operator.id);
      if(!account?.active||account.version!==operator.version)return json(req,{error:"session_expired"},401);
    }
    const readActions=new Set(["me","orders","order_items","order_events","store","products","builder_catalog","couriers","messages","conversations","conversation_for_order","logout","list"]);
    const attendantActions=new Set([...readActions,"manual_order","update_order","assign_courier","send_message"]);
    if(operator.role==="montador"&&!new Set([...readActions,"update_order"]).has(action))return json(req,{error:"forbidden",message:"Esta função está disponível para outro perfil."},403);
    if(operator.role==="atendente"&&!attendantActions.has(action))return json(req,{error:"forbidden",message:"Esta função está disponível para o dono."},403);
    const extra=await appActions({admin,req,body,action,operator,json,readDoc,writeDoc,sha256});
    if(extra)return extra;
    if (action === "logout") {
      await deleteDoc(admin, "security_gestor_app_sessions", sessionHash);
      return json(req, { ok: true });
    }

    if (action === "online") {
      const cutoff = new Date(Date.now() - 90_000).toISOString();
      const result = await admin
        .from("client_online_sessions")
        .select("session_id,customer_name,current_page,first_seen_at,last_seen_at,user_agent")
        .gte("last_seen_at", cutoff)
        .order("last_seen_at", { ascending: false });
      if (result.error) throw result.error;
      const automated = (ua: unknown) =>
        /HeadlessChrome|Lighthouse|Googlebot|bingbot|crawler|spider|bot\b|Playwright|Puppeteer|Netlify/i.test(text(ua));
      const clients = (result.data || [])
        .filter((row: any) => !automated(row.user_agent))
        .map((row: any) => ({
          id: row.session_id,
          name: row.customer_name || "Cliente online",
          page: row.current_page || "/",
          firstSeenAt: row.first_seen_at,
          lastSeenAt: row.last_seen_at,
        }));
      return json(req, {
        ok: true,
        count: clients.length,
        clients,
        generatedAt: new Date().toISOString(),
      });
    }

    if (action === "store") {
      const result = await admin
        .from("store_settings")
        .select("id,value,updated_at")
        .in("id", ["logistica_ultra_v4","operacao"]);
      if (result.error) throw result.error;
      const rows = result.data || [];
      return json(req, {
        ok: true,
        store: rows.find((r:any)=>r.id==="logistica_ultra_v4") || null,
        operation: rows.find((r:any)=>r.id==="operacao") || null
      });
    }

    if (action === "set_operation") {
      const open = Boolean(body?.open);
      const current = await admin
        .from("store_settings")
        .select("value")
        .eq("id","operacao")
        .maybeSingle();
      if (current.error) throw current.error;
      const value = {
        ...(current.data?.value || {}),
        aberta: open,
        lojaAberta: open,
        aceitarPedidos: open,
        modo: "manual",
        updatedAt: new Date().toISOString(),
        atualizadoEm: new Date().toISOString(),
        atualizadoPor: "Gestor 20 RAC"
      };
      const saved = await admin
        .from("store_settings")
        .upsert({id:"operacao",value,updated_at:new Date().toISOString()},{onConflict:"id"})
        .select("id,value,updated_at")
        .single();
      if (saved.error) throw saved.error;
      return json(req,{ok:true,operation:saved.data});
    }

    if (action === "orders") {
      let query=admin.from("orders").select("*").order("created_at",{ascending:false});
      if(body.history){
        const offset=Math.max(0,Number(body.offset)||0);
        if(body.from)query=query.gte("created_at",text(body.from));
        if(body.to)query=query.lt("created_at",text(body.to));
        query=query.range(offset,offset+199);
      }else query=query.not("status","in","(CONCLUIDO,CONCLUÍDO,ENTREGUE,CANCELADO,CANCELADA,RETIRADO,FINALIZADO,COMPLETED,DELIVERED,CANCELLED,CANCELED)").limit(1000);
      const ordersResult=await query;
      if (ordersResult.error) throw ordersResult.error;
      const orders = ordersResult.data || [];
      if(body.include_recent&&!body.history){
        const recent=await admin.from("orders").select("*").gte("updated_at",new Date(Date.now()-3600000).toISOString()).order("updated_at",{ascending:false}).limit(200);
        if(recent.error)throw recent.error;
        for(const row of recent.data||[])if(!orders.some((o:any)=>o.id===row.id))orders.push(row);
      }
      const itemResult=orders.length?await admin.from("order_items").select("*").in("order_id",orders.map((o:any)=>o.id)).order("created_at",{ascending:true}):{data:[]};
      if(itemResult.error)throw itemResult.error;
      for(const order of orders)order.items=(itemResult.data||[]).filter((i:any)=>i.order_id===order.id);
      const ids = Array.from(new Set(orders.map((o: any) => o.customer_id).filter(Boolean)));
      let customers: any[] = [];
      if (ids.length) {
        const customersResult = await admin
          .from("customers")
          .select("id,name,phone,email,tax_id,address")
          .in("id", ids);
        if (customersResult.error) throw customersResult.error;
        customers = customersResult.data || [];
      }

      for(const order of orders)order.customer=customers.find((c:any)=>c.id===order.customer_id)||null;

      const onlineCutoff = new Date(Date.now() - 90_000).toISOString();
      const onlineResult = await admin
        .from("client_online_sessions")
        .select("session_id,customer_name,current_page,first_seen_at,last_seen_at,user_agent")
        .gte("last_seen_at", onlineCutoff)
        .order("last_seen_at", { ascending: false });
      if (onlineResult.error) throw onlineResult.error;

      const automated = (ua: unknown) =>
        /HeadlessChrome|Lighthouse|Googlebot|bingbot|crawler|spider|bot\b|Playwright|Puppeteer|Netlify/i.test(text(ua));
      const onlineClients = (onlineResult.data || [])
        .filter((row: any) => !automated(row.user_agent))
        .map((row: any) => ({
          id: row.session_id,
          name: row.customer_name || "Cliente online",
          page: row.current_page || "/",
          firstSeenAt: row.first_seen_at,
          lastSeenAt: row.last_seen_at,
        }));

      return json(req, {
        ok: true,
        orders,
        customers,
        online_count: onlineClients.length,
        online_clients: onlineClients,
      });
    }


    if (action === "builder_catalog") {
      const collections=["bases","cardapio_acai","acompanhamentos_gratis","adicionais","coberturas","utensilios"];
      const result=await admin.from("app_documents")
        .select("collection_name,document_id,data,updated_at")
        .in("collection_name",collections)
        .order("collection_name",{ascending:true});
      if(result.error) throw result.error;
      const items=(result.data||[]).map((row:any)=>({
        collection:row.collection_name,
        id:row.document_id,
        name:row.data?.nome||row.data?.name||row.document_id,
        price:Number(row.data?.preco||row.data?.price||0),
        order:Number(row.data?.ordem??999),
        available:row.data?.disponivel!==false&&row.data?.pausado!==true&&row.data?.ativo!==false&&row.data?.active!==false,
        data:row.data||{},
        updated_at:row.updated_at
      })).sort((a:any,b:any)=>a.collection.localeCompare(b.collection)||a.order-b.order||a.name.localeCompare(b.name,"pt-BR"));
      return json(req,{ok:true,items});
    }

    if (action === "update_builder_item") {
      const collections=new Set(["bases","cardapio_acai","acompanhamentos_gratis","adicionais","coberturas","utensilios"]);
      const collection=text(body?.collection),documentId=text(body?.document_id);
      if(!collections.has(collection)||!documentId)return json(req,{error:"invalid_builder_item",message:"Item do Monte seu pedido inválido."},400);
      const current=await admin.from("app_documents").select("data").eq("collection_name",collection).eq("document_id",documentId).maybeSingle();
      if(current.error)throw current.error;
      if(!current.data)return json(req,{error:"builder_item_not_found",message:"Item não encontrado."},404);
      const data={...(current.data.data||{})};
      if(typeof body?.available==="boolean"){
        data.disponivel=body.available;
        data.pausado=!body.available;
        if("ativo" in data)data.ativo=true;
        if("active" in data)data.active=true;
      }
      const now=new Date().toISOString();
      const saved=await admin.from("app_documents").update({data,updated_at:now})
        .eq("collection_name",collection).eq("document_id",documentId)
        .select("collection_name,document_id,data,updated_at").single();
      if(saved.error)throw saved.error;
      return json(req,{ok:true,item:saved.data});
    }

    if (action === "products") {
      const result = await admin
        .from("products")
        .select("id,name,description,price,compare_at_price,sort_order,available,active,image_url,metadata,updated_at")
        .order("name", { ascending: true })
        .limit(200);
      if (result.error) throw result.error;
      return json(req, { ok: true, products: result.data || [] });
    }

    if (action === "update_product") {
      const productId = text(body?.product_id);
      if (!productId) return json(req,{error:"invalid_product",message:"Produto inválido."},400);
      const patch:any = {updated_at:new Date().toISOString()};
      if (typeof body?.available === "boolean") patch.available = body.available;
      if (typeof body?.active === "boolean") patch.active = body.active;
      const result = await admin
        .from("products")
        .update(patch)
        .eq("id",productId)
        .select("id,name,description,price,compare_at_price,sort_order,available,active,image_url,metadata,updated_at")
        .single();
      if (result.error) throw result.error;
      return json(req,{ok:true,product:result.data});
    }

    if (action === "order_items") {
      const orderId = text(body?.order_id);
      const result = await admin
        .from("order_items")
        .select("*")
        .eq("order_id", orderId)
        .order("created_at", { ascending: true });
      if (result.error) throw result.error;
      return json(req, { ok: true, items: result.data || [] });
    }

    if (action === "update_order") {
      const orderId = text(body?.order_id);
      const patch = cleanPatch(body?.patch || {});
      if (!orderId || !Object.keys(patch).length) {
        return json(req, { error: "invalid_update", message: "Alteração inválida." }, 400);
      }

      const currentResult = await admin
        .from("orders")
        .select("*")
        .eq("id", orderId)
        .maybeSingle();
      if (currentResult.error) throw currentResult.error;
      const current = currentResult.data;
      if (!current) return json(req, { error: "order_not_found", message: "Pedido não encontrado." }, 404);

      const targetStatus = text((patch as any).status).toUpperCase();
      if(operator.role==="montador"&&!["EM_PREPARO","PRONTO"].includes(targetStatus))return json(req,{error:"forbidden"},403);
      const currentStatus = text(current.status).toUpperCase();

      const terminal=new Set(["CONCLUIDO","CONCLUÍDO","ENTREGUE","CANCELADO","CANCELADA","RETIRADO","FINALIZADO","COMPLETED","DELIVERED","CANCELLED","CANCELED"]);
      if(terminal.has(currentStatus))return json(req,{error:"closed",message:"Este pedido já está encerrado."},409);
      const transitions:any={CONFIRMADO:["PENDENTE","NOVO","RECEBIDO","ENVIADO","AGUARDANDO_CONFIRMACAO","AGUARDANDO_ACEITE","AGUARDANDO_PAGAMENTO","SENT"],EM_PREPARO:["CONFIRMADO","ACEITO","FILA"],PRONTO:["EM_PREPARO","PREPARANDO"],EM_ENTREGA:["PRONTO"],CONCLUIDO:["PRONTO","EM_ENTREGA","SAIU_PARA_ENTREGA","DESPACHADO","A_CAMINHO_CLIENTE"]};
      if(targetStatus!=="CANCELADO"&&!transitions[targetStatus]?.includes(currentStatus))return json(req,{error:"invalid_transition",message:"Atualize o pedido antes de mudar o status."},409);
      if (targetStatus === "CONFIRMADO") {
        if (current.accepted_at || !transitions.CONFIRMADO.includes(currentStatus)) {
          return json(req, { error: "order_not_pending", message: "Este pedido não está mais aguardando aceite." }, 409);
        }
        const createdAt = Date.parse(String(current.created_at || ""));
        const ageMs = Date.now() - createdAt;
        if (!Number.isFinite(createdAt) || ageMs > 5 * 60 * 1000) {
          return json(req, {
            error: "accept_deadline_passed",
            message: "O prazo de 5 minutos para aceitar este pedido expirou. Cancele o pedido informando o motivo."
          }, 409);
        }
      }

      if (targetStatus === "CANCELADO") {
        const reason = text((patch as any).cancellation_reason);
        if (!reason || reason.length < 3) {
          return json(req, { error: "cancellation_reason_required", message: "Informe o motivo do cancelamento ou rejeição." }, 400);
        }
        if (currentStatus === "CANCELADO") {
          return json(req, { error: "already_cancelled", message: "Este pedido já está cancelado." }, 409);
        }
      }

      delete (patch as any).raw_payload;
      const stamp=new Date().toISOString();
      patch.updated_at=stamp;
      const stamps:any={CONFIRMADO:"accepted_at",EM_PREPARO:"preparing_at",EM_ENTREGA:"dispatched_at",CONCLUIDO:"completed_at",CANCELADO:"cancelled_at"};
      if(stamps[targetStatus])patch[stamps[targetStatus]]=stamp;
      patch.raw_payload={...(current.raw_payload||{}),status:targetStatus,statusPedido:targetStatus,statusLoja:targetStatus,updatedAt:stamp};
      const updated = await admin
        .from("orders")
        .update(patch)
        .eq("id", orderId).eq("updated_at",current.updated_at)
        .select("*")
        .maybeSingle();
      if(!updated.error&&!updated.data)return json(req,{error:"conflict",message:"Outro operador alterou o pedido. Atualize."},409);
      if (updated.error) throw updated.error;

      if (targetStatus) {
        await admin.from("order_status_events").insert({
          order_id: orderId,
          status: targetStatus,
          note: targetStatus === "CANCELADO" ? text((patch as any).cancellation_reason) : null,
          actor_type: "staff",
          actor_id: operator.id,
          source: "RODRIGUES_GESTOR_APP",
          occurred_at: new Date().toISOString(),
          raw_payload: {
            source: "gestor20rac",
            cancellation_reason: targetStatus === "CANCELADO" ? text((patch as any).cancellation_reason) : null
          },
        });
      }

      if (targetStatus) await sendCustomerPush(admin, updated.data, targetStatus);
      return json(req, { ok: true, order: updated.data });
    }

    if (action === "conversation_for_order") {
      const orderId = text(body?.order_id);
      const customerId = text(body?.customer_id) || null;
      let result = await admin
        .from("conversations")
        .select("*")
        .eq("order_id", orderId)
        .order("created_at", { ascending: true })
        .limit(1)
        .maybeSingle();
      if (result.error) throw result.error;

      let conversation = result.data;
      if (!conversation) {
        const inserted = await admin
          .from("conversations")
          .insert({
            order_id: orderId,
            customer_id: customerId,
            status: "open",
            metadata: { source: "gestor20rac" },
          })
          .select("*")
          .single();
        if (inserted.error) throw inserted.error;
        conversation = inserted.data;
      }
      return json(req, { ok: true, conversation });
    }

    if (action === "messages") {
      const conversationId = text(body?.conversation_id);
      const result = await admin
        .from("messages")
        .select("*")
        .eq("conversation_id", conversationId)
        .order("created_at", { ascending: true });
      if (result.error) throw result.error;
      return json(req, { ok: true, messages: result.data || [] });
    }

    if (action === "send_message") {
      const conversationId = text(body?.conversation_id);
      const message = text(body?.body).slice(0, 2000);
      if (!conversationId || !message) {
        return json(req, { error: "invalid_message", message: "Mensagem vazia." }, 400);
      }
      const result = await admin
        .from("messages")
        .insert({
          conversation_id: conversationId,
          sender_type: "staff",
          sender_id: "gestor20rac-pin",
          body: message,
          metadata: { source: "gestor20rac" },
        })
        .select("*")
        .single();
      if (result.error) throw result.error;

      await admin
        .from("conversations")
        .update({ updated_at: new Date().toISOString() })
        .eq("id", conversationId);

      return json(req, { ok: true, message: result.data });
    }


    if (action === "couriers") {
      const result = await admin
        .from("couriers")
        .select("id,name,phone,status,online,tracking_enabled,current_lat,current_lng,last_location_at,metadata,updated_at")
        .order("online", { ascending: false })
        .order("name", { ascending: true });
      if (result.error) throw result.error;
      return json(req,{ok:true,couriers:result.data||[]});
    }

    if (action === "assign_courier") {
      const orderId = text(body?.order_id);
      const courierId = text(body?.courier_id) || null;
      if (!orderId) return json(req,{error:"order_required",message:"Pedido inválido."},400);
      const current = await admin.from("orders").select("*").eq("id",orderId).maybeSingle();
      if (current.error) throw current.error;
      if (!current.data) return json(req,{error:"order_not_found",message:"Pedido não encontrado."},404);
      const terminal = new Set(["CONCLUIDO","CONCLUÍDO","FINALIZADO","ENTREGUE","CANCELADO","CANCELADA"]);
      if (terminal.has(text(current.data.status).toUpperCase())) {
        return json(req,{error:"order_closed",message:"Este pedido já foi encerrado."},409);
      }
      let courier:any = null;
      if (courierId) {
        const cq = await admin.from("couriers")
          .select("id,name,phone,status,online,tracking_enabled")
          .eq("id",courierId).maybeSingle();
        if (cq.error) throw cq.error;
        if (!cq.data) return json(req,{error:"courier_not_found",message:"Entregador não encontrado."},404);
        courier = cq.data;
      }
      const now = new Date().toISOString();
      const raw = {...(current.data.raw_payload||{})};
      if (courier) {
        raw.entregadorId = courier.id;
        raw.entregadorNome = courier.name || "Entregador";
        raw.entregadorTelefone = courier.phone || "";
        raw.entregadorAtribuidoEm = now;
      } else {
        delete raw.entregadorId;
        delete raw.entregadorNome;
        delete raw.entregadorTelefone;
        raw.entregadorRemovidoEm = now;
      }
      raw.updatedAt = now;
      const saved = await admin.from("orders")
        .update({courier_id:courier?.id||null,raw_payload:raw,updated_at:now})
        .eq("id",orderId)
        .select("*").single();
      if (saved.error) throw saved.error;
      await admin.from("order_status_events").insert({
        order_id:orderId,
        status:courier?"ENTREGADOR_ATRIBUIDO":"ENTREGADOR_REMOVIDO",
        note:courier?("Entregador: "+(courier.name||"Entregador")):"Entregador removido",
        actor_type:"staff",
        actor_id:"gestor20rac-pin",
        source:"RODRIGUES_GESTOR_WEB_PIN",
        occurred_at:now,
        raw_payload:{courier_id:courier?.id||null,courier_name:courier?.name||null}
      });
      return json(req,{ok:true,order:saved.data,courier});
    }

    if (action === "order_events") {
      const orderId = text(body?.order_id);
      if (!orderId) return json(req,{error:"order_required",message:"Pedido inválido."},400);
      const result = await admin.from("order_status_events")
        .select("id,status,note,actor_type,actor_id,source,occurred_at,raw_payload")
        .eq("order_id",orderId)
        .order("occurred_at",{ascending:true})
        .limit(300);
      if (result.error) throw result.error;
      return json(req,{ok:true,events:result.data||[]});
    }

    if (action === "dashboard_stats") {
      const start = new Date();
      start.setHours(0,0,0,0);
      const today = start.toISOString();
      const result = await admin.from("orders")
        .select("id,status,total,created_at,accepted_at,completed_at,cancelled_at")
        .gte("created_at",today)
        .order("created_at",{ascending:false})
        .limit(500);
      if (result.error) throw result.error;
      const rows = result.data || [];
      const normalize=(v:any)=>text(v).toUpperCase();
      const activeSet=new Set(["PENDENTE","AGUARDANDO_PAGAMENTO","CONFIRMADO","ACEITO","EM_PREPARO","PRONTO","EM_ENTREGA","DESPACHADO","SAIU_PARA_ENTREGA"]);
      const completedSet=new Set(["CONCLUIDO","CONCLUÍDO","FINALIZADO","ENTREGUE","RETIRADO"]);
      const cancelledSet=new Set(["CANCELADO","CANCELADA"]);
      const completed=rows.filter((x:any)=>completedSet.has(normalize(x.status)));
      const accepted=rows.filter((x:any)=>x.accepted_at);
      const avgAcceptMs=accepted.length
        ? accepted.reduce((sum:number,x:any)=>sum+Math.max(0,Date.parse(x.accepted_at)-Date.parse(x.created_at)),0)/accepted.length
        : 0;
      return json(req,{ok:true,stats:{
        today_orders:rows.length,
        active:rows.filter((x:any)=>activeSet.has(normalize(x.status))).length,
        completed:completed.length,
        cancelled:rows.filter((x:any)=>cancelledSet.has(normalize(x.status))).length,
        revenue:completed.reduce((sum:number,x:any)=>sum+Number(x.total||0),0),
        average_accept_seconds:Math.round(avgAcceptMs/1000)
      }});
    }

    if (action === "reviews") {
      const reviewsResult = await admin
        .from("order_reviews")
        .select("id,order_id,customer_id,rating,comment,tags,visible,created_at,updated_at")
        .eq("visible",true)
        .order("created_at",{ascending:false})
        .limit(100);
      if (reviewsResult.error) throw reviewsResult.error;
      const reviews = reviewsResult.data || [];
      const orderIds = Array.from(new Set(reviews.map((r:any)=>r.order_id).filter(Boolean)));
      const customerIds = Array.from(new Set(reviews.map((r:any)=>r.customer_id).filter(Boolean)));
      let orders:any[] = [], customers:any[] = [];
      if (orderIds.length) {
        const q = await admin.from("orders").select("id,order_code,total,created_at,status").in("id",orderIds);
        if (q.error) throw q.error;
        orders = q.data || [];
      }
      if (customerIds.length) {
        const q = await admin.from("customers").select("id,name,phone").in("id",customerIds);
        if (q.error) throw q.error;
        customers = q.data || [];
      }
      return json(req,{ok:true,reviews,orders,customers});
    }

    if (action === "conversations") {
      const result = await admin
        .from("conversations")
        .select("id,order_id,status,updated_at")
        .order("updated_at", { ascending: false })
        .limit(50);
      if (result.error) throw result.error;
      return json(req, { ok: true, conversations: result.data || [] });
    }

    return json(req, { error: "unknown_action", message: "Ação não reconhecida." }, 400);
  } catch (error) {
    console.error("[gestor20rac-api]", error);
    return json(req, { error: "server_error", message: "Não foi possível concluir esta ação agora." }, 500);
  }
});
