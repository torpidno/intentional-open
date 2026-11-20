package com.pause.app.ui.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.data.local.PauseDatabase
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.DailyBudgetRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StatsRange
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.domain.model.TimeOfDayBucket
import com.pause.app.service.ForegroundUsageReader
import com.pause.app.util.PermissionChecker
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var database: PauseDatabase
    private lateinit var logDao: InterventionLogDao
    private lateinit var clock: MutableClock
    private lateinit var fakeUsageReader: FakeForegroundUsageReader
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val directExecutor = Executor { command -> command.run() }
        database = Room.inMemoryDatabaseBuilder(
            context as Context,
            PauseDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        logDao = database.interventionLogDao()
        clock = MutableClock(Instant.parse("2026-05-14T12:00:00Z"), ZoneOffset.UTC)
        fakeUsageReader = FakeForegroundUsageReader()
        store = ViewModelStore()
    }

    @After
    fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `switching StatsRange updates rangeStats across today, 7 days, and 30 days`() =
        runTest(testDispatcher) {
            // Seed logs across 1d, 7d, and 30d windows relative to 2026-05-14T12:00:00Z.
            insertLog(INSTAGRAM, "Instagram", atUtc("2026-04-24T10:00:00"), InterventionOutcome.PROCEEDED)
            insertLog(TIKTOK, "TikTok", atUtc("2026-05-11T19:00:00"), InterventionOutcome.WALKED_AWAY)
            insertLog(TIKTOK, "TikTok", atUtc("2026-05-11T20:00:00"), InterventionOutcome.WALKED_AWAY)
            insertLog(INSTAGRAM, "Instagram", atUtc("2026-05-14T08:00:00"), InterventionOutcome.WALKED_AWAY)

            val (_, viewModel) = createViewModel()
            collectUiState(viewModel)

            val todayState = viewModel.uiState.first {
                it.statsRange == StatsRange.TODAY && it.rangeStats.attempts == 1
            }
            assertEquals(1, todayState.attemptsToday)
            assertEquals(1, todayState.walkedAwayToday)
            assertEquals("Instagram", todayState.mostTriggeredLabel)
            assertEquals(1, todayState.mostTriggeredCount)
            assertEquals(TimeOfDayBucket.MORNING, todayState.rangeStats.peakBucket)

            viewModel.setStatsRange(StatsRange.SEVEN_DAYS)
            val sevenDayState = viewModel.uiState.first {
                it.statsRange == StatsRange.SEVEN_DAYS && it.rangeStats.attempts == 3
            }
            assertEquals(3, sevenDayState.rangeStats.walkedAway)
            assertEquals(100, sevenDayState.rangeStats.walkedAwayPercent)
            assertEquals(
                listOf("TikTok", "Instagram"),
                sevenDayState.rangeStats.topApps.map { it.appLabel },
            )
            assertEquals(TimeOfDayBucket.EVENING, sevenDayState.rangeStats.peakBucket)

            viewModel.setStatsRange(StatsRange.THIRTY_DAYS)
            val thirtyDayState = viewModel.uiState.first {
                it.statsRange == StatsRange.THIRTY_DAYS && it.rangeStats.attempts == 4
            }
            assertEquals(3, thirtyDayState.rangeStats.walkedAway)
            assertEquals(75, thirtyDayState.rangeStats.walkedAwayPercent)
            assertEquals(2, thirtyDayState.rangeStats.topApps.size)
        }

    @Test
    fun `selectProfile switches active profile atomically and reflects scheduled auto-switch`() =
        runTest(testDispatcher) {
            // Thursday 21:00 UTC falls inside eveningPreset (20:00-23:30) and outside workPreset (09:00-17:00).
            clock.setInstant(Instant.parse("2026-05-14T21:00:00Z"))
            val (settingsRepository, viewModel) = createViewModel()
            val work = PauseProfile.workPreset(monitoredPackages = setOf(INSTAGRAM))
            val evening = PauseProfile.eveningPreset(monitoredPackages = setOf(TIKTOK))
            settingsRepository.saveProfile(work, activate = true)
            settingsRepository.saveProfile(evening, activate = false)
            collectUiState(viewModel)

            val workState = viewModel.uiState.first { it.activeProfileId == "work" }
            assertEquals("Work", workState.effectiveProfileName)
            assertFalse(workState.isScheduledProfileActive)
            assertEquals(listOf(INSTAGRAM), workState.monitoredApps.map { it.packageName })
            assertEquals(
                listOf(FrictionMode.BREATHING, FrictionMode.INTENTION),
                workState.defaultModes,
            )

            viewModel.selectProfile("evening")
            val eveningState = viewModel.uiState.first { it.activeProfileId == "evening" }
            assertEquals("Evening", eveningState.effectiveProfileName)
            assertEquals(listOf(TIKTOK), eveningState.monitoredApps.map { it.packageName })
            assertEquals(
                listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
                eveningState.defaultModes,
            )

            viewModel.selectProfile(null)
            val unlinkedState = viewModel.uiState.first { it.activeProfileId == null }
            assertNull(unlinkedState.effectiveProfileName)
            assertEquals(listOf(TIKTOK), unlinkedState.monitoredApps.map { it.packageName })

            // Turning on auto-switch resolves the scheduled Evening profile at 21:00 UTC.
            settingsRepository.setAutoSwitchProfiles(true)
            val autoEveningState = viewModel.uiState.first {
                it.autoSwitchProfiles && it.isScheduledProfileActive
            }
            assertEquals("Evening", autoEveningState.effectiveProfileName)

            // Moving to Friday 10:00 UTC and resuming resolves the scheduled Work profile.
            clock.setInstant(Instant.parse("2026-05-15T10:00:00Z"))
            viewModel.onResume()
            val autoWorkState = viewModel.uiState.first { it.effectiveProfileName == "Work" }
            assertTrue(autoWorkState.isScheduledProfileActive)
        }

    @Test
    fun `setDailyLimitMinutes updates monitored app limit and immediately syncs today usage`() =
        runTest(testDispatcher) {
            fakeUsageReader.response = mapOf(
                INSTAGRAM to 22 * 60_000L,
                TIKTOK to 45 * 60_000L,
            )
            val (settingsRepository, viewModel) = createViewModel()
            settingsRepository.setMonitored(INSTAGRAM, monitored = true)
            collectUiState(viewModel)

            val initialApp = viewModel.uiState.first { state ->
                state.monitoredApps.any { it.packageName == INSTAGRAM }
            }.monitoredApps.single { it.packageName == INSTAGRAM }
            assertNull(initialApp.dailyLimitMinutes)
            assertEquals(0, initialApp.usedTodayMinutes)

            viewModel.setDailyLimitMinutes(INSTAGRAM, 30)
            val limitedApp = viewModel.uiState.first { state ->
                val app = state.monitoredApps.firstOrNull { it.packageName == INSTAGRAM }
                app?.dailyLimitMinutes == 30 && app.usedTodayMinutes == 22
            }.monitoredApps.single { it.packageName == INSTAGRAM }
            assertEquals(30, limitedApp.dailyLimitMinutes)
            assertEquals(22, limitedApp.usedTodayMinutes)
            assertTrue(INSTAGRAM in fakeUsageReader.requestedPackages)

            // Returning from the app updates today's usage snapshot on resume.
            fakeUsageReader.response = mapOf(INSTAGRAM to 35 * 60_000L)
            viewModel.onResume()
            val exceededApp = viewModel.uiState.first { state ->
                state.monitoredApps.firstOrNull { it.packageName == INSTAGRAM }
                    ?.usedTodayMinutes == 35
            }.monitoredApps.single { it.packageName == INSTAGRAM }
            assertEquals(35, exceededApp.usedTodayMinutes)

            viewModel.setDailyLimitMinutes(INSTAGRAM, null)
            val clearedApp = viewModel.uiState.first { state ->
                state.monitoredApps.firstOrNull { it.packageName == INSTAGRAM }
                    ?.dailyLimitMinutes == null
            }.monitoredApps.single { it.packageName == INSTAGRAM }
            assertNull(clearedApp.dailyLimitMinutes)
        }

    @Test
    fun `onResume rolls over today stats and daily usage across midnight`() =
        runTest(testDispatcher) {
            clock.setInstant(Instant.parse("2026-05-14T23:55:00Z"))
            fakeUsageReader.response = mapOf(INSTAGRAM to 25 * 60_000L)
            insertLog(INSTAGRAM, "Instagram", atUtc("2026-05-14T09:00:00"), InterventionOutcome.WALKED_AWAY)
            insertLog(INSTAGRAM, "Instagram", atUtc("2026-05-14T23:30:00"), InterventionOutcome.PROCEEDED)

            val (settingsRepository, viewModel) = createViewModel()
            settingsRepository.setMonitored(INSTAGRAM, monitored = true)
            settingsRepository.setDailyLimitMinutes(INSTAGRAM, 30)
            collectUiState(viewModel)

            val dayOne = viewModel.uiState.first { state ->
                state.rangeStats.attempts == 2 &&
                    state.monitoredApps.firstOrNull { it.packageName == INSTAGRAM }
                        ?.usedTodayMinutes == 25
            }
            assertEquals(2, dayOne.attemptsToday)
            assertEquals(1, dayOne.walkedAwayToday)

            // Advance past local midnight into 2026-05-15 and resume.
            clock.setInstant(Instant.parse("2026-05-15T00:05:00Z"))
            fakeUsageReader.response = mapOf(INSTAGRAM to 3 * 60_000L)
            insertLog(TIKTOK, "TikTok", atUtc("2026-05-15T00:02:00"), InterventionOutcome.WALKED_AWAY)
            viewModel.onResume()

            val dayTwo = viewModel.uiState.first { state ->
                state.rangeStats.attempts == 1 &&
                    state.mostTriggeredLabel == "TikTok" &&
                    state.monitoredApps.firstOrNull { it.packageName == INSTAGRAM }
                        ?.usedTodayMinutes == 3
            }
            assertEquals(1, dayTwo.attemptsToday)
            assertEquals(1, dayTwo.walkedAwayToday)
            assertEquals("TikTok", dayTwo.mostTriggeredLabel)
            assertEquals(
                3,
                dayTwo.monitoredApps.single { it.packageName == INSTAGRAM }.usedTodayMinutes,
            )
        }

    @Test
    fun `strict mode configuration propagates into HomeUiState`() = runTest(testDispatcher) {
        val (settingsRepository, viewModel) = createViewModel()
        collectUiState(viewModel)

        settingsRepository.setStrictModeEnabled(true)
        settingsRepository.setStrictModeChallenge(StrictModeChallenge.PHRASE)
        settingsRepository.setStrictModeDelaySeconds(45)
        settingsRepository.setStrictModePhrase("Stay intentional")

        val state = viewModel.uiState.first {
            it.strictModeEnabled &&
                it.strictModeChallenge == StrictModeChallenge.PHRASE &&
                it.strictModeDelaySeconds == 45 &&
                it.strictModePhrase == "Stay intentional"
        }
        assertTrue(state.strictModeEnabled)
        assertEquals(StrictModeChallenge.PHRASE, state.strictModeChallenge)
        assertEquals(45, state.strictModeDelaySeconds)
        assertEquals("Stay intentional", state.strictModePhrase)
    }

    private fun TestScope.createViewModel(): Pair<SettingsRepository, HomeViewModel> {
        val settingsRepository = SettingsRepository(
            dataStore = FakePreferencesDataStore(),
            scope = backgroundScope,
            clock = clock,
        )
        val interceptionRepository = InterceptionRepository(
            dao = logDao,
            clock = clock,
        )
        val dailyBudgetRepository = DailyBudgetRepository(
            dao = database.dailyUsageDao(),
            usageReader = fakeUsageReader,
            clock = clock,
            ioDispatcher = testDispatcher,
        )
        val installedAppsRepository = InstalledAppsRepository(
            context = context,
            ioDispatcher = testDispatcher,
        )
        val permissionChecker = PermissionChecker(context)
        val rawViewModel = HomeViewModel(
            settingsRepository = settingsRepository,
            interceptionRepository = interceptionRepository,
            dailyBudgetRepository = dailyBudgetRepository,
            installedAppsRepository = installedAppsRepository,
            permissionChecker = permissionChecker,
            clock = clock,
        )
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = rawViewModel as T
            },
        )[HomeViewModel::class.java]
        return settingsRepository to viewModel
    }

    private fun TestScope.collectUiState(viewModel: HomeViewModel) {
        backgroundScope.launch(testDispatcher) {
            viewModel.uiState.collect {}
        }
    }

    private suspend fun insertLog(
        packageName: String,
        appLabel: String,
        timestampMillis: Long,
        outcome: InterventionOutcome,
    ) {
        logDao.insert(
            InterventionLogEntity(
                packageName = packageName,
                appLabel = appLabel,
                timestampMillis = timestampMillis,
                outcome = outcome,
                frictionMode = FrictionMode.BREATHING,
            ),
        )
    }

    private fun atUtc(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC).toEpochMilli()

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeForegroundUsageReader : ForegroundUsageReader {
        var response: Map<String, Long>? = emptyMap()
        val requestedPackages = mutableSetOf<String>()

        override fun readForegroundMillis(
            packages: Set<String>,
            startOfDayMillis: Long,
            nowMillis: Long,
        ): Map<String, Long>? {
            requestedPackages += packages
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
