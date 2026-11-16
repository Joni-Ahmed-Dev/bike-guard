-- v1.2.42: the tracker publishes when the bike became stationary (wall-clock
-- ms of the last movement), so the Remote Dashboard can show the countdown to
-- the guard arming itself. 0 = the bike is moving (not parked).
-- Apply in the Supabase Dashboard SQL editor (or `supabase db push`).
alter table public.devices
  add column if not exists parked_since_ms bigint not null default 0;
