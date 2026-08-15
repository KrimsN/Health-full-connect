-- Typed read views over the generic health_records table. Each view extracts the
-- JSON fields relevant to one metric so consumers (MCP tools, ad-hoc SQL) don't need
-- to know the per-record-type JSON shape written by the Android client.

-- Base view every typed view below reads from instead of health_records
-- directly. Applies `not deleted` plus a per-record-type physiological
-- plausibility range, so sensor garbage (Mi Scale 2 mid-step misreads chief
-- among them: e.g. a 0.1 kg or 3.2 kg "weight" next to a normal ~125 kg
-- reading seconds apart) is excluded from every downstream view without each
-- one re-deriving its own bounds. Raw health_records stays untouched --
-- list_metrics/get_records/run_sql in the MCP server intentionally read it
-- directly so raw truth (including anomalies) stays inspectable.
--
-- Only static, single-row range checks live here. Anything that needs
-- comparison against *other* rows (Weight's local-median-deviation check,
-- for spike-and-revert misreads that land inside a normal human weight
-- range) can't be expressed as a per-row CASE and is layered on top in
-- v_weight instead. Record types with no obvious physiological bound
-- (Steps, Distance, SleepSession, ExerciseSession, TotalCaloriesBurned)
-- pass through unfiltered via the ELSE branch. HeartRate is a series type --
-- its samples[].bpm plausibility can't be checked here (this CASE runs once
-- per row, before the per-sample unnest) -- see v_heart_rate below.
--
-- coalesce(..., true) fails open: if a checked type is ever missing its key
-- (NULL), the row is not dropped, matching "still write it, only exclude
-- confidently-wrong readings."
create or replace view public.v_health_records_clean as
select *
from public.health_records
where not deleted
  and coalesce(
    case record_type
      when 'Weight' then
        (value ->> 'weightKg')::numeric between 30 and 300
      when 'BodyFat' then
        (value ->> 'percentage')::numeric between 3 and 75
      when 'LeanBodyMass' then
        (value ->> 'massKg')::numeric between 10 and 150
      when 'BoneMass' then
        (value ->> 'massKg')::numeric between 0.3 and 10
      when 'OxygenSaturation' then
        (value ->> 'percentage')::numeric between 50 and 100
      when 'RestingHeartRate' then
        (value ->> 'bpm')::numeric between 20 and 250
      when 'BasalMetabolicRate' then
        (value ->> 'kcalPerDay')::numeric between 500 and 5000
      when 'Vo2Max' then
        (value ->> 'vo2MillilitersPerMinuteKilogram')::numeric between 10 and 95
      when 'RespiratoryRate' then
        (value ->> 'rate')::numeric between 4 and 60
      when 'HeartRateVariability' then
        (value ->> 'rmssdMillis')::numeric between 1 and 300
      when 'BloodGlucose' then
        (value ->> 'millimolesPerLiter')::numeric between 1 and 35
      else true
    end,
    true
  );

-- Two-stage filter: stage 1 (static range) already happened in
-- v_health_records_clean. Stage 2 here rejects plausible-looking-but-
-- locally-inconsistent readings (a scale misread mid-step-on that lands
-- inside the normal human weight range, e.g. a lone 136.7 kg between two
-- 125.9 kg readings minutes apart) by comparing each reading against the
-- median of same-type readings within +/-3 days, rejecting if it deviates
-- by more than 5% of that local median. Readings with no nearby neighbors
-- yet are kept rather than rejected for lack of evidence.
--
-- percentile_cont is an ordered-set aggregate and Postgres does not support
-- ordered-set aggregates as window functions, so the per-row "median of
-- nearby rows" is a LATERAL join rather than an OVER clause. This stays
-- cheap as data grows because `start_time between ...` lets the planner use
-- the existing health_records_type_time_idx (record_type, start_time desc)
-- as a bounded range scan per outer row, not a full table scan.
create or replace view public.v_weight as
with plausible_weight as (
  select
    hc_record_id,
    start_time,
    local_date,
    (value ->> 'weightKg')::numeric as weight_kg
  from public.v_health_records_clean
  where record_type = 'Weight'
)
select
  w.hc_record_id,
  w.start_time,
  w.local_date,
  w.weight_kg
