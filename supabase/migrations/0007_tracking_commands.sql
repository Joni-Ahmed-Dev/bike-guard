-- v1.2.1 issue 1: the Remote tracking switch sends tracking_start / tracking_stop,
-- but the commands CHECK constraint from 0003 never allowed those types, so every
-- insert was rejected with a 400 and the switch could never work, online or offline.
-- The app code (TrackingService.execute, ViewerViewModel.send) already handles both
-- types; only the server gate was missing them.
-- Apply in the Supabase Dashboard SQL editor (or `supabase db push`).
alter table public.commands drop constraint if exists commands_type_check;
alter table public.commands add constraint commands_type_check check (type in
  ('locate','live','siren_on','siren_off','arm','disarm','set_speed_limit','cancel_sos','mark_serviced',
   'tracking_start','tracking_stop'));
