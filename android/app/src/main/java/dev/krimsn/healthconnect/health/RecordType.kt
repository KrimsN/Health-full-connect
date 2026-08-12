package dev.krimsn.healthconnect.health

import androidx.health.connect.client.permission.HealthPermission
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
import kotlin.reflect.KClass

/**
 * Every Health Connect record type this app reads, matched to the exact
 * `record_type` string the Supabase schema expects (see db/002_views.sql --
 * the typed views key off these names) and the Kotlin class the Health Connect
 * API uses to identify it.
 *
 * This list intentionally mirrors what Gadgetbridge's Health Connect export
 * writes (see gadgetbridge.org/basics/integrations/health-connect), minus skin
 * temperature -- that record type is still marked experimental in the Health
 * Connect API and was left out to keep the permission set stable.
 */
enum class RecordType(
    val dbName: String,
    val kClass: KClass<out Record>,
) {
    STEPS("Steps", StepsRecord::class),
    DISTANCE("Distance", DistanceRecord::class),
    TOTAL_CALORIES_BURNED("TotalCaloriesBurned", TotalCaloriesBurnedRecord::class),
    HEART_RATE("HeartRate", HeartRateRecord::class),
    RESTING_HEART_RATE("RestingHeartRate", RestingHeartRateRecord::class),
    HEART_RATE_VARIABILITY("HeartRateVariability", HeartRateVariabilityRmssdRecord::class),
    OXYGEN_SATURATION("OxygenSaturation", OxygenSaturationRecord::class),
    RESPIRATORY_RATE("RespiratoryRate", RespiratoryRateRecord::class),
    VO2_MAX("Vo2Max", Vo2MaxRecord::class),
    SLEEP_SESSION("SleepSession", SleepSessionRecord::class),
    EXERCISE_SESSION("ExerciseSession", ExerciseSessionRecord::class),
    WEIGHT("Weight", WeightRecord::class),
    BODY_FAT("BodyFat", BodyFatRecord::class),
    LEAN_BODY_MASS("LeanBodyMass", LeanBodyMassRecord::class),
    BONE_MASS("BoneMass", BoneMassRecord::class),
    BASAL_METABOLIC_RATE("BasalMetabolicRate", BasalMetabolicRateRecord::class),
    BLOOD_GLUCOSE("BloodGlucose", BloodGlucoseRecord::class),
    ;

    val readPermission: String get() = HealthPermission.getReadPermission(kClass)

    companion object {
        fun fromDbName(name: String): RecordType? = entries.find { it.dbName == name }

        /** android.permission.health.* permissions with no per-type constant. */
        const val PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        const val PERMISSION_READ_HEALTH_DATA_HISTORY =
            "android.permission.health.READ_HEALTH_DATA_HISTORY"

        /** Full permission set requested from the user in one Health Connect screen. */
        val ALL_PERMISSIONS: Set<String> =
            entries.map { it.readPermission }.toSet() +
                setOf(PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND, PERMISSION_READ_HEALTH_DATA_HISTORY)
    }
}
