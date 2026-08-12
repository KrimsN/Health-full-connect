-- Access control. Two callers, two roles:
--   * the phone (PostgREST `anon` role via the publishable key) can insert,
--     update, and select on health_records/sync_runs;
--   * the MCP server (`health_reader`, a dedicated login role) can select
--     only, including the typed views in 002_views.sql.
--
-- anon having select is a deliberate tradeoff, not an oversight: PostgREST's
-- upsert (`Prefer: resolution=merge-duplicates`, what the Android client
-- uses for idempotent sync) compiles to `INSERT ... ON CONFLICT DO UPDATE`,
-- which Postgres itself requires SELECT privilege for -- and checks the
-- updated rows against the table's SELECT RLS policies -- regardless of
-- PostgREST. This was fixed as CVE-2017-15099 precisely so a write-only role
-- *couldn't* silently upsert past an RLS SELECT policy that denies it; the
-- flip side is that upsert and "no select" are mutually exclusive. A leaked
-- publishable key can therefore read health data back, not just write it --
-- acceptable here because the key never leaves a personal, sideloaded APK,
-- and the alternative (a SECURITY DEFINER RPC function anon calls instead of
-- touching the table directly) was traded for simplicity.
--
-- Fresh Supabase projects also grant anon/authenticated broad default
-- privileges on the public schema (select/delete/truncate/etc, not just
-- insert/update/select) via ALTER DEFAULT PRIVILEGES set up when the project
-- was created. The explicit revoke-then-grant below resets to exactly what's
-- intended rather than relying on those defaults being narrow.

alter table public.health_records enable row level security;
alter table public.sync_runs enable row level security;

revoke all on public.health_records from anon;
revoke all on public.sync_runs from anon;
grant select, insert, update on public.health_records to anon;
grant select, insert, update on public.sync_runs to anon;

create policy anon_select_health_records on public.health_records
  for select to anon
  using (true);

create policy anon_insert_health_records on public.health_records
  for insert to anon
  with check (true);

create policy anon_update_health_records on public.health_records
  for update to anon
  using (true)
  with check (true);

create policy anon_select_sync_runs on public.sync_runs
  for select to anon
  using (true);

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

-- The GRANT SELECT above is necessary but not sufficient: with RLS enabled,
-- a command with no matching policy is denied outright, table owner
-- excepted.
create policy health_reader_select_health_records on public.health_records
  for select to health_reader
  using (true);

create policy health_reader_select_sync_runs on public.sync_runs
  for select to health_reader
  using (true);

-- anon has no reason to touch any view directly (it only ever talks to the
-- base tables); the default-privileges issue above applies here too.
revoke all on
  public.v_weight,
  public.v_body_composition,
  public.v_steps_daily,
  public.v_sleep_sessions,
  public.v_heart_rate,
  public.v_resting_hr,
  public.v_daily_summary
  from anon;

-- Plain views run with the view owner's privileges by default, which would
-- let a view silently bypass RLS on its base tables for a role that
-- otherwise couldn't read them directly. security_invoker makes each view
-- run as the querying role instead, so the policies above are what actually
-- governs access even through a view.
alter view public.v_weight set (security_invoker = true);
alter view public.v_body_composition set (security_invoker = true);
alter view public.v_steps_daily set (security_invoker = true);
alter view public.v_sleep_sessions set (security_invoker = true);
alter view public.v_heart_rate set (security_invoker = true);
alter view public.v_resting_hr set (security_invoker = true);
alter view public.v_daily_summary set (security_invoker = true);

-- No default privileges are granted on future objects -- a new table added later
-- stays invisible to health_reader until explicitly granted here.
