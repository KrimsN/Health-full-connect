package dev.krimsn.healthconnect.health

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneOffset

/**
 * Converts a raw Health Connect [Record] into the normalized [SyncRecord] shape
 * uploaded to Supabase. One branch per type in [RecordType]; unsupported types
 * are never requested from Health Connect in the first place, so [dbName]
 * always matches a real branch here -- see RecordType.kt for the type list.
 */
fun Record.toSyncRecord(dbName: String): SyncRecord {
    val meta = metadata
    return when (this) {
        is StepsRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("count", count) },
        )

        is DistanceRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("meters", distance.inMeters) },
        )

        is TotalCaloriesBurnedRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("kcal", energy.inKilocalories) },
        )

        is HeartRateRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject {
                put("samples", heartRateSamplesJson())
            },
        )

        is RestingHeartRateRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("bpm", beatsPerMinute) },
        )

        is HeartRateVariabilityRmssdRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("rmssdMillis", heartRateVariabilityMillis) },
        )

        is OxygenSaturationRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("percentage", percentage.value) },
        )

        is RespiratoryRateRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("rate", rate) },
        )

        is Vo2MaxRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject {
                put("vo2MillilitersPerMinuteKilogram", vo2MillilitersPerMinuteKilogram)
            },
        )

        is SleepSessionRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject {
                title?.let { put("title", it) }
                put("stages", sleepStagesJson())
            },
        )

        is ExerciseSessionRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = startTime,
            endTime = endTime,
            zoneOffsetSeconds = startZoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject {
                put("exerciseTypeCode", exerciseType)
                title?.let { put("title", it) }
            },
        )

        is WeightRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("weightKg", weight.inKilograms) },
        )

        is BodyFatRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("percentage", percentage.value) },
        )

        is LeanBodyMassRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("massKg", mass.inKilograms) },
        )

        is BoneMassRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("massKg", mass.inKilograms) },
        )

        is BasalMetabolicRateRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("kcalPerDay", basalMetabolicRate.inKilocaloriesPerDay) },
        )

        is BloodGlucoseRecord -> SyncRecord(
            recordType = dbName,
            hcRecordId = meta.id,
            startTime = time,
            endTime = null,
            zoneOffsetSeconds = zoneOffset?.offsetSeconds,
            sourceApp = meta.dataOrigin.packageName,
            device = meta.device?.model,
            lastModified = meta.lastModifiedTime,
            value = buildJsonObject { put("millimolesPerLiter", level.inMillimolesPerLiter) },
        )

        else -> error("Unmapped Health Connect record class: ${this::class.simpleName}")
    }
}

private fun HeartRateRecord.heartRateSamplesJson(): JsonArray = buildJsonArray {
    samples.forEach { sample ->
        add(
            buildJsonObject {
                put("time", sample.time.toString())
                put("bpm", sample.beatsPerMinute)
            },
        )
    }
}

private fun SleepSessionRecord.sleepStagesJson(): JsonArray = buildJsonArray {
    stages.forEach { s ->
        add(
            buildJsonObject {
                put("start", s.startTime.toString())
                put("end", s.endTime.toString())
                put("stage", sleepStageName(s.stage))
            },
        )
    }
}

private fun sleepStageName(stage: Int): String = when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    else -> "unknown_$stage"
}

private val ZoneOffset.offsetSeconds: Int get() = totalSeconds
