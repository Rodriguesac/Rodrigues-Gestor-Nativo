import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.56.1";

const json = (data: Record<string, unknown>, status = 200) => new Response(
  JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  }
);
async function digest(raw: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(raw));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}
function hasSignature(bytes: Uint8Array, type: string) {
  if (type === "image/png")
    return bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((v,i)=>bytes[i]===v);
  if (type === "image/jpeg")
    return bytes.length >= 4 && bytes[0]===255 && bytes[1]===216 && bytes[2]===255;
  if (type === "image/webp")
    return bytes.length >= 12 && String.fromCharCode(...bytes.slice(0,4))==="RIFF"
      && String.fromCharCode(...bytes.slice(8,12))==="WEBP";
  return false;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  const session = req.headers.get("x-gestor-session")?.trim() || "";
  if (!/^[A-Za-z0-9_-]{40,80}$/.test(session))
    return json({ ok: false, error: "session_required" }, 401);
  const type = (req.headers.get("content-type") || "").toLowerCase().split(";")[0].trim();
  const extension = ({ "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" }
    as Record<string,string>)[type];
  if (!extension) return json({ ok: false, error: "unsupported_media_type" }, 415);
  const length = Number(req.headers.get("content-length") || "0");
  if (length > 5*1024*1024) return json({ ok: false, error: "image_too_large" }, 413);

  const url = Deno.env.get("SUPABASE_URL");
  const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !secret) return json({ ok: false, error: "server_not_configured" }, 503);
  const admin = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } });
  try {
    const hash = await digest(session);
    const { data: record, error: sessionError } = await admin.from("app_documents")
      .select("data").eq("collection_name", "security_gestor_app_sessions")
      .eq("document_id", hash).maybeSingle();
    if (sessionError) throw sessionError;
    const account = record?.data;
    const expiry = Date.parse(String(account?.expiresAt || ""));
    if (!account?.active || !Number.isFinite(expiry) || expiry <= Date.now()
        || account.operator?.id !== "owner" || account.operator?.role !== "owner")
      return json({ ok: false, error: "owner_session_required" }, 403);

    const raw = await req.arrayBuffer();
    if (raw.byteLength === 0 || raw.byteLength > 5*1024*1024)
      return json({ ok: false, error: "invalid_image_size" }, 413);
    const bytes = new Uint8Array(raw);
    if (!hasSignature(bytes, type))
      return json({ ok: false, error: "invalid_image_content" }, 415);

    const name = "gestor20/catalog/" + crypto.randomUUID() + "." + extension;
    const saved = await admin.storage.from("rac-media-v1")
      .upload(name, bytes, { upsert: false, contentType: type, cacheControl: "3600" });
    if (saved.error) throw saved.error;
    const { data: publicData } = admin.storage.from("rac-media-v1").getPublicUrl(name);
    const imageUrl = publicData.publicUrl;
    if (!imageUrl.startsWith("https://"))
      return json({ ok: false, error: "invalid_public_url" }, 503);
    return json({ ok: true, image_url: imageUrl, path: name });
  } catch (error) {
    console.error("[gestor-media-upload] upload failed:", error);
    return json({ ok: false, error: "upload_failed" }, 503);
  }
});
