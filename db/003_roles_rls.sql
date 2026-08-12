-- Access control. Two callers, two different privilege sets, neither able to do
-- what the other does:
--   * the phone (PostgREST `anon` role via the publishable key) can only insert
--     and update health_records/sync_runs -- select is not granted, so a leaked
--     APK key lets someone spam rows but never read health data back;
--   * the MCP server (`health_reader`, a dedicated login role) can only select,
--     including from the typed views in 002_views.sql.

alter table public.health_records enable row level security;
alter table public.sync_runs enable row level security;

grant insert, update on public.health_records to anon;
grant insert, update on public.sync_runs to anon;

create policy anon_insert_health_records on public.health_records
  for insert to anon
  with check (true);

create policy anon_update_health_records on public.health_records
  for update to anon
  using (true)
  with check (true);

create policy anon_insert_sync_runs on public.sync_runs
  for insert to anon
  with check (true);

create policy anon_update_sync_runs on public.sync_runs
  for update to anon
  using (true)
  with check (true);

-- Dedicated read-only role for the MCP server. Not part of the standard
-- anon/authenticated/service_role trio PostgREST uses, so it is reached by
-- connecting directly to Postgres (via Supavisor), not through the REST API.
--
-- IMPORTANT: replace the placeholder password below before running this file,
-- then never commit the real value -- it belongs only in mcp/.env. See
-- docs/SETUP.md for how to enable this role for Supavisor connection pooling
-- (Dashboard -> Database -> Roles -> health_reader -> "Connection pooling").
create role health_reader with login password 'REPLACE_WITH_STRONG_PASSWORD' nosuperuser noinherit;

alter role health_reader set statement_timeout = '15s';

grant usage on schema public to health_reader;

grant select on public.health_records to health_reader;
grant select on public.sync_runs to health_reader;
grant select on
  public.v_weight,
  public.v_body_composition,
  public.v_steps_daily,
  public.v_sleep_sessions,
  public.v_heart_rate,
  public.v_resting_hr,
  public.v_daily_summary
  to health_reader;

-- No default privileges are granted on future objects -- a new table added later
-- stays invisible to health_reader until explicitly granted here.
