package dev.krimsn.healthconnect.sync

import kotlinx.serialization.Serializable
import java.util.UUID

enum class SyncTrigger { PERIODIC, MANUAL, MANUAL_FULL, BACKFILL }
enum class SyncOutcome { OK, PARTIAL, ERROR }

/**
 * One sync attempt, stored locally (data/SyncHistoryStore.kt, source of truth for
 * the History screen) and mirrored to Supabase's `sync_runs` table (db/001_schema.sql)
 * for the MCP server. Instant fields are ISO-8601 strings rather than a custom
 * kotlinx.serialization Instant serializer -- one less moving part for a table
 * with exactly two datetime columns.
 */
@Serializable
data class SyncRun(
    val runId: String = UUID.randomUUID().toString(),
    val startedAt: String,
    val finishedAt: String? = null,
    val trigger: String,
    val outcome: String,
    val recordsRead: Int = 0,
    val recordsSent: Int = 0,
    val perType: Map<String, Int> = emptyMap(),
    val errorMessage: String? = null,
    val durationMs: Long? = null,
)
