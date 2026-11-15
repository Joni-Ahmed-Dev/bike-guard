-- CI-only setup for supabase/tests/sql_tests.sql (v1.2.21, review item #23).
-- The BikeGuard migrations are written for Supabase, so a vanilla Postgres
-- service needs these stubs before the migrations can apply:
--   * the auth schema, auth.users table and auth.uid() (used by every
--     owner_id default and RLS policy), returning the test owner id;
--   * the anon / authenticated roles (used by GRANT / REVOKE in the migrations);
--   * the supabase_realtime publication (0003 adds tables to it).
-- Nothing here is needed on the live Supabase project — it already has all
-- of it. Run before the migrations, with the same connection:
--   psql -v ON_ERROR_STOP=1 -f supabase/tests/ci-setup.sql

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin;
  end if;
end $$;

create schema if not exists auth;
create table if not exists auth.users (id uuid primary key);
insert into auth.users (id)
values ('22222222-2222-2222-2222-222222222222')
on conflict (id) do nothing;

create or replace function auth.uid() returns uuid
language sql stable as $$ select '22222222-2222-2222-2222-222222222222'::uuid $$;

-- CREATE PUBLICATION has no IF NOT EXISTS clause, so guard it explicitly.
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end $$;
