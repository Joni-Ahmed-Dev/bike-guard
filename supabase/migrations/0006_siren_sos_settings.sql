-- BikeGuard v1.1.4: siren + SOS-SMS settings sync to the tracker phone.
-- RUN THIS in Supabase > SQL Editor BEFORE shipping v1.1.4: without these columns the
-- Remote phone's settings upsert fails (unknown column) and no settings change applies.
alter table public.device_settings
  add column if not exists siren_on_alert boolean not null default false,
  add column if not exists sos_sms boolean not null default false;
