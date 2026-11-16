-- v1.2.40 added the master_on / master_off command types, but the commands
-- CHECK constraint never allowed them, so every master command insert was
-- rejected with a 400 and the Remote showed "Couldn't Send". Same pattern as
-- 0007 (tracking_start / tracking_stop) and 0015 (sos).
-- Apply in the Supabase Dashboard SQL editor (or `supabase db push`).
alter table public.commands drop constraint if exists commands_type_check;
alter table public.commands add constraint commands_type_check check (type in
  ('locate','live','siren_on','siren_off','arm','disarm','set_speed_limit','cancel_sos','mark_serviced',
   'tracking_start','tracking_stop','sos','master_on','master_off'));
