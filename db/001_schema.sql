-- Core tables for Health-full-connect.
-- Run once against a fresh Supabase project via the SQL Editor, in filename order.

-- One generic table for every Health Connect record type instead of one table per
-- type (~15 types produced by Gadgetbridge). Downstream consumers read typed views
-- (see 002_views.sql), so the per-type shape lives in `value` rather than in schema.
create table if not exists public.health_records (
  record_type   text        not null,   -- 'Weight', 'HeartRate', 'Steps', 'SleepSession', ...
  hc_record_id  text        not null,   -- Health Connect metadata.id (stable UUID)
  start_time    timestamptz not null,
  end_time      timestamptz,            -- null for instantaneous records
  zone_offset   interval,               -- device UTC offset at recording time
  source_app    text,                   -- metadata.dataOrigin.packageName
  device        text,
  value         jsonb       not null,   -- record fields; shape depends on record_type
  deleted       boolean     not null default false,
  hc_modified   timestamptz,            -- Health Connect's own last-modified time
  ingested_at   timestamptz not null default now(),
  -- "Steps on Aug 3" is a question about the phone's local day, not UTC, so the
  -- local date is computed and stored rather than derived per query.
  local_date    date generated always as
                 (((start_time at time zone 'utc') + coalesce(zone_offset, interval '0'))::date)
                 stored,
  primary key (record_type, hc_record_id)
);

create index if not exists health_records_type_time_idx
  on public.health_records (record_type, start_time desc);

create index if not exists health_records_type_local_date_idx
  on public.health_records (record_type, local_date);

create index if not exists health_records_value_gin_idx
  on public.health_records using gin (value);

comment on table public.health_records is
  'Raw and derived Health Connect records synced from the Android client. One row per (record_type, hc_record_id); upserts from the phone use Prefer: resolution=merge-duplicates on this key for idempotent incremental sync.';

-- Sync run log, mirrored from the on-device history (DataStore ring buffer).
-- The phone is the source of truth for the user-facing history screen -- a run
-- that fails on the network never reaches this table by definition -- this copy
-- exists for the MCP server, to answer "when did data last arrive" and surface
-- coverage gaps without needing to ask the phone directly.
create table if not exists public.sync_runs (
  run_id        uuid        primary key,        -- generated on device
  started_at    timestamptz not null,
  finished_at   timestamptz,
  trigger       text        not null
                check (trigger in ('periodic', 'manual', 'manual_full', 'backfill')),
  outcome       text        not null
                check (outcome in ('ok', 'partial', 'error')),
  records_read  int         not null default 0,
  records_sent  int         not null default 0,
  per_type      jsonb,                          -- {"HeartRate": 412, "Steps": 96, ...}
  error_message text,
  duration_ms   int
);

create index if not exists sync_runs_started_at_idx
  on public.sync_runs (started_at desc);

comment on table public.sync_runs is
  'Mirror of the on-device sync history, upserted by the Android client after each run. Consumed by the MCP sync_health tool.';
