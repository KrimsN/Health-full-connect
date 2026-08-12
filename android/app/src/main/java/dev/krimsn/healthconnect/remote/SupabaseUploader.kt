package dev.krimsn.healthconnect.remote

import dev.krimsn.healthconnect.BuildConfig
import dev.krimsn.healthconnect.health.SyncRecord
import dev.krimsn.healthconnect.sync.SyncRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.util.concurrent.TimeUnit

class SupabaseUploadException(val httpCode: Int?, message: String) : Exception(message)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Talks to Supabase's PostgREST endpoint using the publishable key. That key is
 * granted select/insert/update on health_records and sync_runs (see
 * db/003_roles_rls.sql) -- select is a forced tradeoff, not a convenience: the
 * upsert below compiles to `INSERT ... ON CONFLICT DO UPDATE`, which Postgres
 * itself requires SELECT privilege for and checks against SELECT RLS policies
 * (core Postgres behavior, not a PostgREST quirk -- see CVE-2017-15099). A
 * write-only role cannot upsert. A leaked key can therefore read health data
 * back, not just write it.
 *
 * Deliberately just the `apikey` header, not `Authorization: Bearer`. Supabase's
 * new non-JWT publishable/secret keys are explicitly documented as rejected by
 * PostgREST when sent as a bearer token -- see "Understanding authorization
 * headers" in the Supabase docs.
 */
class SupabaseUploader(
    private val baseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun uploadRecords(records: List<SyncRecord>) {
        if (records.isEmpty()) return
        val body = buildJsonArray {
            records.forEach { r ->
                add(
                    buildJsonObject {
                        put("record_type", r.recordType)
                        put("hc_record_id", r.hcRecordId)
                        put("start_time", r.startTime.toString())
                        put("end_time", r.endTime?.toString())
                        put("zone_offset", r.zoneOffsetSeconds?.let { "${it}s" })
                        put("source_app", r.sourceApp)
                        put("device", r.device)
                        put("value", r.value)
                        put("deleted", false)
                        put("hc_modified", r.lastModified?.toString())
                    },
                )
            }
        }
        upsert(table = "health_records", onConflict = "record_type,hc_record_id", jsonBody = body.toString())
    }

    suspend fun markDeleted(recordType: String, hcRecordIds: List<String>) {
        if (hcRecordIds.isEmpty()) return
        val body = buildJsonArray {
            hcRecordIds.forEach { id ->
                add(
                    buildJsonObject {
                        put("record_type", recordType)
                        put("hc_record_id", id)
                        // start_time is NOT NULL; a deletion tombstone still needs a value,
                        // and "now" is as good as any -- the row is marked deleted, so no
                        // view reads its timestamp as meaningful.
                        put("start_time", java.time.Instant.now().toString())
                        put("deleted", true)
                    },
                )
            }
        }
        upsert(table = "health_records", onConflict = "record_type,hc_record_id", jsonBody = body.toString())
    }

    suspend fun uploadSyncRun(run: SyncRun) {
        val body = buildJsonArray {
            add(
                buildJsonObject {
                    put("run_id", run.runId)
                    put("started_at", run.startedAt)
                    put("finished_at", run.finishedAt)
                    put("trigger", run.trigger)
                    put("outcome", run.outcome)
                    put("records_read", run.recordsRead)
                    put("records_sent", run.recordsSent)
                    put("per_type", buildJsonObject { run.perType.forEach { (k, v) -> put(k, v) } })
                    put("error_message", run.errorMessage)
                    put("duration_ms", run.durationMs)
                },
            )
        }
        upsert(table = "sync_runs", onConflict = "run_id", jsonBody = body.toString())
    }

    private suspend fun upsert(table: String, onConflict: String, jsonBody: String) {
        val url = "$baseUrl/rest/v1/$table?on_conflict=$onConflict"
        val request = Request.Builder()
            .url(url)
            .header("apikey", publishableKey)
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body.string()
                    throw SupabaseUploadException(
                        httpCode = response.code,
                        message = "Supabase upsert into $table failed: HTTP ${response.code} $responseBody",
                    )
                }
            }
        }
    }
}