from plausible_weight w
cross join lateral (
  select percentile_cont(0.5) within group (order by nearby.weight_kg)
    as local_median
  from plausible_weight nearby
  where nearby.hc_record_id <> w.hc_record_id
    and nearby.start_time between w.start_time - interval '3 days'
                              and w.start_time + interval '3 days'
) med
where med.local_median is null
   or abs(w.weight_kg - med.local_median) <= med.local_median * 0.05
order by w.start_time desc;

-- Mi Scale 2 readings land as several same-timestamp records of different types
-- (Weight, BodyFat, LeanBodyMass, BoneMass, BasalMetabolicRate). Pivoting them
-- back onto one row per start_time reconstructs a single scale reading.
--
-- weight_kg is sourced from v_weight (already through the two-stage outlier
-- filter above); the other four columns are grouped from
-- v_health_records_clean (stage-1 range filter only -- they don't have a
-- demonstrated median-deviation failure mode in practice). FULL JOIN on
-- start_time so a row survives even if only one side has a plausible
-- reading at that timestamp: an outlier weight_kg becomes null while
-- body_fat_pct/lean_body_mass_kg/bone_mass_kg/bmr_kcal_per_day from the same
-- scale event are still surfaced, and vice versa.
create or replace view public.v_body_composition as
select
  coalesce(nw.start_time, w.start_time) as start_time,
  coalesce(nw.local_date, w.local_date) as local_date,
  w.weight_kg,
  nw.body_fat_pct,
  nw.lean_body_mass_kg,
  nw.bone_mass_kg,
  nw.bmr_kcal_per_day
from (
  select
    start_time,
    local_date,
    max((value ->> 'percentage')::numeric) filter (where record_type = 'BodyFat')
      as body_fat_pct,
    max((value ->> 'massKg')::numeric) filter (where record_type = 'LeanBodyMass')
      as lean_body_mass_kg,
    max((value ->> 'massKg')::numeric) filter (where record_type = 'BoneMass')
      as bone_mass_kg,
    max((value ->> 'kcalPerDay')::numeric) filter (where record_type = 'BasalMetabolicRate')
      as bmr_kcal_per_day
  from public.v_health_records_clean
  where record_type in ('BodyFat', 'LeanBodyMass', 'BoneMass', 'BasalMetabolicRate')
  group by start_time, local_date
) nw
full join (
  select start_time, local_date, max(weight_kg) as weight_kg
  from public.v_weight
  group by start_time, local_date
) w
  on w.start_time = nw.start_time
order by coalesce(nw.start_time, w.start_time) desc;

create or replace view public.v_steps_daily as
select
  local_date,
  sum((value ->> 'count')::bigint) as steps
from public.v_health_records_clean
where record_type = 'Steps'
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
from public.v_health_records_clean
where record_type = 'SleepSession'
order by start_time desc;

-- HeartRateRecord is a series type: one Health Connect record covers a time range
-- and carries many (time, bpm) samples in its samples list. The Android mapper
-- stores that list verbatim in `value.samples`; this view unnests it so each
-- sample becomes its own row for time-bucketed aggregation. Per-sample bpm
-- plausibility can't live in v_health_records_clean's whole-row CASE (that
-- runs once per record, before this unnest), so the range check is applied
-- here instead, after jsonb_array_elements splits value.samples into rows.
create or replace view public.v_heart_rate as
select
  hr.hc_record_id,
  hr.local_date,
  (sample ->> 'time')::timestamptz as sample_time,
  (sample ->> 'bpm')::int as bpm
from public.v_health_records_clean hr
cross join lateral jsonb_array_elements(hr.value -> 'samples') as sample
where hr.record_type = 'HeartRate'
  and (sample ->> 'bpm')::int between 20 and 250;

create or replace view public.v_resting_hr as
select
  hc_record_id,
  start_time,
  local_date,
  (value ->> 'bpm')::int as resting_bpm
from public.v_health_records_clean
where record_type = 'RestingHeartRate'
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
  select distinct local_date from public.v_health_records_clean
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
