-- v1.2.3: stable tracker identity across reinstalls.
-- The tracker id is a random UUID stored in the phone's EncryptedSharedPreferences,
-- so an uninstall or app-data clear wipes it and the next launch registers a second
-- device row (that is how the same ITEL phone ended up with four rows). From v1.2.3
-- the phone stamps its hardware key (Settings.Secure.ANDROID_ID, stable across
-- reinstalls of the same app on the same phone) on its device row, and after a
-- reinstall it looks the row up by that key and rejoins it instead of minting a
-- new id. Apply in the Supabase Dashboard SQL editor (or `supabase db push`),
-- like 0007.
alter table public.devices
  add column if not exists hardware_key text;

-- One phone = one row per owner. Partial index: the old rows (and any row whose
-- key was never stamped) keep NULL and never collide with each other.
create unique index if not exists devices_owner_hardware_key_idx
  on public.devices(owner_id, hardware_key) where hardware_key is not null;

-- No RLS change: the existing own-row policies already cover the new column.
-- No backfill is possible server-side: the key lives on the phone, so the live
-- row gets it from the app's first v1.2.3 flush (SyncManager stamps it once).
-- The three dead rows keep NULL and are harmless.
