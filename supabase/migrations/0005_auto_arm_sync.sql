-- BikeGuard v1.1.2: auto-guard switch synced to the server.
-- The Remote phone can turn auto-guard on/off; the tracker phone polls it and applies it.
-- Existing rows get auto_arm = false, matching the tracker's local default.

alter table public.device_settings
  add column if not exists auto_arm boolean not null default false;
