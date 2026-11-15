-- BikeGuard SQL tests: geofence_check (trigger) + device_trips (v1.2.21, review item #23).
--
-- Run: psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/sql_tests.sql
-- or paste into the Supabase SQL Editor. Everything runs inside ONE
-- transaction and ROLLS BACK at the end, so no test data ever persists.
-- Every check is a DO block that RAISES on mismatch; the run fails loudly
-- on the first wrong expectation instead of silently passing.
--
-- CI runs this against a vanilla postgres:16 service (see the sql-test job
-- in docs/ci-test-step.diff) after ci-setup.sql + migrations
-- 0001, 0002, 0003, 0011, 0012. The live DB needs nothing new: the
-- functions under test are already applied there (0011, 0012).

begin;

-- Fixed ids, so the assertions can name them. The owner must exist in
-- auth.users (live DB: it is the owner's real id; CI: ci-setup.sql inserts it).
-- NOTE: replace the owner id below with the owner's real auth id when running
-- by hand in the SQL Editor; the CI stub uid() returns this same id.
insert into public.devices (id, owner_id, name)
values ('11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 'SQL test bike');
insert into public.devices (id, owner_id, name)
values ('33333333-3333-3333-3333-333333333333',
        '22222222-2222-2222-2222-222222222222', 'SQL test trips bike');

-- ================= geofence_check =================
-- Geofence centred at (23.8000, 90.4000), radius 500 m, for the first device.
insert into public.geofences (id, owner_id, device_id, name, lat, lng, radius_m)
values ('44444444-4444-4444-4444-444444444444',
        '22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111', 'Test home', 23.8000, 90.4000, 500);

-- 1. First point OUTSIDE (23.8100 is ~1.1 km north), fresh: baseline only,
--    state inside=false, no event.
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
values ('11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 23.8100, 90.4000, 5.0, now() - interval '3 minutes');

do $$
declare v_inside boolean; v_events int;
begin
  select inside into v_inside from public.geofence_state
   where geofence_id = '44444444-4444-4444-4444-444444444444'
     and device_id = '11111111-1111-1111-1111-111111111111';
  if v_inside is distinct from false then
    raise exception 'geofence baseline: expected inside=false, got %', v_inside;
  end if;
  select count(*) into v_events from public.events
   where device_id = '11111111-1111-1111-1111-111111111111';
  if v_events <> 0 then
    raise exception 'geofence baseline: expected 0 events, got %', v_events;
  end if;
end $$;

-- 2. Fresh point INSIDE (~111 m north): transition, state flips, one enter event.
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
values ('11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 23.8010, 90.4000, 5.0, now() - interval '2 minutes');

do $$
declare v_inside boolean; v_enter int;
begin
  select inside into v_inside from public.geofence_state
   where geofence_id = '44444444-4444-4444-4444-444444444444'
     and device_id = '11111111-1111-1111-1111-111111111111';
  if v_inside is distinct from true then
    raise exception 'geofence enter: expected inside=true, got %', v_inside;
  end if;
  select count(*) into v_enter from public.events
   where device_id = '11111111-1111-1111-1111-111111111111' and type = 'geofence_enter';
  if v_enter <> 1 then
    raise exception 'geofence enter: expected 1 enter event, got %', v_enter;
  end if;
end $$;

-- 3. STALE point OUTSIDE (recorded 20 min ago, an offline batch): the 0011 fix
--    means the state must NOT move and no alert may fire — delayed, not lost.
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
values ('11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 23.8100, 90.4000, 5.0, now() - interval '20 minutes');

do $$
declare v_inside boolean; v_events int;
begin
  select inside into v_inside from public.geofence_state
   where geofence_id = '44444444-4444-4444-4444-444444444444'
     and device_id = '11111111-1111-1111-1111-111111111111';
  if v_inside is distinct from true then
    raise exception 'geofence stale: expected inside to stay true, got %', v_inside;
  end if;
  select count(*) into v_events from public.events
   where device_id = '11111111-1111-1111-1111-111111111111';
  if v_events <> 1 then
    raise exception 'geofence stale: expected still 1 event, got %', v_events;
  end if;
end $$;

-- 4. Fresh point OUTSIDE again: the pending transition is detected now —
--    state flips to false and the exit event fires (delayed, not lost).
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
values ('11111111-1111-1111-1111-111111111111',
        '22222222-2222-2222-2222-222222222222', 23.8100, 90.4000, 5.0, now() - interval '1 minute');

do $$
declare v_inside boolean; v_exit int;
begin
  select inside into v_inside from public.geofence_state
   where geofence_id = '44444444-4444-4444-4444-444444444444'
     and device_id = '11111111-1111-1111-1111-111111111111';
  if v_inside is distinct from false then
    raise exception 'geofence exit: expected inside=false, got %', v_inside;
  end if;
  select count(*) into v_exit from public.events
   where device_id = '11111111-1111-1111-1111-111111111111' and type = 'geofence_exit';
  if v_exit <> 1 then
    raise exception 'geofence exit: expected 1 exit event, got %', v_exit;
  end if;
end $$;

-- ================= device_trips =================
-- Ride 1: 6 moving points (speed 5 m/s, ~111 m apart, 1 min apart), then a
-- 20-minute stop bridged by 10-minute parked heartbeats (speed 0), then
-- 6 more moving points. The 0012 rules say this is ONE trip (stop < 30 min,
-- heartbeat gaps < p_gap 15 min) and the average speed must include the stop.
-- Times are relative to a base 2 hours ago, so the run is repeatable.
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
select '33333333-3333-3333-3333-333333333333',
       '22222222-2222-2222-2222-222222222222',
       23.8000 + (g.i * 0.001), 90.4000, 5.0,
       now() - interval '2 hours' + (g.i || ' minutes')::interval
  from generate_series(0, 5) as g(i);
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
values ('33333333-3333-3333-3333-333333333333',
        '22222222-2222-2222-2222-222222222222', 23.8050, 90.4000, 0.0, now() - interval '105 minutes'),
       ('33333333-3333-3333-3333-333333333333',
        '22222222-2222-2222-2222-222222222222', 23.8050, 90.4000, 0.0, now() - interval '95 minutes');
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
select '33333333-3333-3333-3333-333333333333',
       '22222222-2222-2222-2222-222222222222',
       23.8060 + (g.i * 0.001), 90.4000, 5.0,
       now() - interval '94 minutes' + (g.i || ' minutes')::interval
  from generate_series(0, 5) as g(i);

do $$
declare v_trips int; v_points int; v_avg double precision; v_max double precision;
begin
  select count(*) into v_trips
    from public.device_trips('33333333-3333-3333-3333-333333333333', now() - interval '1 day');
  if v_trips <> 1 then
    raise exception 'trips ride 1: expected 1 trip (stop < 30 min), got %', v_trips;
  end if;
  select points, avg_speed_kmh, max_speed_kmh into v_points, v_avg, v_max
    from public.device_trips('33333333-3333-3333-3333-333333333333', now() - interval '1 day')
   limit 1;
  if v_points <> 12 then
    raise exception 'trips ride 1: expected 12 moving points, got %', v_points;
  end if;
  if not (v_avg > 0 and v_avg < v_max) then
    raise exception 'trips ride 1: average % must be between 0 and max % (stop included)', v_avg, v_max;
  end if;
end $$;

-- Ride 2: after 40 minutes of silence (no points at all) the bike moves
-- again — a real trip end (gap > p_gap 15 min), so there must now be 2 trips.
insert into public.locations (device_id, owner_id, lat, lng, speed, recorded_at)
select '33333333-3333-3333-3333-333333333333',
       '22222222-2222-2222-2222-222222222222',
       23.8120 + (g.i * 0.001), 90.4000, 5.0,
       now() - interval '49 minutes' + (g.i || ' minutes')::interval
  from generate_series(0, 5) as g(i);

do $$
declare v_trips int;
begin
  select count(*) into v_trips
    from public.device_trips('33333333-3333-3333-3333-333333333333', now() - interval '1 day');
  if v_trips <> 2 then
    raise exception 'trips ride 2: expected 2 trips after a 40-min silence, got %', v_trips;
  end if;
end $$;

rollback;
