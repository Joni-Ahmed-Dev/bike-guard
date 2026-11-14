-- BikeGuard fix (review item #16): a shared link exposed a 90-minute route,
-- which can include the home address. The trail now covers only the last
-- 30 minutes; the live position from shared_location is unchanged.
-- Per-link revoke needs no schema change: share_links.token is already the
-- primary key and revoked is per-row, so the app now PATCHes one token
-- instead of every active link for the device (see v1.2.14).

create or replace function public.shared_trail(p_token text)
returns table (lat double precision, lng double precision, recorded_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select t.lat, t.lng, t.recorded_at from (
    select l.lat, l.lng, l.recorded_at
      from public.share_links s
      join public.locations l on l.device_id = s.device_id
     where s.token = p_token and not s.revoked and s.expires_at > now()
       and l.recorded_at > now() - interval '30 minutes'
     order by l.recorded_at desc limit 120) t
  order by t.recorded_at asc;
$$;
revoke all on function public.shared_trail(text) from public;
grant execute on function public.shared_trail(text) to anon, authenticated;
