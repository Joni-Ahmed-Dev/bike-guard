-- BikeGuard fix (review item #13): trips must survive a stop, and the average
-- speed must include the stop.
--
-- Before: only points with speed >= 1.0 were considered, so a stop longer than
-- p_gap (5 min) split one ride into two trips, and avg() ran over moving
-- points only, which reads too high. The 1.0 m/s cut also disagreed with the
-- app's moving threshold (AdaptivePolicy.MOVING_SPEED_MS = 1.5 m/s, used by
-- Guard, TrackingService and the odometer).
--
-- Now: a trip starts at a moving point (speed >= 1.5 m/s) and ends when the
-- bike has not moved for more than 30 minutes, or when no point at all arrives
-- for more than p_gap. Parked heartbeat points (speed 0, re-sent by the
-- tracker every 10 min) keep the point gap small, so a short stop bridges
-- instead of splitting. Average speed = distance / total trip time, stops
-- included. Distance is still summed over moving points only, so heartbeat
-- points add nothing.
--
-- p_gap default rises 5 -> 15 minutes: it must exceed the 10-minute parked
-- heartbeat, otherwise the heartbeat itself would split the trip. A gap
-- longer than 15 minutes only happens when tracking actually stopped
-- (Auto mode parked stop, service killed, GPS off), which is a real trip end.
-- The signature is unchanged, so existing callers keep working.

create or replace function public.device_trips(
  p_device uuid,
  p_since  timestamptz default (now() - interval '30 days'),
  p_gap    interval    default interval '15 minutes'
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
    select l.recorded_at, l.lat, l.lng, l.speed,
           l.speed >= 1.5 as moving
    from public.locations l
    where l.device_id = p_device and l.recorded_at >= p_since
  ),
  flagged as (
    select p.*,
      lag(p.recorded_at) over (order by p.recorded_at) as prev_at,
      max(case when p.moving then p.recorded_at end) over (
        order by p.recorded_at
        rows between unbounded preceding and 1 preceding
      ) as prev_moving_at
    from pts p
  ),
  moving as (
    select f.*,
      case when f.prev_moving_at is null then 1
           when f.recorded_at - f.prev_at > p_gap then 1
           when f.recorded_at - f.prev_moving_at > interval '30 minutes' then 1
           else 0 end as is_start
    from flagged f
    where f.moving
  ),
  numbered as (
    select m.*,
      sum(m.is_start) over (order by m.recorded_at rows between unbounded preceding and current row) as tno,
      lag(m.lat) over (order by m.recorded_at) as plat,
      lag(m.lng) over (order by m.recorded_at) as plng
    from moving m
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
         sum(s.step_m), max(s.speed) * 3.6,
         sum(s.step_m) * 3.6 / nullif(extract(epoch from (max(s.recorded_at) - min(s.recorded_at))), 0)
  from stepped s
  group by s.tno
  having count(*) >= 5 and sum(s.step_m) >= 200
  order by min(s.recorded_at) desc
  limit 100;
$$;

revoke all on function public.device_trips(uuid, timestamptz, interval) from public, anon;
grant execute on function public.device_trips(uuid, timestamptz, interval) to authenticated;
