# Diag token split — rotation procedure (v1.2.21, review item #23)

## The problem

The diagnostics Worker (`bikeguard-diag`) used one secret, `READ_TOKEN`,
for both writing reports (`POST /report`) and reading them (`GET /latest`).
The same value is baked into the APK as `DIAG_TOKEN` (GitHub secret), and
the repo is public — anyone who extracts it from the APK can read every
device's latest snapshot.

## The fix

`docs/bikeguard-diag-worker-v2.js` splits the token:

- `POST /report` accepts `WRITE_TOKEN` (the value the APK carries).
- `GET /latest` accepts only `READ_TOKEN` (never in the APK).

After rotation the APK holds a write-only token: extracting it lets an
attacker file fake reports, but no longer read snapshots.

## Rotation order (do not skip steps)

1. Deploy `docs/bikeguard-diag-worker-v2.js` to the `bikeguard-diag`
   Worker (Cloudflare dashboard or wrangler). The v2 code still accepts
   `READ_TOKEN` for writes until `WRITE_TOKEN` exists, so Send Report
   keeps working during the rotation.
2. In the Cloudflare dashboard, add a new Worker secret `WRITE_TOKEN`
   (generate a fresh value; never reuse the old one).
3. In GitHub (repo Settings → Secrets → Actions), set the `DIAG_TOKEN`
   secret to the same `WRITE_TOKEN` value. The next CI build bakes the
   write-only token into the APK.
4. Optional, after a green build with the new token: rotate `READ_TOKEN`
   to a fresh value in the Cloudflare dashboard, and update the local
   copy used to read `/latest`. (The app never reads `/latest`, so this
   touches nothing else.)
5. Optional cleanup: delete the stale `diag:devices` KV key in the
   Cloudflare dashboard (v2 ignores it, but it is dead weight).

## What v2 also fixes

- **KV index race + never-pruned index:** v1 kept a `diag:devices` list
  with a read-modify-write that could lose a device under concurrent
  reports, and never pruned it. v2 lists `diag:` keys directly.
- **No rate limit:** v2 allows one report per device per 10 seconds
  (429 beyond that). The app reports far less often, so a real phone
  never hits it.

## Not fixed (follow-up)

- A holder of the write token can still file fake reports for any device
  id. Fully stopping that needs per-device credentials, which is a
  bigger change than this item.
- The old `READ_TOKEN` value is still valid for writes until step 2 is
  done; the split only bites once `WRITE_TOKEN` is set.
