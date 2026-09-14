// @ts-nocheck
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.56.1";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const enc = new TextEncoder();
const dec = new TextDecoder();
const ADDITIONAL_COLLECTIONS = [
  "addons",
  "adicionais",
  "acompanhamentos_gratis",
  "coberturas",
  "bases",
  "utensilios"
];

const responseHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type, x-gadm-session",
  "access-control-allow-methods": "POST, OPTIONS"
};

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: responseHeaders });

const text = (value) => String(value ?? "").trim();
const nowIso = () => new Date().toISOString();

function fromBase64url(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/") +
    "===".slice((value.length + 3) % 4);
  const binary = atob(normalized);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function hmacKey(secret) {
  return crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"]
  );
}

async function verifySession(token, secret) {
  const [body, signature] = String(token || "").split(".");
  if (!body || !signature) return null;
  try {
    const key = await hmacKey(secret);
    const valid = await crypto.subtle.verify(
      "HMAC",
      key,
      fromBase64url(signature),
      enc.encode(body)
    );
    if (!valid) return null;
    const payload = JSON.parse(dec.decode(fromBase64url(body)));
    if (!payload?.sub || !payload?.exp ||
        Number(payload.exp) < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

function normalize(value) {
  return text(value)
    .toLocaleLowerCase("pt-BR")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/(\d)\s+(ml|l|kg|g)\b/g, "$1$2")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function cleanTarget(value) {
  let target = normalize(value)
    .replace(/^(?:o|a|os|as|um|uma)\s+/, "")
    .replace(/^(?:produto|adicional|acompanhamento|ingrediente|item)\s+/, "")
    .replace(/^(?:o|a|os|as|um|uma)\s+/, "")
    .replace(/\s+(?:que\s+)?(?:acabou|esta em falta|ficou em falta|sem estoque)$/, "")
    .replace(/\s+(?:de novo|novamente|outra vez)$/, "")
    .trim();
  return target;
}

function parseCommand(transcript) {
  let phrase = normalize(transcript)
    .replace(/^(?:ok google|ola maca|ei maca)\s+/, "")
    .trim();

  const closesStore =
    /\b(?:fechar|feche|encerrar|encerre|desligar|desligue)\b.*\b(?:loja|pedidos)\b/.test(phrase) ||
    /\b(?:loja|pedidos)\b.*\b(?:fechar|fechada|encerrar|parar)\b/.test(phrase);
  if (closesStore) return { kind: "store", open: false };

  const opensStore =
    /\b(?:abrir|abra|reabrir|reabra|ligar|ligue|reativar|reative)\b.*\b(?:loja|pedidos)\b/.test(phrase) ||
    /\b(?:loja|pedidos)\b.*\b(?:abrir|aberta|reativar|retomar|voltar)\b/.test(phrase);
  if (opensStore) return { kind: "store", open: true };

  const rules = [
    { available: false, regex: /^(?:pausar|pause|desativar|desative|bloquear|bloqueie|retirar|tire|tirar)\s+(.+)$/ },
    { available: false, regex: /^(?:acabou|faltou)\s+(.+)$/ },
    { available: false, regex: /^(.+?)\s+(?:acabou|esta em falta|ficou em falta|sem estoque)$/ },
    { available: true, regex: /^(?:reativar|reative|ativar|ative|liberar|libere|despausar|despause)\s+(.+)$/ },
    { available: true, regex: /^(?:voltar|volte)\s+(?:com\s+)?(.+)$/ },
    { available: true, regex: /^(?:colocar|coloque)\s+(.+?)\s+(?:de volta|novamente)$/ },
    { available: true, regex: /^(.+?)\s+(?:voltou|chegou|esta disponivel)$/ }
  ];

  for (const rule of rules) {
    const match = phrase.match(rule.regex);
    if (match) {
      const target = cleanTarget(match[1]);
      if (target) return { kind: "item", available: rule.available, target };
    }
  }
  return null;
}

function aliasesForProduct(row) {
  const metadata = row?.metadata && typeof row.metadata === "object" ? row.metadata : {};
  const name = text(row?.name || metadata.nome);
  const size = text(metadata.tamanho || metadata.volume || metadata.peso);
  const brand = text(metadata.marca);
  return [
    name,
    size ? name + " " + size : "",
    brand && !normalize(name).includes(normalize(brand)) ? brand + " " + name : "",
    row?.firebase_id || "",
    row?.slug || ""
  ].filter(Boolean);
}

function aliasesForAdditional(row) {
  const data = row?.data && typeof row.data === "object" ? row.data : {};
  const name = text(data.nome || data.name || data.titulo || data.label || row.document_id);
  return [
    name,
    row.document_id,
    data.apelido,
    data.slug,
    data.codigo
  ].map(text).filter(Boolean);
}

function scoreCandidate(candidate, target) {
  const aliases = candidate.aliases.map(normalize).filter(Boolean);
  if (aliases.some((alias) => alias === target)) return 100;
  if (aliases.some((alias) => alias.startsWith(target + " ") || target.startsWith(alias + " "))) return 86;
  if (aliases.some((alias) => alias.includes(target))) return 76;
  const words = target.split(" ").filter((word) => word.length > 1);
  if (words.length && aliases.some((alias) => words.every((word) => alias.includes(word)))) return 66;
  return 0;
}

async function bumpCatalog(admin) {
  const { data } = await admin
    .from("catalog_version")
    .select("version")
    .eq("id", 1)
    .maybeSingle();
  const { error } = await admin
    .from("catalog_version")
    .upsert({
      id: 1,
      version: Number(data?.version || 0) + 1,
      updated_at: nowIso()
    }, { onConflict: "id" });
  if (error) throw error;
}

async function setStoreOpen(admin, open, actor) {
  const { data: current, error: readError } = await admin
    .from("store_settings")
    .select("value")
    .eq("id", "operacao")
    .maybeSingle();
  if (readError) throw readError;

  const timestamp = nowIso();
  const next = {
    ...(current?.value || {}),
    id: "master",
    modo: "manual",
    aberta: open,
    lojaAberta: open,
    aceitarPedidos: open,
    pausaAte: null,
    manutencao: false,
    emergencia: false,
    updatedAt: timestamp,
    atualizadoEm: timestamp,
    atualizadoPor: text(actor?.name || actor?.sub || "GESTOR_VOICE_ANDROID"),
    origemBanco: "SUPABASE"
  };

  const { error: settingError } = await admin
    .from("store_settings")
    .upsert({ id: "operacao", value: next, updated_at: timestamp }, { onConflict: "id" });
  if (settingError) throw settingError;

  const { data: legacy } = await admin
    .from("app_documents")
    .select("created_at")
    .eq("collection_name", "gadm_operacao")
    .eq("document_id", "master")
    .maybeSingle();
  const legacyRow = {
    collection_name: "gadm_operacao",
    document_id: "master",
    data: next,
    updated_at: timestamp
  };
  if (!legacy) legacyRow.created_at = timestamp;
  const { error: legacyError } = await admin
    .from("app_documents")
    .upsert(legacyRow, { onConflict: "collection_name,document_id" });
  if (legacyError) throw legacyError;

  return {
    ok: true,
    type: "store",
    state: open ? "open" : "closed",
    message: open ? "Loja aberta e aceitando pedidos." : "Loja fechada para novos pedidos."
  };
}

async function loadCandidates(admin) {
  const [productsQuery, additionsQuery] = await Promise.all([
    admin
      .from("products")
      .select("id,firebase_id,slug,name,active,available,metadata,categories(name)"),
    admin
      .from("app_documents")
      .select("collection_name,document_id,data")
      .in("collection_name", ADDITIONAL_COLLECTIONS)
  ]);
  if (productsQuery.error) throw productsQuery.error;
  if (additionsQuery.error) throw additionsQuery.error;

  const products = (productsQuery.data || []).map((row) => {
    const aliases = aliasesForProduct(row);
    const name = text(row.name || row.metadata?.nome || row.firebase_id || row.id);
    const size = text(row.metadata?.tamanho || row.metadata?.volume || row.metadata?.peso);
    return {
      kind: "product",
      key: row.id,
      firebaseId: text(row.firebase_id),
      name,
      label: size ? name + " " + size : name,
      aliases,
      row
    };
  });

  const additions = (additionsQuery.data || []).map((row) => {
    const aliases = aliasesForAdditional(row);
    const name = text(row.data?.nome || row.data?.name || row.data?.titulo ||
      row.data?.label || row.document_id);
    return {
      kind: "additional",
      key: row.document_id,
      collection: row.collection_name,
      name,
      label: name,
      aliases,
      row
    };
  });

  return [...products, ...additions];
}

function selectCandidates(candidates, target) {
  const ranked = candidates
    .map((candidate) => ({ ...candidate, score: scoreCandidate(candidate, target) }))
    .filter((candidate) => candidate.score > 0)
    .sort((a, b) => b.score - a.score || a.label.localeCompare(b.label, "pt-BR"));

  if (!ranked.length || ranked[0].score < 66) {
    return { selected: [], suggestions: [], reason: "not_found" };
  }

  const bestScore = ranked[0].score;
  const best = ranked.filter((candidate) => candidate.score === bestScore);
  if (best.length === 1) return { selected: best, suggestions: [], reason: "matched" };

  const allSameAdditional =
    best.every((candidate) => candidate.kind === "additional") &&
    new Set(best.map((candidate) => normalize(candidate.name))).size === 1;
  if (allSameAdditional) return { selected: best, suggestions: [], reason: "matched_duplicates" };

  const uniqueLabels = [...new Set(best.map((candidate) => candidate.label))].slice(0, 6);
  return { selected: [], suggestions: uniqueLabels, reason: "ambiguous" };
}

async function updateProduct(admin, candidate, available, timestamp) {
  const row = candidate.row;
  const metadata = {
    ...(row.metadata || {}),
    ativo: available,
    disponivel: available,
    pausado: !available,
    atualizadoEm: timestamp,
    origemBanco: "SUPABASE_VOICE"
  };
  const { error } = await admin
    .from("products")
    .update({
      active: available,
      available,
      metadata,
      updated_at: timestamp
    })
    .eq("id", candidate.key);
  if (error) throw error;

  if (candidate.firebaseId) {
    const { data: legacy, error: legacyReadError } = await admin
      .from("app_documents")
      .select("data")
      .eq("collection_name", "catalogo_produtos")
      .eq("document_id", candidate.firebaseId)
      .maybeSingle();
    if (legacyReadError) throw legacyReadError;
    if (legacy) {
      const legacyData = {
        ...(legacy.data || {}),
        ativo: available,
        disponivel: available,
        pausado: !available,
        atualizadoEm: timestamp,
        origemBanco: "SUPABASE_VOICE"
      };
      const { error: legacyUpdateError } = await admin
        .from("app_documents")
        .update({ data: legacyData, updated_at: timestamp })
        .eq("collection_name", "catalogo_produtos")
        .eq("document_id", candidate.firebaseId);
      if (legacyUpdateError) throw legacyUpdateError;
    }
  }
}

async function updateAdditional(admin, candidate, available, timestamp) {
  const data = {
    ...(candidate.row.data || {}),
    ativo: available,
    disponivel: available,
    pausado: !available,
    atualizadoEm: timestamp,
    origemBanco: "SUPABASE_VOICE"
  };
  const { error } = await admin
    .from("app_documents")
    .update({ data, updated_at: timestamp })
    .eq("collection_name", candidate.collection)
    .eq("document_id", candidate.key);
  if (error) throw error;
}

async function setItemAvailable(admin, target, available) {
  const candidates = await loadCandidates(admin);
  const match = selectCandidates(candidates, target);

  if (match.reason === "not_found") {
    return json({
      ok: false,
      code: "item_not_found",
      message: "Não encontrei " + target + " no catálogo atual do Supabase."
    }, 404);
  }

  if (match.reason === "ambiguous") {
    return json({
      ok: false,
      code: "ambiguous_item",
      needs_clarification: true,
      candidates: match.suggestions,
      message: "Encontrei mais de um item: " + match.suggestions.join(", ") +
        ". Fale novamente usando o nome completo ou o tamanho."
    }, 409);
  }

  const timestamp = nowIso();
  for (const candidate of match.selected) {
    if (candidate.kind === "product") {
      await updateProduct(admin, candidate, available, timestamp);
    } else {
      await updateAdditional(admin, candidate, available, timestamp);
    }
  }
  await bumpCatalog(admin);

  const names = [...new Set(match.selected.map((candidate) => candidate.label))];
  const itemName = names.length === 1 ? names[0] : match.selected[0].name;
  return json({
    ok: true,
    type: match.selected.every((candidate) => candidate.kind === "additional")
      ? "additional"
      : "product",
    state: available ? "active" : "paused",
    count: match.selected.length,
    items: names,
    message: itemName + (available ? " reativado no cardápio." : " pausado no cardápio.")
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: responseHeaders });
  if (req.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ ok: false, error: "server_not_configured" }, 500);
  }

  let body = {};
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "invalid_json" }, 400);
  }

  const sessionToken = req.headers.get("x-gadm-session") || text(body.session);
  const actor = await verifySession(sessionToken, SERVICE_ROLE_KEY);
  if (!actor) {
    return json({
      ok: false,
      error: "unauthorized",
      message: "Sessão administrativa expirada. Informe o PIN do GADM novamente."
    }, 401);
  }

  const transcript = text(body.transcript);
  if (!transcript || transcript.length > 180) {
    return json({
      ok: false,
      error: "invalid_transcript",
      message: "Fale um comando curto, como pausar maçã ou fechar a loja."
    }, 400);
  }

  const command = parseCommand(transcript);
  if (!command) {
    return json({
      ok: false,
      error: "unknown_command",
      message: "Não entendi. Diga abrir loja, fechar loja, pausar um item ou reativar um item."
    }, 422);
  }

  const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false }
  });

  try {
    if (command.kind === "store") {
      return json(await setStoreOpen(admin, command.open, actor));
    }
    return await setItemAvailable(admin, command.target, command.available);
  } catch (error) {
    console.error("[gestor-voice]", error);
    return json({
      ok: false,
      error: "voice_command_failed",
      message: error instanceof Error ? error.message : String(error)
    }, 500);
  }
});
