// Supabase Edge Function: turns new rows in `events` and `commands` into FCM data messages.
// Hook it up in Dashboard > Database > Webhooks (INSERT on public.events and public.commands -> this function).
// Secret needed:  supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT")!);

const enc = new TextEncoder();
const b64url = (data: Uint8Array | string) => {
  const bytes = typeof data === "string" ? enc.encode(data) : data;
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

let cached: { token: string; exp: number } | null = null;
async function accessToken(): Promise<string> {
  if (cached && cached.exp > Date.now() + 60_000) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claim}`)));
  const jwt = `${header}.${claim}.${b64url(sig)}`;
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  const j = await r.json();
  cached = { token: j.access_token, exp: Date.now() + (j.expires_in ?? 3600) * 1000 };
  return cached.token;
}

async function send(token: string, data: Record<string, string>) {
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl: "300s" } } }),
  });
  if (r.status === 404 || r.status === 410) await sb.from("push_tokens").delete().eq("token", token); // dead token
}

Deno.serve(async (req) => {
  const p = await req.json();
  if (p.type !== "INSERT") return new Response("ignored");
  const rec = p.record;

  if (p.table === "events") {
    const { data: dev } = await sb.from("devices").select("name").eq("id", rec.device_id).single();
    const { data: tokens } = await sb.from("push_tokens").select("token, device_id").eq("owner_id", rec.owner_id);
    const payload = {
      kind: "event", type: rec.type, severity: rec.severity, device_id: rec.device_id,
      device_name: dev?.name ?? "", lat: String(rec.lat ?? ""), lng: String(rec.lng ?? ""),
      data: JSON.stringify(rec.data ?? {}),
    };
    // Don't alert the tracker phone about itself.
    await Promise.all((tokens ?? []).filter((t) => t.device_id !== rec.device_id).map((t) => send(t.token, payload)));
  } else if (p.table === "commands") {
    const { data: tokens } = await sb.from("push_tokens").select("token")
      .eq("owner_id", rec.owner_id).eq("device_id", rec.device_id);
    await Promise.all((tokens ?? []).map((t) => send(t.token, { kind: "command", type: rec.type })));
  }
  return new Response("ok");
});
