# Backup — v1.2.21, review item #23

## The risk

The Supabase free plan has **no automatic backups and no point-in-time
recovery** (those are Pro features). If a migration drops a column, a
bad `DELETE` runs, or the project is deleted, the data is gone. The
keep-alive workflow (`docs/supabase-keep-alive.yml`) prevents the
7-day pause — it backs up nothing.

## What survives without a data backup

The repo holds the full schema: `supabase/migrations/0001`–`0015`
rebuild every table, function, policy and cron job from scratch. What
does NOT survive is the data itself: devices, locations, events,
commands, settings, share links, expenses.

## Manual export (weekly is enough for one bike)

From any machine with the PostgreSQL client installed, using the
connection string from the Supabase dashboard (Project Settings →
Database → Connection string, URI format):

```sh
pg_dump "postgresql://postgres:<DB_PASSWORD>@db.<PROJECT_REF>.supabase.co:5432/postgres" \
  -F c -f bikeguard-$(date +%F).dump
```

Keep the dump file somewhere off the phone (laptop / Drive). To
restore into a fresh project:

```sh
pg_restore -d "postgresql://postgres:<DB_PASSWORD>@db.<NEW_REF>.supabase.co:5432/postgres" \
  --clean --if-exists bikeguard-<DATE>.dump
```

Notes:

- The dashboard password is the database password set at project
  creation, not the Supabase login password.
- `pg_dump` with `-F c` (custom format) is preferred: it restores
  cleanly with `pg_restore`. A plain SQL dump (`-F p`) also works.
- The `auth.users` rows (logins) are NOT included in a plain `pg_dump`
  of the `public` schema — they live in the `auth` schema, which
  `pg_dump` skips unless asked (`-n auth` adds it, but restoring auth
  users into another project needs care). After a restore the owner
  signs in with the same email; if the auth row is missing, sign up
  again with the same email and re-point the devices to the new id.
- The two Cloudflare Workers (`bikeguard-diag`, `bikeguard-alerts`)
  hold no data worth backing up (the diag KV expires in 7 days; the
  alerts KV is a 120-second dedupe cache). Their source lives in
  `~/workspace/bikeguard-diag/` and `~/workspace/bikeguard-alerts/`
  (not in this repo — see the brief).

## What would make this automatic

A scheduled GitHub Actions job that runs `pg_dump` and uploads the
dump as an artifact (or to storage) — needs the database password as a
repo secret and a place to store the dump. That is a follow-up, not
part of this item.
