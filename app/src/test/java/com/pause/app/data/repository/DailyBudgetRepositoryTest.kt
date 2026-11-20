package com.pause.app.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.DailyUsageDao
import com.pause.app.data.local.DailyUsageEntity
import com.pause.app.data.local.PauseDatabase
import com.pause.app.service.ForegroundUsageReader
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DailyBudgetRepositoryTest {

    private lateinit var database: PauseDatabase
    private lateinit var dao: DailyUsageDao
    private lateinit var clock: MutableClock
    private lateinit var fakeReader: FakeForegroundUsageReader
    private lateinit var repository: DailyBudgetRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PauseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dailyUsageDao()
        clock = MutableClock(Instant.parse("2026-05-14T12:00:00Z"), ZoneOffset.UTC)
        fakeReader = FakeForegroundUsageReader()
        repository = DailyBudgetRepository(
            dao = dao,
            usageReader = fakeReader,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `checkBudgetSync computes remaining, exceeded, and disabled limits accurately`() {
        fakeReader.response = mapOf(INSTAGRAM to 15 * 60_000L)

        val under = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 20)
        assertFalse(under.isExceeded)
        assertEquals(15 * 60_000L, under.usedMillis)
        assertEquals(15, under.usedMinutes)
        assertEquals(5 * 60_000L, under.remainingMillis)

        val exact = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 15)
        assertTrue(exact.isExceeded)
        assertEquals(0L, exact.remainingMillis)

        val over = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 10)
        assertTrue(over.isExceeded)
        assertEquals(0L, over.remainingMillis)

        val unlimited = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 0)
        assertFalse(unlimited.isExceeded)
    }

    @Test
    fun `checkAndPersistBudget writes snapshot to Room and observeTodayUsage emits it`() = runTest {
        fakeReader.response = mapOf(INSTAGRAM to 12 * 60_000L)

        val status = repository.checkAndPersistBudget(INSTAGRAM, limitMinutes = 30)

        assertEquals(12 * 60_000L, status.usedMillis)
        assertEquals(18 * 60_000L, status.remainingMillis)
        assertFalse(status.isExceeded)
        assertEquals(12 * 60_000L, dao.getUsedMillis("2026-05-14", INSTAGRAM))
        assertEquals(mapOf(INSTAGRAM to 12 * 60_000L), repository.observeTodayUsage().first())
    }

    @Test
    fun `syncUsageSnapshot persists all requested packages and prunes rows older than 30 days`() =
        runTest {
            // Seed a row older than 30 days (should be pruned) and a row exactly 30 days ago (kept).
            dao.upsertAll(
                listOf(
                    DailyUsageEntity(
                        localDate = "2026-04-13",
                        packageName = INSTAGRAM,
                        usedMillis = 999_000L,
                        updatedAtMillis = 1L,
                    ),
                    DailyUsageEntity(
                        localDate = "2026-04-14",
                        packageName = INSTAGRAM,
                        usedMillis = 888_000L,
                        updatedAtMillis = 2L,
                    ),
                ),
            )
            fakeReader.response = mapOf(INSTAGRAM to 18 * 60_000L)

            val result = repository.syncUsageSnapshot(setOf(INSTAGRAM, TIKTOK))

            assertEquals(mapOf(INSTAGRAM to 18 * 60_000L, TIKTOK to 0L), result)
            assertEquals(18 * 60_000L, dao.getUsedMillis("2026-05-14", INSTAGRAM))
            assertEquals(0L, dao.getUsedMillis("2026-05-14", TIKTOK))
            assertNull(dao.getUsedMillis("2026-04-13", INSTAGRAM))
            assertEquals(888_000L, dao.getUsedMillis("2026-04-14", INSTAGRAM))
            assertEquals(
                mapOf(INSTAGRAM to 18 * 60_000L, TIKTOK to 0L),
                repository.observeTodayUsage().first(),
            )
        }

    @Test
    fun `budget resets cleanly at local midnight when clock advances to next day`() = runTest {
        fakeReader.response = mapOf(INSTAGRAM to 30 * 60_000L)
        val dayOne = repository.checkAndPersistBudget(INSTAGRAM, limitMinutes = 30)
        assertTrue(dayOne.isExceeded)

        // Advance clock past local midnight to 2026-05-15T00:01:00Z.
        clock.setInstant(Instant.parse("2026-05-15T00:01:00Z"))

        // Even if Usage Access is unavailable on the new day, yesterday's cache must NOT carry over.
        fakeReader.response = null
        val fallbackNextDay = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 30)
        assertEquals(0L, fallbackNextDay.usedMillis)
        assertFalse(fallbackNextDay.isExceeded)

        // And when Usage Access reports fresh usage for the new day, Room gets a separate row.
        fakeReader.response = mapOf(INSTAGRAM to 2 * 60_000L)
        val freshNextDay = repository.checkAndPersistBudget(INSTAGRAM, limitMinutes = 30)
        assertEquals(2 * 60_000L, freshNextDay.usedMillis)
        assertFalse(freshNextDay.isExceeded)
        assertEquals(30 * 60_000L, dao.getUsedMillis("2026-05-14", INSTAGRAM))
        assertEquals(2 * 60_000L, dao.getUsedMillis("2026-05-15", INSTAGRAM))
        assertEquals(
            mapOf(INSTAGRAM to 2 * 60_000L),
            repository.observeTodayUsage(LocalDate.of(2026, 5, 15)).first(),
        )
    }

    @Test
    fun `fallback uses today cache and persisted Room snapshot when Usage Access is unavailable`() =
        runTest {
            // 1. Fresh read populates cache and Room.
            fakeReader.response = mapOf(INSTAGRAM to 25 * 60_000L)
            repository.checkAndPersistBudget(INSTAGRAM, limitMinutes = 20)

            // 2. Permission revoked: checkBudgetSync falls back to today's in-memory cache.
            fakeReader.response = null
            val cachedStatus = repository.checkBudgetSync(INSTAGRAM, limitMinutes = 20)
            assertEquals(25 * 60_000L, cachedStatus.usedMillis)
            assertTrue(cachedStatus.isExceeded)

            // 3. Process restart (fresh repo, cold in-memory cache) with Usage Access unavailable:
            //    checkBudgetSync returns 0L without blocking, while checkAndPersistBudget and
            //    syncUsageSnapshot recover the Room snapshot without clobbering it with 0.
            val coldRepo = DailyBudgetRepository(
                dao = dao,
                usageReader = fakeReader,
                clock = clock,
                ioDispatcher = UnconfinedTestDispatcher(),
            )
            assertEquals(0L, coldRepo.checkBudgetSync(INSTAGRAM, limitMinutes = 20).usedMillis)

            val recovered = coldRepo.checkAndPersistBudget(INSTAGRAM, limitMinutes = 20)
            assertEquals(25 * 60_000L, recovered.usedMillis)
            assertTrue(recovered.isExceeded)
            assertEquals(25 * 60_000L, dao.getUsedMillis("2026-05-14", INSTAGRAM))
            // Cache was warmed by the Room fallback.
            assertEquals(25 * 60_000L, coldRepo.checkBudgetSync(INSTAGRAM, limitMinutes = 20).usedMillis)

            val synced = coldRepo.syncUsageSnapshot(setOf(INSTAGRAM))
            assertEquals(mapOf(INSTAGRAM to 25 * 60_000L), synced)
        }

    @Test
    fun `uses clock local timezone for day start and localDate key`() = runTest {
        // 2026-05-14T02:30:00Z is still 2026-05-13 22:30 in America/New_York (UTC-4).
        val nyZone = ZoneId.of("America/New_York")
        val nyClock = MutableClock(Instant.parse("2026-05-14T02:30:00Z"), nyZone)
        val nyRepo = DailyBudgetRepository(
            dao = dao,
            usageReader = fakeReader,
            clock = nyClock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        fakeReader.response = mapOf(INSTAGRAM to 5 * 60_000L)

        nyRepo.checkAndPersistBudget(INSTAGRAM, limitMinutes = 10)

        val expectedStartOfDay = LocalDate.of(2026, 5, 13)
            .atStartOfDay(nyZone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStartOfDay, fakeReader.lastStartOfDayMillis)
        assertEquals(nyClock.millis(), fakeReader.lastNowMillis)
        assertEquals(5 * 60_000L, dao.getUsedMillis("2026-05-13", INSTAGRAM))
        assertNull(dao.getUsedMillis("2026-05-14", INSTAGRAM))
    }

    private class FakeForegroundUsageReader : ForegroundUsageReader {
        var response: Map<String, Long>? = emptyMap()
        var lastStartOfDayMillis: Long? = null
        var lastNowMillis: Long? = null

        override fun readForegroundMillis(
            packages: Set<String>,
            startOfDayMillis: Long,
            nowMillis: Long,
        ): Map<String, Long>? {
            lastStartOfDayMillis = startOfDayMillis
            lastNowMillis = nowMillis
            return response?.filterKeys { it in packages }
        }
    }

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        fun setInstant(instant: Instant) {
            currentInstant = instant
        }

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
    }
}
