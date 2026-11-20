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
import com.pause.app.domain.model.StatsRange
import com.pause.app.domain.model.TimeOfDayBucket
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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

    @Test
    fun `observeStats respects exact TODAY, SEVEN_DAYS, and THIRTY_DAYS boundaries`() = runTest {
        // Clock is fixed at 2026-05-14T12:00:00Z (UTC):
        // TODAY:       [2026-05-14T00:00:00Z, 2026-05-15T00:00:00Z)
        // SEVEN_DAYS:  [2026-05-08T00:00:00Z, 2026-05-15T00:00:00Z)
        // THIRTY_DAYS: [2026-04-15T00:00:00Z, 2026-05-15T00:00:00Z)
        insert(INSTAGRAM, "Instagram", at("2026-04-14T23:59:59"), InterventionOutcome.PROCEEDED)
        insert(INSTAGRAM, "Instagram", at("2026-04-15T00:00:00"), InterventionOutcome.WALKED_AWAY)
        insert(INSTAGRAM, "Instagram", at("2026-05-07T23:59:59"), InterventionOutcome.PROCEEDED)
        insert(TIKTOK, "TikTok", at("2026-05-08T00:00:00"), InterventionOutcome.WALKED_AWAY)
        insert(TIKTOK, "TikTok", at("2026-05-14T09:00:00"), InterventionOutcome.WALKED_AWAY)
        insert(INSTAGRAM, "Instagram", at("2026-05-14T23:59:59"), InterventionOutcome.PROCEEDED)
        // Tomorrow must never be included in any range.
        insert(INSTAGRAM, "Instagram", at("2026-05-15T00:00:00"), InterventionOutcome.PROCEEDED)

        val today = repository.observeStats(StatsRange.TODAY).first()
        assertEquals(StatsRange.TODAY, today.range)
        assertEquals(2, today.attempts)
        assertEquals(1, today.walkedAway)
        assertEquals(50, today.walkedAwayPercent)

        val sevenDays = repository.observeStats(StatsRange.SEVEN_DAYS).first()
        assertEquals(StatsRange.SEVEN_DAYS, sevenDays.range)
        assertEquals(3, sevenDays.attempts)
        assertEquals(2, sevenDays.walkedAway)
        assertEquals(66, sevenDays.walkedAwayPercent)
        assertEquals(TIKTOK, sevenDays.mostTriggeredPackage)
        assertEquals("TikTok", sevenDays.mostTriggeredLabel)
        assertEquals(2, sevenDays.mostTriggeredCount)

        val thirtyDays = repository.observeStats(StatsRange.THIRTY_DAYS).first()
        assertEquals(StatsRange.THIRTY_DAYS, thirtyDays.range)
        assertEquals(5, thirtyDays.attempts)
        assertEquals(3, thirtyDays.walkedAway)
        assertEquals(60, thirtyDays.walkedAwayPercent)
        assertEquals(INSTAGRAM, thirtyDays.mostTriggeredPackage)
        assertEquals(3, thirtyDays.mostTriggeredCount)
    }

    @Test
    fun `observeStats orders topApps by attempts descending then label ascending and caps at 5`() =
        runTest {
            val apps = listOf(
                "com.example.f" to "Zeta",
                "com.example.e" to "Epsilon",
                "com.example.d" to "Delta",
                "com.example.c" to "Charlie",
                "com.example.b" to "Beta",
                "com.example.a" to "Alpha",
            )
            // Alpha & Beta tied at 2 attempts; Charlie, Delta, Epsilon, Zeta at 1 attempt each.
            insert("com.example.b", "Beta", at("2026-05-14T08:00:00"), InterventionOutcome.WALKED_AWAY)
            insert("com.example.b", "Beta", at("2026-05-14T09:00:00"), InterventionOutcome.PROCEEDED)
            insert("com.example.a", "Alpha", at("2026-05-14T10:00:00"), InterventionOutcome.WALKED_AWAY)
            insert("com.example.a", "Alpha", at("2026-05-14T11:00:00"), InterventionOutcome.WALKED_AWAY)
            for ((pkg, label) in apps.take(4)) {
                insert(pkg, label, at("2026-05-14T13:00:00"), InterventionOutcome.PROCEEDED)
            }

            val stats = repository.observeStats(StatsRange.SEVEN_DAYS).first()

            assertEquals(5, stats.topApps.size)
            assertEquals(
                listOf("Alpha", "Beta", "Charlie", "Delta", "Epsilon"),
                stats.topApps.map { it.appLabel },
            )
            assertEquals(2, stats.topApps[0].attempts)
            assertEquals(2, stats.topApps[0].walkedAway)
            assertEquals(2, stats.topApps[1].attempts)
            assertEquals(1, stats.topApps[1].walkedAway)
            assertEquals("com.example.a", stats.mostTriggeredPackage)
            assertEquals("Alpha", stats.mostTriggeredLabel)
            assertEquals(2, stats.mostTriggeredCount)
        }

    @Test
    fun `observeStats buckets time of day in local timezone and identifies peak bucket`() =
        runTest {
            // Use America/New_York (EDT = UTC-4 in May) to verify local-zone bucketing,
            // NOT UTC hours!
            val nyZone = ZoneId.of("America/New_York")
            val nyClock = Clock.fixed(Instant.parse("2026-05-14T16:00:00Z"), nyZone)
            val nyRepo = InterceptionRepository(dao, nyClock)

            fun atNy(localDateTime: String): Long =
                LocalDateTime.parse(localDateTime).atZone(nyZone).toInstant().toEpochMilli()

            // Night: 00:00, 04:59, 22:00 (3 attempts, 1 walked away)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T00:00:00"), InterventionOutcome.WALKED_AWAY)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T04:59:59"), InterventionOutcome.PROCEEDED)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T22:00:00"), InterventionOutcome.PROCEEDED)
            // Morning: 05:00, 11:59 (2 attempts, 1 walked away)
            insert(TIKTOK, "TikTok", atNy("2026-05-14T05:00:00"), InterventionOutcome.WALKED_AWAY)
            insert(TIKTOK, "TikTok", atNy("2026-05-14T11:59:59"), InterventionOutcome.PROCEEDED)
            // Afternoon: 12:00, 16:59 (2 attempts, 0 walked away)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T12:00:00"), InterventionOutcome.PROCEEDED)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T16:59:59"), InterventionOutcome.PROCEEDED)
            // Evening: 17:00, 19:30, 20:15, 21:59 (4 attempts, 3 walked away) -> peak!
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T17:00:00"), InterventionOutcome.WALKED_AWAY)
            insert(INSTAGRAM, "Instagram", atNy("2026-05-14T19:30:00"), InterventionOutcome.WALKED_AWAY)
            insert(TIKTOK, "TikTok", atNy("2026-05-14T20:15:00"), InterventionOutcome.WALKED_AWAY)
            insert(TIKTOK, "TikTok", atNy("2026-05-14T21:59:59"), InterventionOutcome.PROCEEDED)

            val stats = nyRepo.observeStats(StatsRange.TODAY).first()
            val byBucket = stats.timeOfDay.associateBy { it.bucket }

            assertEquals(11, stats.attempts)
            assertEquals(5, stats.walkedAway)
            assertEquals(TimeOfDayBucket.EVENING, stats.peakBucket)

            assertEquals(2, byBucket.getValue(TimeOfDayBucket.MORNING).attempts)
            assertEquals(1, byBucket.getValue(TimeOfDayBucket.MORNING).walkedAway)
            assertEquals(2, byBucket.getValue(TimeOfDayBucket.AFTERNOON).attempts)
            assertEquals(0, byBucket.getValue(TimeOfDayBucket.AFTERNOON).walkedAway)
            assertEquals(4, byBucket.getValue(TimeOfDayBucket.EVENING).attempts)
            assertEquals(3, byBucket.getValue(TimeOfDayBucket.EVENING).walkedAway)
            assertEquals(3, byBucket.getValue(TimeOfDayBucket.NIGHT).attempts)
            assertEquals(1, byBucket.getValue(TimeOfDayBucket.NIGHT).walkedAway)
        }

    @Test
    fun `observeStats on empty database returns zeroed buckets and null peak`() = runTest {
        val stats = repository.observeStats(StatsRange.THIRTY_DAYS).first()

        assertEquals(StatsRange.THIRTY_DAYS, stats.range)
        assertEquals(0, stats.attempts)
        assertEquals(0, stats.walkedAway)
        assertEquals(0, stats.walkedAwayPercent)
        assertEquals(emptyList<Any>(), stats.topApps)
        assertNull(stats.mostTriggeredPackage)
        assertNull(stats.mostTriggeredLabel)
        assertEquals(0, stats.mostTriggeredCount)
        assertNull(stats.peakBucket)
        assertEquals(TimeOfDayBucket.entries, stats.timeOfDay.map { it.bucket })
        assertEquals(listOf(0, 0, 0, 0), stats.timeOfDay.map { it.attempts })
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
