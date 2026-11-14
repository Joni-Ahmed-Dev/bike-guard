-- BikeGuard fix (review item #14): the stale-device alarm fired too soon.
--
-- Before: check_stale_devices() alarmed after 15 minutes of silence, while the
-- parked heartbeat is only every 10 minutes. One failed upload at the 10-minute
-- mark left last_seen 10+ minutes old, and the Watchdog's retry (~15 min) could
-- land just after a pg_cron check (every 5 min), so a single network hiccup
-- raised a false device_stale warning (and a Telegram alert) for a bike that
-- was fine.
--
-- Now: the threshold is 30 minutes — three heartbeat intervals. That tolerates
-- two consecutive missed heartbeats (20 min) plus upload and cron jitter, while
-- a genuinely dead tracker still alarms within 30-35 minutes. The heartbeat
-- itself stays at 10 minutes (TrackingService.STATIONARY_HEARTBEAT_MS): halving
-- it would double parked uploads and battery for no real gain, and the
-- Watchdog's 15-minute flush already bounds the retry. 30 minutes also matches
-- the trip stop tolerance from migration 0012, so one outing's pause is one
-- boundary everywhere. The guard (movement detection) is a separate path and
-- is unaffected by this change.

create or replace function public.check_stale_devices()
returns void language sql security definer set search_path = '' as $$
  insert into public.events(owner_id, device_id, type, severity, data)
  select d.owner_id, d.id, 'device_stale', 'warning', jsonb_build_object('last_seen', d.last_seen)
    from public.devices d
   where d.tracking_expected
     and d.last_seen < now() - interval '30 minutes'
     and not exists (select 1 from public.events e
                      where e.device_id = d.id and e.type = 'device_stale' and e.created_at > d.last_seen);
$$;

revoke all on function public.check_stale_devices() from public, anon, authenticated;
