package com.pause.app.data.repository

import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.HistoryArchiveEntry
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.domain.model.InterventionRecord
import com.pause.app.domain.model.RangeStats
import com.pause.app.domain.model.StatsRange
import com.pause.app.domain.model.TodayStats
import com.pause.app.domain.model.aggregateRangeStats
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Append-only history of intercepted launches plus the aggregates used by the
 * home screen. All data stays in the local Room database.
 */
@Singleton
class InterceptionRepository @Inject constructor(
    private val dao: InterventionLogDao,
    private val clock: Clock,
) {

    /**
     * Attempts, wins and the most triggered app for the current local day.
     *
     * The range is captured when this Flow is created; the home ViewModel
     * re-creates it when the screen is opened, which is enough for a daily
     * dashboard without a midnight timer.
     */
    fun observeTodayStats(): Flow<TodayStats> {
        val (startMillis, endMillis) = todayRange()
        return combine(
            dao.observeAttemptCount(startMillis, endMillis),
            dao.observeWalkedAwayCount(startMillis, endMillis),
            dao.observeMostTriggered(startMillis, endMillis),
        ) { attempts, walkedAway, mostTriggered ->
            TodayStats(
                attempts = attempts,
                walkedAway = walkedAway,
                mostTriggeredPackage = mostTriggered?.packageName,
                mostTriggeredLabel = mostTriggered?.appLabel,
                mostTriggeredCount = mostTriggered?.count ?: 0,
            )
        }
    }

    /**
     * Aggregated attempts, walk-aways, top apps, and time-of-day distribution
     * over [range] ending at the end of the current local day.
     */
    fun observeStats(range: StatsRange): Flow<RangeStats> {
        val (startMillis, endMillis) = rangeBounds(range)
        val zone = clock.zone
        return dao.observeInRange(startMillis, endMillis).map { rows ->
            aggregateRangeStats(
                records = rows.map { it.toRecord() },
                range = range,
                zone = zone,
            )
        }
    }

    /**
     * Records that a pause surface was shown. Returns the row id used later to
     * attach the user's decision.
     */
    suspend fun recordAttempt(
        packageName: String,
        appLabel: String,
        mode: FrictionMode,
    ): Long = recordAttempt(packageName, appLabel, listOf(mode))

    /** Records a multi-step pause; the full sequence is preserved for history. */
    suspend fun recordAttempt(
        packageName: String,
        appLabel: String,
        modes: List<FrictionMode>,
    ): Long {
        val normalized = modes.distinct().ifEmpty { listOf(FrictionMode.BREATHING) }
        return dao.insert(
            InterventionLogEntity(
                packageName = packageName,
                appLabel = appLabel,
                timestampMillis = clock.millis(),
                outcome = InterventionOutcome.PENDING,
                frictionMode = normalized.first(),
                frictionModeSequence = normalized.joinToString(",") { it.name },
            ),
        )
    }

    /** Newest first; the history screen filters and formats the small result. */
    fun observeHistory(limit: Int = 200): Flow<List<InterventionRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { row -> row.toRecord() } }

    /** Attaches an outcome to a previously recorded attempt. */
    suspend fun completeAttempt(
        id: Long,
        outcome: InterventionOutcome,
        intention: String?,
        sessionLimitMinutes: Int?,
    ) {
        if (id <= 0L) return
        dao.complete(
            id = id,
            outcome = outcome,
            intention = intention,
            sessionLimitMinutes = sessionLimitMinutes,
        )
    }

    /** Closes pause rows that were left pending by an interrupted process. */
    suspend fun completeStalePendingAttempts() {
        dao.completeAllPending(InterventionOutcome.WALKED_AWAY)
    }

    /** Closes pause rows recorded before [beforeMillis] that were left pending by an interrupted process. */
    suspend fun completeStalePendingAttempts(beforeMillis: Long) {
        dao.completeAllPendingBefore(InterventionOutcome.WALKED_AWAY, beforeMillis)
    }

    /** Every record, oldest first, for a local archive. */
    suspend fun exportHistory(): List<HistoryArchiveEntry> =
        dao.getAll().map { it.toArchiveEntry() }

    /**
     * Inserts archive rows with new local IDs, skipping entries that match an
     * existing row or repeat within the import batch. Returns how many new rows
     * were added.
     */
    suspend fun importHistory(entries: List<HistoryArchiveEntry>): Int {
        if (entries.isEmpty()) return 0
        val seen = dao.getAll().mapTo(HashSet()) { it.toArchiveEntry().dedupKey() }
        val fresh = entries.mapNotNull { entry ->
            if (seen.add(entry.dedupKey())) entry.toEntity() else null
        }
        if (fresh.isEmpty()) return 0
        dao.insertAll(fresh)
        return fresh.size
    }

    suspend fun deleteHistoryBefore(cutoffMillis: Long) {
        dao.deleteOlderThan(cutoffMillis)
    }

    suspend fun clearHistory() {
        dao.deleteAll()
    }

    private fun InterventionLogEntity.toArchiveEntry(): HistoryArchiveEntry {
        val record = toRecord()
        return HistoryArchiveEntry(
            timestampMillis = record.timestampMillis,
            packageName = record.packageName,
            appLabel = record.appLabel,
            outcome = record.outcome,
            frictionModes = record.frictionModes,
            intention = record.intention,
            sessionLimitMinutes = record.sessionLimitMinutes,
        )
    }

    private fun HistoryArchiveEntry.toEntity(): InterventionLogEntity {
        val normalized = frictionModes.distinct().ifEmpty { listOf(FrictionMode.BREATHING) }
        return InterventionLogEntity(
            packageName = packageName,
            appLabel = appLabel,
            timestampMillis = timestampMillis,
            outcome = outcome,
            frictionMode = normalized.first(),
            frictionModeSequence = normalized.joinToString(",") { it.name },
            intention = intention,
            sessionLimitMinutes = sessionLimitMinutes,
        )
    }

    private fun InterventionLogEntity.toRecord(): InterventionRecord {
        val modes = frictionModeSequence
            ?.split(",")
            ?.mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(frictionMode)
        return InterventionRecord(
            id = id,
            packageName = packageName,
            appLabel = appLabel,
            timestampMillis = timestampMillis,
            outcome = outcome,
            frictionModes = modes,
            intention = intention,
            sessionLimitMinutes = sessionLimitMinutes,
        )
    }

    private fun HistoryArchiveEntry.dedupKey(): HistoryDedupKey = HistoryDedupKey(
        timestampMillis = timestampMillis,
        packageName = packageName,
        outcome = outcome,
        frictionModes = frictionModes.distinct().ifEmpty { listOf(FrictionMode.BREATHING) },
        intention = intention,
        sessionLimitMinutes = sessionLimitMinutes,
    )

    private fun todayRange(): Pair<Long, Long> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun rangeBounds(range: StatsRange): Pair<Long, Long> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = today.minusDays((range.days - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val end = today.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return start to end
    }
}

private data class HistoryDedupKey(
    val timestampMillis: Long,
    val packageName: String,
    val outcome: InterventionOutcome,
    val frictionModes: List<FrictionMode>,
    val intention: String?,
    val sessionLimitMinutes: Int?,
)
