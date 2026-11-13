-- BikeGuard Phase 3 + 4: events, remote commands, geofences, push tokens, share links.

-- ---------- device state mirrored from the tracker ----------
alter table public.devices
  add column if not exists armed             boolean not null default false,
  add column if not exists tracking_expected boolean not null default false,
  add column if not exists odometer_km       real    not null default 0,
  add column if not exists next_service_km   real,
  add column if not exists speed_limit_kmh   int     not null default 0;

-- ---------- events (alerts) ----------
create table if not exists public.events (
  id         bigint generated always as identity primary key,
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid not null references public.devices(id) on delete cascade,
  type       text not null,
  severity   text not null default 'info' check (severity in ('info','warning','critical')),
  lat        double precision,
  lng        double precision,
  data       jsonb,
  created_at timestamptz not null default now(),
  constraint events_dedupe unique (device_id, type, created_at)   -- offline re-uploads are idempotent
);
create index if not exists events_owner_time_idx on public.events(owner_id, created_at desc);

-- ---------- remote commands ----------
create table if not exists public.commands (
  id         bigint generated always as identity primary key,
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid not null references public.devices(id) on delete cascade,
  type       text not null check (type in
             ('locate','live','siren_on','siren_off','arm','disarm','set_speed_limit','cancel_sos','mark_serviced')),
  arg        text,
  status     text not null default 'pending' check (status in ('pending','done','expired')),
  created_at timestamptz not null default now(),
  done_at    timestamptz
);
create index if not exists commands_pending_idx on public.commands(device_id, status);

-- ---------- geofences ----------
create table if not exists public.geofences (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid references public.devices(id) on delete cascade,   -- null = all devices
  name       text not null,
  lat        double precision not null,
  lng        double precision not null,
  radius_m   int not null check (radius_m between 50 and 20000),
  active     boolean not null default true,
  created_at timestamptz not null default now()
);
create table if not exists public.geofence_state (
  geofence_id uuid not null references public.geofences(id) on delete cascade,
  device_id   uuid not null references public.devices(id) on delete cascade,
  inside      boolean not null,
  primary key (geofence_id, device_id)
);

-- ---------- push tokens (FCM) ----------
create table if not exists public.push_tokens (
  token      text primary key,
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid not null,   -- the phone that owns this token (its BikeGuard device id)
  updated_at timestamptz not null default now()
);

-- ---------- temporary share links ----------
create table if not exists public.share_links (
  token      text primary key,
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid not null references public.devices(id) on delete cascade,
  expires_at timestamptz not null,
  revoked    boolean not null default false,
  created_at timestamptz not null default now()
);

-- ---------- Row Level Security ----------
alter table public.events          enable row level security;
alter table public.commands        enable row level security;
alter table public.geofences       enable row level security;
alter table public.geofence_state  enable row level security;
alter table public.push_tokens     enable row level security;
alter table public.share_links     enable row level security;

create policy "events_select_own" on public.events for select using (owner_id = auth.uid());
create policy "events_insert_own" on public.events for insert with check (
  owner_id = auth.uid()
  and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));
create policy "events_delete_own" on public.events for delete using (owner_id = auth.uid());

create policy "commands_select_own" on public.commands for select using (owner_id = auth.uid());
create policy "commands_insert_own" on public.commands for insert with check (
  owner_id = auth.uid()
  and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));
create policy "commands_update_own" on public.commands for update
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

create policy "geofences_all_own" on public.geofences for all
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "push_tokens_all_own" on public.push_tokens for all
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "share_links_all_own" on public.share_links for all
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid()
    and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));
-- geofence_state has no policy on purpose: only the trigger below (security definer) touches it.

-- ---------- geofence enter/exit detection (runs on every new location) ----------
create or replace function public.geofence_check()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  g record; is_in boolean; prev boolean; dist double precision;
begin
  for g in
    select * from public.geofences
     where owner_id = new.owner_id and active and (device_id is null or device_id = new.device_id)
  loop
    dist := 2 * 6371000 * asin(least(1, sqrt(
      power(sin(radians(new.lat - g.lat) / 2), 2) +
      cos(radians(g.lat)) * cos(radians(new.lat)) * power(sin(radians(new.lng - g.lng) / 2), 2))));
    is_in := dist <= g.radius_m;

    select s.inside into prev from public.geofence_state s
     where s.geofence_id = g.id and s.device_id = new.device_id;

    if not found then
      insert into public.geofence_state(geofence_id, device_id, inside) values (g.id, new.device_id, is_in);
    elsif prev is distinct from is_in then
      update public.geofence_state s set inside = is_in
       where s.geofence_id = g.id and s.device_id = new.device_id;
      -- ignore old points that arrive late from an offline queue
      if new.recorded_at > now() - interval '10 minutes' then
        insert into public.events(owner_id, device_id, type, severity, lat, lng, data, created_at)
        values (new.owner_id, new.device_id,
                case when is_in then 'geofence_enter' else 'geofence_exit' end,
                'warning', new.lat, new.lng, jsonb_build_object('name', g.name), new.recorded_at)
        on conflict do nothing;
      end if;
    end if;
  end loop;
  return new;
end $$;

drop trigger if exists trg_geofence on public.locations;
create trigger trg_geofence after insert on public.locations
  for each row execute function public.geofence_check();

-- ---------- "tracker went silent" detection (call from pg_cron) ----------
create or replace function public.check_stale_devices()
returns void language sql security definer set search_path = '' as $$
  insert into public.events(owner_id, device_id, type, severity, data)
  select d.owner_id, d.id, 'device_stale', 'warning', jsonb_build_object('last_seen', d.last_seen)
    from public.devices d
   where d.tracking_expected
     and d.last_seen < now() - interval '15 minutes'
     and not exists (select 1 from public.events e
                      where e.device_id = d.id and e.type = 'device_stale' and e.created_at > d.last_seen);
$$;
revoke all on function public.check_stale_devices() from public, anon, authenticated;
-- Enable the pg_cron extension (Database > Extensions), then run:
-- select cron.schedule('bikeguard-stale', '*/5 * * * *', 'select public.check_stale_devices()');

-- ---------- public read of ONE shared bike via an unguessable token ----------
create or replace function public.shared_location(p_token text)
returns table (lat double precision, lng double precision, speed real, bearing real, battery smallint,
               recorded_at timestamptz, device_name text, expires_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select l.lat, l.lng, l.speed, l.bearing, l.battery, l.recorded_at, d.name, s.expires_at
    from public.share_links s
    join public.devices d on d.id = s.device_id
    join lateral (select * from public.locations x where x.device_id = s.device_id
                   order by x.recorded_at desc limit 1) l on true
   where s.token = p_token and not s.revoked and s.expires_at > now();
$$;

create or replace function public.shared_trail(p_token text)
returns table (lat double precision, lng double precision, recorded_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select t.lat, t.lng, t.recorded_at from (
    select l.lat, l.lng, l.recorded_at
      from public.share_links s
      join public.locations l on l.device_id = s.device_id
     where s.token = p_token and not s.revoked and s.expires_at > now()
       and l.recorded_at > now() - interval '90 minutes'
     order by l.recorded_at desc limit 120) t
  order by t.recorded_at asc;
$$;
revoke all on function public.shared_location(text), public.shared_trail(text) from public;
grant execute on function public.shared_location(text), public.shared_trail(text) to anon, authenticated;

-- ---------- Realtime ----------
alter publication supabase_realtime add table public.events;
alter publication supabase_realtime add table public.devices;
