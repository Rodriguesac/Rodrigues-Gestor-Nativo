import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.56.1";

// Nova rota ADITIVA: não altera o cadastrador legado usado pelo Gestor atual.
// Somente sessões reais da gestor-app-api podem registrar tokens FCM.
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  }
});
const text = (value: unknown): string => String(value ?? "").trim();
const VALID_SESSION = /^[A-Za-z0-9_-]{40,80}$/;
const VALID_TOKEN = /^[A-Za-z0-9:._-]{40,4096}$/;

async function sha256(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ ok: false, error: "method_not_allowed" }, 405);
  const length = Number(req.headers.get("content-length") || 0);
  if (Number.isFinite(length) && length > 8192)
    return json({ ok: false, error: "payload_too_large" }, 413);

  let body: Record<string, unknown>;
  try {
    const parsed = await req.json();
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object")
      return json({ ok: false, error: "invalid_json" }, 400);
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ ok: false, error: "invalid_json" }, 400);
  }

  const token = text(body.token);
  const session = text(body.session_token);
  const pkg = text(body.package);
  if (pkg !== "com.rodrigues.gestor")
    return json({ ok: false, error: "invalid_app" }, 403);
  if (!VALID_TOKEN.test(token))
    return json({ ok: false, error: "invalid_token" }, 400);
  if (!VALID_SESSION.test(session))
    return json({ ok: false, error: "session_required" }, 401);

  const url = Deno.env.get("SUPABASE_URL") || "";
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  if (!url || !serviceRole)
    return json({ ok: false, error: "server_not_configured" }, 503);

  const admin = createClient(url, serviceRole, {
    auth: { persistSession: false, autoRefreshToken: false }
  });
  try {
    const sessionHash = await sha256(session);
    const record = await admin.from("app_documents").select("data")
      .eq("collection_name", "security_gestor_app_sessions")
      .eq("document_id", sessionHash).maybeSingle();
    if (record.error) throw record.error;
    const savedSession = record.data?.data;
    const expiresAt = Date.parse(text(savedSession?.expiresAt));
    const operator = savedSession?.operator;
    if (!savedSession?.active || !Number.isFinite(expiresAt) || expiresAt <= Date.now() ||
        !operator || !["owner", "atendente", "montador"].includes(text(operator.role)))
      return json({ ok: false, error: "session_expired" }, 401);

    // A mesma regra da gestor-app-api: conta de funcionário deve continuar
    // ativa e com a mesma versão de permissões da sessão.
    if (text(operator.id) !== "owner") {
      const staff = await admin.from("app_documents").select("data")
        .eq("collection_name", "security_gestor_app_staff")
        .eq("document_id", text(operator.id)).maybeSingle();
      if (staff.error) throw staff.error;
      const account = staff.data?.data;
      if (!account?.active || account.version !== operator.version ||
          text(account.role) !== text(operator.role))
        return json({ ok: false, error: "session_expired" }, 401);
    }

    const now = new Date().toISOString();
    const upsert = await admin.from("fcm_devices").upsert({
      owner_type: "gestor",
      owner_id: null,
      app: "RODRIGUES_GESTOR",
      platform: "android",
      token,
      active: true,
      metadata: {
        package: pkg,
        model: text(body.model).slice(0, 160),
        sdk: Number(body.sdk) || null,
        app_version: text(body.app_version).slice(0, 80),
        registration_method: "authenticated_session_v2",
        operator_role: text(operator.role)
      },
      last_seen_at: now,
      updated_at: now
    }, { onConflict: "token" });
    if (upsert.error) throw upsert.error;
    return json({ ok: true, registered: true });
  } catch (err) {
    // Não vazar SQL, documento de sessão ou dados de cliente ao aparelho.
    console.error("[gestor-device-register-v2] falha interna", err);
    return json({ ok: false, error: "registration_failed" }, 503);
  }
});
