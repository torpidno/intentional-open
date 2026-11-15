package com.pause.app.data.repository

import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.domain.model.TodayStats
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
     * Records that a pause surface was shown. Returns the row id used later to
     * attach the user's decision.
     */
    suspend fun recordAttempt(
        packageName: String,
        appLabel: String,
        mode: FrictionMode,
    ): Long = dao.insert(
        InterventionLogEntity(
            packageName = packageName,
            appLabel = appLabel,
            timestampMillis = clock.millis(),
            outcome = InterventionOutcome.PENDING,
            frictionMode = mode,
        ),
    )

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

    private fun todayRange(): Pair<Long, Long> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }
}
