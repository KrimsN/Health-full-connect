-- Typed read views over the generic health_records table. Each view extracts the
-- JSON fields relevant to one metric so consumers (MCP tools, ad-hoc SQL) don't need
-- to know the per-record-type JSON shape written by the Android client.

create or replace view public.v_weight as
select
  hc_record_id,
  start_time,
  local_date,
  (value ->> 'weightKg')::numeric as weight_kg
from public.health_records
where record_type = 'Weight' and not deleted
order by start_time desc;

-- Mi Scale 2 readings land as several same-timestamp records of different types
-- (Weight, BodyFat, LeanBodyMass, BoneMass, BasalMetabolicRate). Pivoting them
-- back onto one row per start_time reconstructs a single scale reading.
create or replace view public.v_body_composition as
select
  start_time,
  local_date,
  max((value ->> 'weightKg')::numeric) filter (where record_type = 'Weight')
    as weight_kg,
  max((value ->> 'percentage')::numeric) filter (where record_type = 'BodyFat')
    as body_fat_pct,
  max((value ->> 'massKg')::numeric) filter (where record_type = 'LeanBodyMass')
    as lean_body_mass_kg,
  max((value ->> 'massKg')::numeric) filter (where record_type = 'BoneMass')
    as bone_mass_kg,
  max((value ->> 'kcalPerDay')::numeric) filter (where record_type = 'BasalMetabolicRate')
    as bmr_kcal_per_day
from public.health_records
where record_type in ('Weight', 'BodyFat', 'LeanBodyMass', 'BoneMass', 'BasalMetabolicRate')
  and not deleted
group by start_time, local_date
order by start_time desc;

create or replace view public.v_steps_daily as
select
  local_date,
  sum((value ->> 'count')::bigint) as steps
from public.health_records
where record_type = 'Steps' and not deleted
group by local_date
order by local_date desc;

create or replace view public.v_sleep_sessions as
select
  hc_record_id,
  start_time,
  end_time,
  local_date,
  extract(epoch from (end_time - start_time)) / 60 as duration_minutes,
  value -> 'stages' as stages
from public.health_records
where record_type = 'SleepSession' and not deleted
order by start_time desc;

-- HeartRateRecord is a series type: one Health Connect record covers a time range
-- and carries many (time, bpm) samples in its samples list. The Android mapper
-- stores that list verbatim in `value.samples`; this view unnests it so each
-- sample becomes its own row for time-bucketed aggregation.
create or replace view public.v_heart_rate as
select
  hr.hc_record_id,
  hr.local_date,
  (sample ->> 'time')::timestamptz as sample_time,
  (sample ->> 'bpm')::int as bpm
from public.health_records hr
cross join lateral jsonb_array_elements(hr.value -> 'samples') as sample
where hr.record_type = 'HeartRate' and not hr.deleted;

create or replace view public.v_resting_hr as
select
  hc_record_id,
  start_time,
  local_date,
  (value ->> 'bpm')::int as resting_bpm
from public.health_records
where record_type = 'RestingHeartRate' and not deleted
order by start_time desc;

-- One row per local day, left-joining every metric onto the set of days that have
-- at least one record of any type -- days with partial data (e.g. steps but no
-- scale reading) show up with nulls rather than being dropped.
create or replace view public.v_daily_summary as
select
  d.local_date,
  s.steps,
  sl.total_sleep_minutes,
  hr.avg_bpm,
  rhr.resting_bpm,
  w.weight_kg
from (
  select distinct local_date from public.health_records where not deleted
) d
left join public.v_steps_daily s on s.local_date = d.local_date
left join (
  select local_date, sum(duration_minutes) as total_sleep_minutes
  from public.v_sleep_sessions
  group by local_date
) sl on sl.local_date = d.local_date
left join (
  select local_date, avg(bpm)::numeric(5, 1) as avg_bpm
  from public.v_heart_rate
  group by local_date
) hr on hr.local_date = d.local_date
left join lateral (
  select resting_bpm from public.v_resting_hr r
  where r.local_date = d.local_date
  order by r.start_time desc
  limit 1
) rhr on true
left join lateral (
  select weight_kg from public.v_weight w
  where w.local_date = d.local_date
  order by w.start_time desc
  limit 1
) w on true
order by d.local_date desc;
