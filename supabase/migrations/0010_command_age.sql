-- Server-computed command age (v1.2.8, review item #6).
-- The tracker used to compare the server's created_at with the phone's
-- currentTimeMillis to decide whether a command was expired. On a phone whose
-- clock is minutes wrong, every command expired. This RPC returns the age
-- computed by the database clock (extract(epoch from now() - created_at)),
-- so a wrong phone clock can no longer expire commands.
-- Freshness windows are unchanged (tracking_start/arm 60 min, others 10 min);
-- only the clock they are measured against changed.
-- The function runs as the invoker, so the commands RLS policies still apply:
-- a caller only ever sees its own rows.

create or replace function public.pending_commands(p_device uuid)
returns table (
  id bigint,
  type text,
  arg text,
  created_at timestamptz,
  age_seconds double precision
)
language sql
stable
security invoker
as $$
  select c.id, c.type, c.arg, c.created_at,
         extract(epoch from now() - c.created_at)
  from public.commands c
  where c.device_id = p_device
    and c.status = 'pending'
  order by c.id asc;
$$;

revoke all on function public.pending_commands(uuid) from public, anon;
grant execute on function public.pending_commands(uuid) to authenticated;
