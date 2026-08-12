package dev.krimsn.healthconnect.health

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Normalized shape of one Health Connect record, ready to be upserted into
 * Supabase's health_records table. [value] mirrors the per-record-type JSON
 * shapes the typed views in db/002_views.sql expect.
 */
data class SyncRecord(
    val recordType: String,
    val hcRecordId: String,
    val startTime: Instant,
    val endTime: Instant?,
    val zoneOffsetSeconds: Int?,
    val sourceApp: String?,
    val device: String?,
    val lastModified: Instant?,
    val value: JsonObject,
)
