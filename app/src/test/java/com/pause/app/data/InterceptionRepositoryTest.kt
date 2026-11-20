package com.pause.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.data.local.PauseDatabase
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.HistoryArchiveEntry
import com.pause.app.domain.model.InterventionOutcome
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end tests for the Room aggregates behind the dashboard.
 *
 * Robolectric gives a real in-memory SQLite database, so the SQL itself is
 * exercised (including the local-day boundaries) rather than a fake DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InterceptionRepositoryTest {

    private lateinit var database: PauseDatabase
    private lateinit var dao: InterventionLogDao
    private lateinit var repository: InterceptionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PauseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.interventionLogDao()

        // Fixed at midday UTC on 14 May 2026 so "today" is deterministic.
        val clock = Clock.fixed(Instant.parse("2026-05-14T12:00:00Z"), ZoneOffset.UTC)
        repository = InterceptionRepository(dao, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `today stats ignore entries outside the local day`() = runTest {
        insert(INSTAGRAM, "Instagram", at("2026-05-14T08:00:00"), InterventionOutcome.WALKED_AWAY)
        insert(INSTAGRAM, "Instagram", at("2026-05-14T09:00:00"), InterventionOutcome.PENDING)
        insert(TIKTOK, "TikTok", at("2026-05-14T23:59:59"), InterventionOutcome.PROCEEDED)
        // The day before and the day after must not leak into today's numbers.
        insert(INSTAGRAM, "Instagram", at("2026-05-13T23:59:59"), InterventionOutcome.PROCEEDED)
        insert(INSTAGRAM, "Instagram", at("2026-05-15T00:00:00"), InterventionOutcome.PROCEEDED)

        val stats = repository.observeTodayStats().first()

        assertEquals(3, stats.attempts)
        assertEquals(1, stats.walkedAway)
    }

    @Test
    fun `most triggered app counts only today's attempts`() = runTest {
        // A heavy yesterday must not outweigh today's leader.
        repeat(5) { index ->
            insert(
                INSTAGRAM,
                "Instagram",
                at("2026-05-13T0${index}:00:00"),
                InterventionOutcome.PROCEEDED,
            )
        }
        insert(INSTAGRAM, "Instagram", at("2026-05-14T08:00:00"), InterventionOutcome.PROCEEDED)
        insert(INSTAGRAM, "Instagram", at("2026-05-14T09:00:00"), InterventionOutcome.WALKED_AWAY)
        insert(TIKTOK, "TikTok", at("2026-05-14T10:00:00"), InterventionOutcome.PROCEEDED)

        val stats = repository.observeTodayStats().first()

        assertEquals(INSTAGRAM, stats.mostTriggeredPackage)
        assertEquals("Instagram", stats.mostTriggeredLabel)
        assertEquals(2, stats.mostTriggeredCount)
    }

    @Test
    fun `recorded attempts start pending and can be completed`() = runTest {
        val attemptId = repository.recordAttempt(INSTAGRAM, "Instagram", FrictionMode.INTENTION)

        val pendingStats = repository.observeTodayStats().first()
        assertEquals(1, pendingStats.attempts)
        assertEquals(0, pendingStats.walkedAway)

        repository.completeAttempt(
            id = attemptId,
            outcome = InterventionOutcome.WALKED_AWAY,
            intention = "Check a message",
            sessionLimitMinutes = null,
        )

        val completedStats = repository.observeTodayStats().first()
        assertEquals(1, completedStats.attempts)
        assertEquals(1, completedStats.walkedAway)
        assertEquals("Instagram", completedStats.mostTriggeredLabel)
    }

    @Test
    fun `history preserves the full friction sequence`() = runTest {
        val attemptId = repository.recordAttempt(
            INSTAGRAM,
            "Instagram",
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
        )
        repository.completeAttempt(
            id = attemptId,
            outcome = InterventionOutcome.PROCEEDED,
            intention = "Check a message",
            sessionLimitMinutes = 15,
        )

        val entry = repository.observeHistory().first().single()

        assertEquals(listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING), entry.frictionModes)
        assertEquals(InterventionOutcome.PROCEEDED, entry.outcome)
        assertEquals("Check a message", entry.intention)
        assertEquals(15, entry.sessionLimitMinutes)
    }

    @Test
    fun `archive exports every row and import round-trips it`() = runTest {
        dao.insert(
            InterventionLogEntity(
                packageName = INSTAGRAM,
                appLabel = "Instagram",
                timestampMillis = 1_000L,
                outcome = InterventionOutcome.WALKED_AWAY,
                frictionMode = FrictionMode.INTENTION,
                frictionModeSequence = "INTENTION",
            ),
        )
        dao.insert(
            InterventionLogEntity(
                packageName = TIKTOK,
                appLabel = "TikTok",
                timestampMillis = 2_000L,
                outcome = InterventionOutcome.PROCEEDED,
                frictionMode = FrictionMode.TIME_LIMIT,
                frictionModeSequence = "TIME_LIMIT,BREATHING",
                intention = "Watch one video",
                sessionLimitMinutes = 15,
            ),
        )

        val exported = repository.exportHistory()

        assertEquals(2, exported.size)
        assertEquals(listOf(INSTAGRAM, TIKTOK), exported.map { it.packageName })
        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            exported[1].frictionModes,
        )

        repository.clearHistory()
        assertEquals(2, repository.importHistory(exported))
        assertEquals(exported, repository.exportHistory())
    }

    @Test
    fun `re-importing the same archive inserts zero duplicates`() = runTest {
        dao.insert(
            InterventionLogEntity(
                packageName = INSTAGRAM,
                appLabel = "Instagram",
                timestampMillis = 1_000L,
                outcome = InterventionOutcome.WALKED_AWAY,
                frictionMode = FrictionMode.INTENTION,
                frictionModeSequence = "INTENTION",
            ),
        )
        dao.insert(
            InterventionLogEntity(
                packageName = TIKTOK,
                appLabel = "TikTok",
                timestampMillis = 2_000L,
                outcome = InterventionOutcome.PROCEEDED,
                frictionMode = FrictionMode.TIME_LIMIT,
                frictionModeSequence = "TIME_LIMIT,BREATHING",
                intention = "Watch one video",
                sessionLimitMinutes = 15,
            ),
        )

        val exported = repository.exportHistory()

        assertEquals(0, repository.importHistory(exported))
        assertEquals(2, repository.exportHistory().size)

        val novelEntry = HistoryArchiveEntry(
            timestampMillis = 3_000L,
            packageName = INSTAGRAM,
            appLabel = "Instagram",
            outcome = InterventionOutcome.PROCEEDED,
            frictionModes = listOf(FrictionMode.INTENTION, FrictionMode.BREATHING),
            intention = "Reply to message",
            sessionLimitMinutes = null,
        )
        // Mixed batch with existing rows and in-batch duplicates only inserts unique new rows.
        assertEquals(
            1,
            repository.importHistory(exported + listOf(novelEntry, novelEntry)),
        )
        assertEquals(exported + novelEntry, repository.exportHistory())
    }

    @Test
    fun `completeStalePendingAttempts with cutoff only closes earlier pending rows`() = runTest {
        insert(INSTAGRAM, "Instagram", 999L, InterventionOutcome.PENDING)
        insert(TIKTOK, "TikTok", 1_000L, InterventionOutcome.PENDING)
        insert(INSTAGRAM, "Instagram", 1_500L, InterventionOutcome.PENDING)

        repository.completeStalePendingAttempts(beforeMillis = 1_000L)

        val byTimestamp = repository.exportHistory().associateBy { it.timestampMillis }
        assertEquals(InterventionOutcome.WALKED_AWAY, byTimestamp.getValue(999L).outcome)
        assertEquals(InterventionOutcome.PENDING, byTimestamp.getValue(1_000L).outcome)
        assertEquals(InterventionOutcome.PENDING, byTimestamp.getValue(1_500L).outcome)
    }

    @Test
    fun `retention deletion and clear history remove old rows`() = runTest {
        insert(INSTAGRAM, "Instagram", 1_000L, InterventionOutcome.WALKED_AWAY)
        insert(TIKTOK, "TikTok", 2_000L, InterventionOutcome.PROCEEDED)

        repository.deleteHistoryBefore(cutoffMillis = 1_500L)

        assertEquals(listOf(TIKTOK), repository.exportHistory().map { it.packageName })
        repository.clearHistory()
        assertEquals(0, repository.exportHistory().size)
    }

    @Test
    fun `empty database reports zero and no most triggered app`() = runTest {
        val stats = repository.observeTodayStats().first()

        assertEquals(0, stats.attempts)
        assertEquals(0, stats.walkedAway)
        assertNull(stats.mostTriggeredPackage)
        assertEquals(0, stats.mostTriggeredCount)
    }

    private suspend fun insert(
        packageName: String,
        appLabel: String,
        timestampMillis: Long,
        outcome: InterventionOutcome,
    ) {
        dao.insert(
            InterventionLogEntity(
                packageName = packageName,
                appLabel = appLabel,
                timestampMillis = timestampMillis,
                outcome = outcome,
                frictionMode = FrictionMode.BREATHING,
            ),
        )
    }

    private fun at(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
    }
}
