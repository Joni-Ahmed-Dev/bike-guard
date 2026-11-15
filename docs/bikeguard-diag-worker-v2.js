/* BikeGuard diagnostics Worker v2 — v1.2.21, review item #23.
 *
 * NOT DEPLOYED by the repo push. This file is the new Worker code for the
 * parent to deploy (Cloudflare dashboard / wrangler) and rotate secrets
 * for. See docs/diag-token-split.md for the exact order.
 *
 * What changed vs v1 (~/workspace/bikeguard-diag/worker.js):
 *
 * 1. SPLIT TOKENS. v1 used one READ_TOKEN for everything, and the same
 *    value is baked into the public-repo APK as DIAG_TOKEN — anyone who
 *    extracts it can read every device's latest snapshot. v2 splits it:
 *      POST /report  -> WRITE_TOKEN (the value the APK carries)
 *      GET  /latest  -> READ_TOKEN (never leaves the owner's hands)
 *    Until WRITE_TOKEN is set, POST still accepts READ_TOKEN, so the
 *    Worker can be deployed BEFORE the GitHub secret is rotated without
 *    breaking Send Report. After rotation the APK holds only the write
 *    token: extracting it lets an attacker file fake reports, but no
 *    longer read anyone's snapshots.
 *
 * 2. KV INDEX RACE + NEVER-PRUNED INDEX, fixed by deleting the index.
 *    v1 kept a "diag:devices" list with a read-modify-write: two reports
 *    arriving at once could lose a device from the list, and the list was
 *    never pruned when a snapshot expired. v2 lists keys with prefix
 *    "diag:" instead, so there is no list to race over or prune. The old
 *    "diag:devices" key, if present, is ignored (delete it by hand).
 *
 * 3. RATE LIMIT. One report per device per 10 seconds (KV key "rl:<id>",
 *    10 s TTL); faster repeats get 429. The app reports at most every
 *    ~30 s and on manual Send Report taps, so a real phone never hits it;
 *    a looped-up client cannot flood the KV.
 *
 * Snapshots still expire after 7 days, and still carry no location.
 */

function unauthorized() {
  return new Response("forbidden", { status: 403 });
}

function hasWriteToken(token, env) {
  // Rotation fallback: until WRITE_TOKEN exists, the old single token
  // still writes. Once it is set, only it writes.
  if (env.WRITE_TOKEN) return token === env.WRITE_TOKEN;
  return token === env.READ_TOKEN;
}

function hasReadToken(token, env) {
  return token === env.READ_TOKEN;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const token = url.searchParams.get("token");

    if (url.pathname === "/report" && request.method === "POST") {
      if (!hasWriteToken(token, env)) return unauthorized();
      let body;
      try {
        body = await request.json();
      } catch {
        return new Response("bad json", { status: 400 });
      }
      if (typeof body.device_id !== "string" || body.device_id.length < 8) {
        return new Response("bad device_id", { status: 400 });
      }
      // One report per device per 10 s; the app reports far less often.
      if (await env.DIAG_KV.get("rl:" + body.device_id)) {
        return new Response("too fast", { status: 429 });
      }
      const snap = {
        device_id: body.device_id,
        role: typeof body.role === "string" ? body.role : "",
        version: typeof body.version === "string" ? body.version : "",
        tracking: body.tracking === true,
        armed: body.armed === true,
        queued: typeof body.queued === "number" ? body.queued : 0,
        last_fix_age_s: typeof body.last_fix_age_s === "number" ? body.last_fix_age_s : -1,
        last_sync_error: typeof body.last_sync_error === "string" ? body.last_sync_error : "",
        last_sync_at: typeof body.last_sync_at === "string" ? body.last_sync_at : "",
        battery: typeof body.battery === "number" ? body.battery : -1,
        note: typeof body.note === "string" ? body.note.slice(0, 200) : "",
        at: new Date().toISOString(),
      };
      await env.DIAG_KV.put("diag:" + snap.device_id, JSON.stringify(snap), {
        expirationTtl: 7 * 24 * 3600,
      });
      await env.DIAG_KV.put("rl:" + snap.device_id, "1", { expirationTtl: 10 });
      return Response.json({ ok: true });
    }

    if (url.pathname === "/latest" && request.method === "GET") {
      if (!hasReadToken(token, env)) return unauthorized();
      const only = url.searchParams.get("device");
      // No index anymore: list the snapshot keys directly. The stale v1
      // "diag:devices" key, if still present, is skipped below.
      const listed = await env.DIAG_KV.list({ prefix: "diag:" });
      const out = [];
      for (const key of listed.keys) {
        if (key.name === "diag:devices") continue;
        const id = key.name.slice("diag:".length);
        if (only && id !== only) continue;
        const raw = await env.DIAG_KV.get(key.name);
        if (!raw) continue;
        try {
          const snap = JSON.parse(raw);
          if (snap && typeof snap.device_id === "string") out.push(snap);
        } catch {
          // skip a corrupt entry
        }
      }
      return Response.json({ devices: out });
    }

    return new Response("not found", { status: 404 });
  },
};
