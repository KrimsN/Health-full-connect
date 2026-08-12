package dev.krimsn.healthconnect.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/** Outcome of reading everything new since the last stored changes token. */
data class ChangesResult(
    val upserted: List<SyncRecord>,
    val deletedIds: List<String>,
    val nextToken: String,
    /** True when Health Connect reported the token as expired -- callers should
     *  fall back to [HealthConnectRepository.readRange] for a bounded window
     *  instead of treating this as a hard failure. */
    val tokenExpired: Boolean,
)

/**
 * Thin wrapper around [HealthConnectClient] scoped to the record types this app
 * cares about ([RecordType.entries]). Two read paths:
 *  - [readChangesSince] -- incremental, used by every normal sync run.
 *  - [readRange] -- bounded by wall-clock time, used for manual backfill and as
 *    the fallback when a changes token has expired (Health Connect keeps tokens
 *    valid for roughly 30 days of inactivity; a phone that sits unsynced longer
 *    than that would otherwise get stuck rather than just losing incrementality).
 */
class HealthConnectRepository(context: Context) {

    private val appContext = context.applicationContext

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(RecordType.ALL_PERMISSIONS)

    private suspend fun newChangesToken(): String =
        client.getChangesToken(
            ChangesTokenRequest(recordTypes = RecordType.entries.map { it.kClass }.toSet()),
        )

    /**
     * Reads every change since [token]. Health Connect signals an expired token
     * by throwing `IllegalStateException` from [HealthConnectClient.getChanges];
     * that specific, documented failure mode is caught here and reported via
     * [ChangesResult.tokenExpired] rather than propagated, so callers don't need
     * to know Health Connect's token-lifetime internals.
     */
    suspend fun readChangesSince(token: String?): ChangesResult {
        val startToken = token ?: newChangesToken()
        val upserted = mutableListOf<SyncRecord>()
        val deleted = mutableListOf<String>()
        var currentToken = startToken

        try {
            lateinit var response: ChangesResponse
            do {
                response = client.getChanges(currentToken)
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> {
                            val dbName = RecordType.entries
                                .find { it.kClass == change.record::class }
                                ?.dbName
                            if (dbName != null) {
                                upserted += change.record.toSyncRecord(dbName)
                            }
                        }
                        is DeletionChange -> deleted += change.recordId
                    }
                }
                currentToken = response.nextChangesToken
            } while (response.hasMore)
        } catch (e: IllegalStateException) {
            return ChangesResult(
                upserted = upserted,
                deletedIds = deleted,
                nextToken = newChangesToken(),
                tokenExpired = true,
            )
        }

        return ChangesResult(upserted = upserted, deletedIds = deleted, nextToken = currentToken, tokenExpired = false)
    }

    /** Bounded read for backfill and for token-expiry fallback. One request per
     *  record type -- Health Connect's readRecords API is per-type, unlike the
     *  combined changes stream. */
    suspend fun readRange(start: Instant, end: Instant): List<SyncRecord> {
        val results = mutableListOf<SyncRecord>()
        for (type in RecordType.entries) {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type.kClass,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            response.records.forEach { record ->
                results += record.toSyncRecord(type.dbName)
            }
        }
        return results
    }
}
