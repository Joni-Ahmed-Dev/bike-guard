-- v1.2.19 review item #21: the Remote phone can now trigger SOS, so the
-- commands CHECK constraint must allow the new 'sos' type (same pattern as
-- 0007, which added tracking_start / tracking_stop).
-- Apply in the Supabase Dashboard SQL editor (or `supabase db push`).
alter table public.commands drop constraint if exists commands_type_check;
alter table public.commands add constraint commands_type_check check (type in
  ('locate','live','siren_on','siren_off','arm','disarm','set_speed_limit','cancel_sos','mark_serviced',
   'tracking_start','tracking_stop','sos'));
