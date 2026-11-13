-- BikeGuard Phase 2: trip detection on the server.
-- A trip = consecutive "moving" points (speed >= 1 m/s) with no gap longer than p_gap.
-- security invoker => Row Level Security still applies: you only ever see your own trips.

create or replace function public.device_trips(
  p_device uuid,
  p_since  timestamptz default (now() - interval '30 days'),
  p_gap    interval    default interval '5 minutes'
)
returns table (
  trip_no        int,
  started_at     timestamptz,
  ended_at       timestamptz,
  points         int,
  distance_m     double precision,
  max_speed_kmh  double precision,
  avg_speed_kmh  double precision
)
language sql stable security invoker set search_path = ''
as $$
  with pts as (
    select l.recorded_at, l.lat, l.lng, l.speed
    from public.locations l
    where l.device_id = p_device and l.recorded_at >= p_since and l.speed >= 1.0
  ),
  flagged as (
    select p.*,
      case when lag(p.recorded_at) over w is null
             or p.recorded_at - lag(p.recorded_at) over w > p_gap then 1 else 0 end as is_start,
      lag(p.lat) over w as plat,
      lag(p.lng) over w as plng
    from pts p
    window w as (order by p.recorded_at)
  ),
  numbered as (
    select f.*, sum(f.is_start) over (order by f.recorded_at rows between unbounded preceding and current row) as tno
    from flagged f
  ),
  stepped as (
    select n.*,
      case when n.is_start = 1 then 0::double precision else
        2 * 6371000 * asin(least(1, sqrt(
          power(sin(radians(n.lat - n.plat) / 2), 2) +
          cos(radians(n.plat)) * cos(radians(n.lat)) * power(sin(radians(n.lng - n.plng) / 2), 2)
        )))
      end as step_m
    from numbered n
  )
  select s.tno::int, min(s.recorded_at), max(s.recorded_at), count(*)::int,
         sum(s.step_m), max(s.speed) * 3.6, avg(s.speed) * 3.6
  from stepped s
  group by s.tno
  having count(*) >= 5 and sum(s.step_m) >= 200
  order by min(s.recorded_at) desc
  limit 100;
$$;

revoke all on function public.device_trips(uuid, timestamptz, interval) from public, anon;
grant execute on function public.device_trips(uuid, timestamptz, interval) to authenticated;
