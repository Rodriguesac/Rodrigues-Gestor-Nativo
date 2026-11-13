import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.56.1";

const reply = (data: Record<string,unknown>, status=200) => new Response(JSON.stringify(data), {
 status,headers:{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store"}
});
const validUuid = (s:string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
const clean = (v:unknown,n=300) => String(v??"").trim().slice(0,n);
async function digest(s:string) {
 const b=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(s));
 return Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,"0")).join("");
}

Deno.serve(async req => {
 if(req.method!=="POST") return reply({ok:false,error:"method_not_allowed"},405);
 const session=clean(req.headers.get("x-gestor-session"),100);
 if(!/^[A-Za-z0-9_-]{40,80}$/.test(session))
   return reply({ok:false,error:"session_required"},401);
 const url=Deno.env.get("SUPABASE_URL"),secret=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
 if(!url||!secret)return reply({ok:false,error:"not_configured"},503);
 const admin=createClient(url,secret,{auth:{persistSession:false,autoRefreshToken:false}});
 try{
  const hashed=await digest(session);
  const account=await admin.from("app_documents").select("data")
    .eq("collection_name","security_gestor_app_sessions")
    .eq("document_id",hashed).maybeSingle();
  if(account.error)throw account.error;
  const state=account.data?.data;
  const expiry=Date.parse(String(state?.expiresAt||""));
  if(!state?.active||!Number.isFinite(expiry)||expiry<=Date.now()||
    state.operator?.id!=="owner"||state.operator?.role!=="owner")
    return reply({ok:false,error:"owner_session_required"},403);

  const body=await req.json();
  if(!body||typeof body!=="object"||Array.isArray(body))
    return reply({ok:false,error:"invalid_payload"},400);
  const action=clean(body.action,40);
  const fail=(error:any)=>{if(error)throw error;};

  if(action==="catalog_structure"){
    const [categories,products]=await Promise.all([
      admin.from("categories").select("id,name,active").order("sort_order").limit(250),
      admin.from("products").select("id,category_id").limit(500)
    ]);
    fail(categories.error);fail(products.error);
    return reply({ok:true,categories:categories.data||[],
      products:products.data||[]});
  }
  if(action==="catalog_item_save"){
    const id=clean(body.id,48),collection=clean(body.collection,60);
    const name=clean(body.name,160),description=clean(body.description,3000);
    const image=clean(body.image_url,2048);
    const price=Number(body.price),compare=body.compare_at_price===null||body.compare_at_price===""
      ?null:Number(body.compare_at_price);
    const order=Number(body.sort_order??0),active=body.active!==false,
      available=body.available!==false;
    const groups=new Set(["products","bases","cardapio_acai","acompanhamentos_gratis",
      "adicionais","coberturas","utensilios"]);
    if(!groups.has(collection)||name.length<2||name.length>160||
      !Number.isFinite(price)||price<0||price>10000||
      !Number.isInteger(order)||order<0||order>100000||
      (image&&!image.startsWith("https://"))||
      (id&&(id.length>180||/[\u0000-\u001F]/.test(id)||
        (collection==="products"&&!validUuid(id))))||
      (compare!==null&&(!Number.isFinite(compare)||compare<0||compare>10000)))
      return reply({ok:false,error:"invalid_catalog_item"},400);
    let savedId="";
    if(collection==="products"){
      const categoryId=clean(body.category_id,36);
      if(!validUuid(categoryId))return reply({ok:false,error:"category_required"},400);
      const cat=await admin.from("categories").select("id")
        .eq("id",categoryId).eq("active",true).maybeSingle();
      fail(cat.error);
      if(!cat.data)return reply({ok:false,error:"category_not_available"},409);
      const record={name,description,price,compare_at_price:compare,
        image_url:image||null,sort_order:order,active,available,
        category_id:categoryId,updated_at:new Date().toISOString()};
      const row=id
        ?await admin.from("products").update(record).eq("id",id).select("id").single()
        :await admin.from("products").insert({...record,
           firebase_id:crypto.randomUUID()}).select("id").single();
      fail(row.error);savedId=String(row.data?.id||"");
    }else{
      const collectionName=collection;
      const documentId=id||crypto.randomUUID();
      const old=await admin.from("app_documents").select("data")
        .eq("collection_name",collectionName).eq("document_id",documentId).maybeSingle();
      fail(old.error);
      const previous=old.data?.data||{};
      const now=new Date().toISOString();
      const data={...previous,nome:name,name,descricao:description,description,
        preco:price,price,ordem:order,
        imagem:image,ativo:active,disponivel:available,pausado:!available,
        atualizadoEm:now};
      const variants=body.variant_prices&&typeof body.variant_prices==="object"
        ?body.variant_prices:{};
      if(collection==="cardapio_acai"){
        for(const key of ["misto","origem","cupuacu","polpanorte"]){
          if(variants[key]===undefined||variants[key]===null||variants[key]==="")continue;
          const n=Number(variants[key]);
          if(!Number.isFinite(n)||n<0||n>10000)
            return reply({ok:false,error:"invalid_variant_price"},400);
          data[key]=n;
        }
      }
      const row=await admin.from("app_documents").upsert({
        collection_name:collectionName,document_id:documentId,data,updated_at:now
      },{onConflict:"collection_name,document_id"});
      fail(row.error);savedId=documentId;
    }
    const version=await admin.from("catalog_version").select("version")
      .eq("id",1).maybeSingle();
    fail(version.error);
    const change=await admin.from("catalog_version").upsert({
      id:1,version:Number(version.data?.version||0)+1,updated_at:new Date().toISOString()
    },{onConflict:"id"});
    fail(change.error);
    return reply({ok:true,id:savedId});
  }
  if(action==="categories"){
    const r=await admin.from("categories").select("id,name,description,sort_order,active,metadata")
      .order("sort_order",{ascending:true}).limit(250);
    fail(r.error);return reply({ok:true,items:r.data||[]});
  }
  if(action==="category_save"){
    const name=clean(body.name,100);
    const description=clean(body.description,1000);
    const id=clean(body.id,36),active=body.active!==false;
    const order=Number(body.sort_order??0);
    if(name.length<2||!Number.isInteger(order)||order<0||order>100000||
      (id&&!validUuid(id)))return reply({ok:false,error:"invalid_category"},400);
    const row={name,description,sort_order:order,active,
      updated_at:new Date().toISOString()};
    const result=id
      ? await admin.from("categories").update(row).eq("id",id).select("id").single()
      : await admin.from("categories").insert({...row,
          firebase_id:crypto.randomUUID(),
          slug:name.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g,"")
           .replace(/[^a-z0-9]+/g,"-").replace(/^-|-$/g,"").slice(0,85)+"-"+crypto.randomUUID().slice(0,8)
        }).select("id").single();
    fail(result.error);
    const cur=await admin.from("catalog_version").select("version").eq("id",1).maybeSingle();
    if(!cur.error)await admin.from("catalog_version").upsert({
      id:1,version:Number(cur.data?.version||0)+1,updated_at:new Date().toISOString()
    },{onConflict:"id"});
    return reply({ok:true,id:result.data?.id});
  }
  if(action==="customers"){
    let query=admin.from("customers")
      .select("id,name,email,phone,created_at,updated_at")
      .order("updated_at",{ascending:false}).limit(200);
    const term=clean(body.search,80);
    if(term){
      const safe=term.replace(/[%_,()]/g,"").trim();
      if(safe.length<2)return reply({ok:false,error:"search_too_short"},400);
      query=query.or("name.ilike.%"+safe+"%,email.ilike.%"+safe+"%,phone.ilike.%"+safe+"%");
    }
    const r=await query;fail(r.error);
    return reply({ok:true,items:r.data||[]});
  }
  if(action==="customer_addresses"){
    const customer=clean(body.customer_id,36);
    if(!validUuid(customer))return reply({ok:false,error:"invalid_customer"},400);
    const r=await admin.from("customer_addresses")
      .select("id,label,street,number,district,complement,city,state,postal_code,active")
      .eq("customer_id",customer).order("created_at",{ascending:false}).limit(30);
    fail(r.error);return reply({ok:true,items:r.data||[]});
  }
  if(action==="assign_coupon"){
    const customer=clean(body.customer_id,36),code=clean(body.coupon_code,40).toUpperCase();
    if(!validUuid(customer)||!code.match(/^[A-Z0-9_-]{3,40}$/))
      return reply({ok:false,error:"invalid_assignment"},400);
    const [client,coupon]=await Promise.all([
      admin.from("customers").select("id").eq("id",customer).maybeSingle(),
      admin.from("coupons").select("id,active,ends_at")
        .eq("code",code).eq("active",true).maybeSingle()
    ]);
    fail(client.error);fail(coupon.error);
    if(!client.data||!coupon.data)return reply({ok:false,error:"customer_or_coupon_not_found"},404);
    if(coupon.data.ends_at&&Date.parse(coupon.data.ends_at)<Date.now())
      return reply({ok:false,error:"coupon_expired"},409);
    const existing=await admin.from("customer_coupons").select("id")
      .eq("coupon_id",coupon.data.id).eq("customer_id",customer).maybeSingle();
    fail(existing.error);
    if(existing.data)return reply({ok:true,already_assigned:true});
    const saved=await admin.from("customer_coupons").insert({
      coupon_id:coupon.data.id,customer_id:customer
    });fail(saved.error);
    return reply({ok:true,already_assigned:false});
  }
  if(action==="notices"){
    const r=await admin.from("notices")
      .select("id,title,body,image_url,active,audience,severity,sort_order,created_at")
      .order("created_at",{ascending:false}).limit(200);
    fail(r.error);return reply({ok:true,items:r.data||[]});
  }
  if(action==="notice_save"){
    const id=clean(body.id,36), title=clean(body.title,160);
    const content=clean(body.body,1500),imageUrl=clean(body.image_url,2048);
    if((id&&!validUuid(id))||title.length<3||content.length<3||
       (imageUrl&&!imageUrl.startsWith("https://")))
      return reply({ok:false,error:"invalid_notice"},400);
    const row={title,body:content,image_url:imageUrl||null,active:body.active!==false,
      audience:"cliente",sort_order:Math.max(0,Math.trunc(Number(body.sort_order)||0)),
      updated_at:new Date().toISOString()};
    const r=id?await admin.from("notices").update(row).eq("id",id).select("id").single()
      :await admin.from("notices").insert({...row,firebase_id:crypto.randomUUID()})
        .select("id").single();
    fail(r.error);return reply({ok:true,id:r.data?.id});
  }
  return reply({ok:false,error:"unknown_action"},400);
 }catch(error){
  console.error("[gestor-admin-extras] internal error",error);
  return reply({ok:false,error:"operation_failed"},503);
 }
});
