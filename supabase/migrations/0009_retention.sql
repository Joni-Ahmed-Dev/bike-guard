-- BikeGuard retention (v1.2.8, review item #6).
-- Keeps 30 days of locations and 7 days of commands (user decision, 14 Nov 2026).
-- Alert history (public.events) is NOT deleted by this job.
-- The commented 90-day proposal in 0001_init.sql is superseded by this job; 0001 is left untouched.

create or replace function public.purge_old_bikeguard_data()
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.locations where recorded_at < now() - interval '30 days';
  delete from public.commands where created_at < now() - interval '7 days';
$$;

-- Idempotent: drop a previous schedule of the same name before re-creating it,
-- so re-applying this migration never leaves two 03:00 jobs running.
do $$
begin
  if exists (select 1 from cron.job where jobname = 'bikeguard-retention') then
    perform cron.unschedule('bikeguard-retention');
  end if;
end $$;

select cron.schedule(
  'bikeguard-retention',
  '0 3 * * *',
  'select public.purge_old_bikeguard_data()'
);
