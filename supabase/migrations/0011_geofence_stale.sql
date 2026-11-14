-- Geofence state must not advance on stale points (review item #8).
-- Bug: geofence_check() updated geofence_state on every transition but only
-- inserted the enter/exit alert when the point was fresher than 10 minutes.
-- An offline batch crossing a geofence boundary therefore consumed the
-- transition without ever alerting: that enter/exit was lost forever, and the
-- brief's claim that offline batches stay correct was wrong for this reason.
-- Fix: the state update is now gated on the same freshness check as the
-- alert. A stale point leaves the state untouched, so the transition is still
-- pending and is detected (and alerted) on the next fresh fix -- delayed
-- instead of lost. The alert is never sent with a stale timestamp as if it
-- were live: it always carries the fresh fix's recorded_at.
-- The first-point initialization is unchanged: it only establishes a
-- baseline, it cannot consume a transition (and a stale baseline is still
-- useful, because the next fresh fix can then detect a transition against it).
-- Threshold is unchanged (10 minutes); only what happens below it changed.
-- No app change needed: the app never reads geofence_state, it only displays
-- geofence_enter/geofence_exit rows from the events table.

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
      -- stale points (offline batch) must not consume the transition: skip the
      -- state update as well as the alert, so the next fresh fix still sees
      -- the transition and alerts (delayed instead of lost).
      if new.recorded_at > now() - interval '10 minutes' then
        update public.geofence_state s set inside = is_in
         where s.geofence_id = g.id and s.device_id = new.device_id;
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
