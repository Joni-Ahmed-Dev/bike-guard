-- BikeGuard v1.1.0: two roles, server-synced settings, family invites, expenses, parking spots.
-- The Remote phone edits device_settings; the tracker phone polls it and applies it.
-- Same RLS style as 0003: every user sees only their own rows.

-- ---------- server-synced settings (one row per tracker device) ----------
create table if not exists public.device_settings (
  device_id            uuid primary key references public.devices(id) on delete cascade,
  owner_id             uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mode                 text not null default 'manual' check (mode in ('manual','auto','always')),
  interval_sec         int  not null default 5 check (interval_sec between 3 and 60),
  speed_limit_kmh      int  not null default 0 check (speed_limit_kmh >= 0),
  guard_armed          boolean not null default false,
  crash_enabled        boolean not null default true,
  sos_number           text not null default '',
  service_km           int  not null default 0 check (service_km >= 0),
  guard_schedule_start int  check (guard_schedule_start is null or guard_schedule_start between 0 and 23),
  guard_schedule_end   int  check (guard_schedule_end is null or guard_schedule_end between 0 and 23),
  updated_at           timestamptz not null default now(),
  applied_at           timestamptz
);

-- ---------- family view-only invites ----------
create table if not exists public.family_invites (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  code       text not null unique,
  role       text not null default 'viewer' check (role = 'viewer'),
  used_by    uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

-- ---------- expense log (the app also keeps a local Room copy) ----------
create table if not exists public.expenses (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid references public.devices(id) on delete cascade,
  category   text not null check (category in ('fuel','service','repair','other')),
  amount     numeric(12, 2) not null check (amount >= 0),
  note       text,
  spent_on   date not null default current_date,
  created_at timestamptz not null default now()
);
create index if not exists expenses_owner_date_idx on public.expenses(owner_id, spent_on desc);

-- ---------- parked spots ("where did I park") ----------
create table if not exists public.parking_spots (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  uuid not null references public.devices(id) on delete cascade,
  lat        double precision not null check (lat between -90 and 90),
  lng        double precision not null check (lng between -180 and 180),
  note       text,
  created_at timestamptz not null default now()
);
create index if not exists parking_spots_device_time_idx on public.parking_spots(device_id, created_at desc);

-- ---------- Row Level Security (same pattern as 0003) ----------
alter table public.device_settings enable row level security;
alter table public.family_invites  enable row level security;
alter table public.expenses        enable row level security;
alter table public.parking_spots   enable row level security;

create policy "device_settings_all_own" on public.device_settings for all
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid()
    and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

create policy "family_invites_all_own" on public.family_invites for all
  using (owner_id = auth.uid()) with check (owner_id = auth.uid());

create policy "expenses_all_own" on public.expenses for all
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid()
    and (device_id is null or exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())));

create policy "parking_spots_all_own" on public.parking_spots for all
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid()
    and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

-- ---------- browser dashboard: last alerts for one shared bike ----------
create or replace function public.shared_alerts(p_token text)
returns table (type text, severity text, created_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select e.type, e.severity, e.created_at
    from public.share_links s
    join public.events e on e.device_id = s.device_id
   where s.token = p_token and not s.revoked and s.expires_at > now()
   order by e.created_at desc limit 10;
$$;
revoke all on function public.shared_alerts(text) from public;
grant execute on function public.shared_alerts(text) to anon, authenticated;

-- ---------- Realtime ----------
alter publication supabase_realtime add table public.device_settings;
