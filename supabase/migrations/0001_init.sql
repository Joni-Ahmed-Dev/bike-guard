-- BikeGuard Phase 1 schema. Run in Supabase > SQL Editor (or `supabase db push`).

create table if not exists public.devices (
  id          uuid primary key,
  owner_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name        text not null,
  last_seen   timestamptz,
  created_at  timestamptz not null default now()
);
create index if not exists devices_owner_idx on public.devices(owner_id);

create table if not exists public.locations (
  id           bigint generated always as identity primary key,
  device_id    uuid not null references public.devices(id) on delete cascade,
  owner_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  lat          double precision not null check (lat between -90 and 90),
  lng          double precision not null check (lng between -180 and 180),
  speed        real,
  bearing      real,
  accuracy     real,
  altitude     double precision,
  battery      smallint,
  is_charging  boolean,
  recorded_at  timestamptz not null,
  created_at   timestamptz not null default now(),
  -- makes offline re-uploads idempotent
  constraint locations_device_time_uniq unique (device_id, recorded_at)
);
create index if not exists locations_device_time_idx
  on public.locations(device_id, recorded_at desc);

-- keep devices.last_seen fresh
create or replace function public.touch_device_last_seen()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.devices
     set last_seen = greatest(coalesce(last_seen, new.recorded_at), new.recorded_at)
   where id = new.device_id;
  return new;
end $$;

drop trigger if exists trg_touch_device on public.locations;
create trigger trg_touch_device after insert on public.locations
  for each row execute function public.touch_device_last_seen();

-- Row Level Security: every user sees only their own data
alter table public.devices   enable row level security;
alter table public.locations enable row level security;

create policy "devices_select_own" on public.devices
  for select using (owner_id = auth.uid());
create policy "devices_insert_own" on public.devices
  for insert with check (owner_id = auth.uid());
create policy "devices_update_own" on public.devices
  for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "devices_delete_own" on public.devices
  for delete using (owner_id = auth.uid());

create policy "locations_select_own" on public.locations
  for select using (owner_id = auth.uid());
create policy "locations_insert_own" on public.locations
  for insert with check (
    owner_id = auth.uid()
    and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "locations_delete_own" on public.locations
  for delete using (owner_id = auth.uid());

-- Realtime (used in a later phase instead of polling)
alter publication supabase_realtime add table public.locations;

-- Optional: delete points older than 90 days (enable pg_cron first)
-- select cron.schedule('bikeguard-retention','0 3 * * *',
--   $$delete from public.locations where recorded_at < now() - interval '90 days'$$);
