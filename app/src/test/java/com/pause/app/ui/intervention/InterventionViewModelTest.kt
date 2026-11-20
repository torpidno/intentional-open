package com.pause.app.ui.intervention

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.AppTriggerCount
import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.ActiveSession
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.service.CooldownTracker
import com.pause.app.service.SessionAlarmScheduler
import com.pause.app.service.SessionLimitManager
import com.pause.app.service.SessionPersistence
import com.pause.app.service.SessionReminderSink
import com.pause.app.util.FakeTimeSource
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class InterventionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `restores the current step and selections after process death`() = runTest(dispatcher) {
        val persistence = GatedSessionPersistence(CompletableDeferred())
        val manager = manager(persistence, this)
        val modes = listOf(FrictionMode.INTENTION, FrictionMode.TIME_LIMIT)
        val handle = SavedStateHandle(
            mapOf(
                PauseInterventionActivity.EXTRA_PACKAGE_NAME to context.packageName,
                PauseInterventionActivity.EXTRA_FRICTION_MODES to
                    ArrayList(modes.map { it.name }),
            ),
        )
        val first = viewModel(manager, modes, handle)

        runCurrent()
        first.selectReason("Check a message")
        first.updateNote("A note")
        first.continueToTargetApp()
        first.selectSessionLimit(15)

        assertEquals(FrictionMode.TIME_LIMIT, first.uiState.value.mode)
        assertEquals(1, first.uiState.value.stepIndex)

        // A new ViewModel with the same SavedStateHandle simulates process death
        // and activity recreation.
        val restored = viewModel(manager, modes, handle).uiState.value

        assertEquals(FrictionMode.TIME_LIMIT, restored.mode)
        assertEquals(1, restored.stepIndex)
        assertEquals("Check a message", restored.selectedReason)
        assertEquals("A note", restored.note)
        assertEquals(15, restored.selectedLimitMinutes)
        assertTrue(restored.isUnlocked)
    }

    @Test
    fun `target app waits until the session deadline is persisted`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val persistence = GatedSessionPersistence(gate)
        val manager = manager(persistence, this)
        val viewModel = viewModel(manager)
        val effects = collectEffects(viewModel)

        runCurrent()
        viewModel.selectSessionLimit(5)
        viewModel.continueToTargetApp()
        runCurrent()

        assertTrue(persistence.writeStarted.isCompleted)
        assertTrue(effects.isEmpty())
        assertTrue(viewModel.uiState.value.isCompleting)

        gate.complete(Unit)
        runCurrent()
        runCurrent()

        assertNotNull(persistence.stored)
        assertTrue((persistence.stored?.attemptId ?: 0L) > 0L)
        assertEquals(1, effects.size)
        assertTrue(effects.single() is InterventionEffect.OpenTargetApp)
    }

    @Test
    fun `persistence failure keeps the app closed and retry launches after success`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val persistence = GatedSessionPersistence(gate, fail = true)
            val manager = manager(persistence, this)
            val viewModel = viewModel(manager)
            val effects = collectEffects(viewModel)

            runCurrent()
            viewModel.selectSessionLimit(5)
            viewModel.continueToTargetApp()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            runCurrent()

            assertTrue(viewModel.uiState.value.sessionStartError)
            assertFalse(viewModel.uiState.value.isCompleting)
            assertTrue(effects.isEmpty())

            persistence.fail = false
            viewModel.retrySessionStart()
            runCurrent()
            runCurrent()

            assertFalse(viewModel.uiState.value.sessionStartError)
            assertNotNull(persistence.stored)
            assertEquals(1, effects.size)
            assertTrue(effects.single() is InterventionEffect.OpenTargetApp)
        }

    @Test
    fun `multi-step intention then breathing runs countdown, unlocks, and preserves intention`() =
        runTest(dispatcher) {
            val mutableClock = MutableClock(1_000_000L)
            val dao = FakeInterventionLogDao()
            val persistence = GatedSessionPersistence(CompletableDeferred(Unit))
            val manager = manager(persistence, this, mutableClock)
            val modes = listOf(FrictionMode.INTENTION, FrictionMode.BREATHING)
            val handle = SavedStateHandle(
                mapOf(
                    PauseInterventionActivity.EXTRA_PACKAGE_NAME to context.packageName,
                    PauseInterventionActivity.EXTRA_FRICTION_MODES to
                        ArrayList(modes.map { it.name }),
                ),
            )
            val viewModel = viewModel(
                manager = manager,
                modes = modes,
                savedStateHandle = handle,
                dao = dao,
                testClock = mutableClock,
            )
            val effects = collectEffects(viewModel)

            runCurrent()
            assertEquals(FrictionMode.INTENTION, viewModel.uiState.value.mode)
            assertEquals(0, viewModel.uiState.value.stepIndex)
            assertFalse(viewModel.uiState.value.isUnlocked)
            assertNull(handle.get<Long>("pause_ends_at"))

            viewModel.selectReason("Check a message")
            viewModel.updateNote("Reply to Sam")
            viewModel.continueToTargetApp()

            // Entering step 2 (BREATHING) initializes the countdown and keeps Continue locked.
            val totalMillis = PauseSettings.DEFAULT_BREATHING_SECONDS * 1_000L
            assertEquals(FrictionMode.BREATHING, viewModel.uiState.value.mode)
            assertEquals(1, viewModel.uiState.value.stepIndex)
            assertEquals(totalMillis, viewModel.uiState.value.remainingMillis)
            assertEquals(0f, viewModel.uiState.value.breathProgress, 0.001f)
            assertFalse(viewModel.uiState.value.isUnlocked)
            assertEquals(1_000_000L + totalMillis, handle.get<Long>("pause_ends_at"))

            // Mid-countdown: progress advances, Continue still blocked.
            mutableClock.advanceBy(2_000L)
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(totalMillis - 2_000L, viewModel.uiState.value.remainingMillis)
            assertEquals(2_000f / totalMillis, viewModel.uiState.value.breathProgress, 0.001f)
            assertFalse(viewModel.uiState.value.isUnlocked)
            viewModel.continueToTargetApp()
            runCurrent()
            assertTrue(effects.isEmpty())

            // End of countdown: unlocks and preserves step 1's intention on completion.
            mutableClock.advanceBy(totalMillis - 2_000L)
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(0L, viewModel.uiState.value.remainingMillis)
            assertEquals(1f, viewModel.uiState.value.breathProgress, 0.001f)
            assertTrue(viewModel.uiState.value.isUnlocked)

            viewModel.continueToTargetApp()
            runCurrent()
            runCurrent()

            assertEquals(1, effects.size)
            assertTrue(effects.single() is InterventionEffect.OpenTargetApp)
            val completion = dao.completions.single()
            assertEquals(InterventionOutcome.PROCEEDED, completion.outcome)
            assertEquals("Check a message — Reply to Sam", completion.intention)
            assertNull(completion.sessionLimitMinutes)
        }

    @Test
    fun `time limit then breathing restores remaining countdown across process death`() =
        runTest(dispatcher) {
            val mutableClock = MutableClock(1_000_000L)
            val dao = FakeInterventionLogDao()
            val persistence = GatedSessionPersistence(CompletableDeferred(Unit))
            val manager = manager(persistence, this, mutableClock)
            val modes = listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING)
            val handle = SavedStateHandle(
                mapOf(
                    PauseInterventionActivity.EXTRA_PACKAGE_NAME to context.packageName,
                    PauseInterventionActivity.EXTRA_FRICTION_MODES to
                        ArrayList(modes.map { it.name }),
                ),
            )
            val firstStore = ViewModelStore()
            val first = attachToStore(
                firstStore,
                viewModel(
                    manager = manager,
                    modes = modes,
                    savedStateHandle = handle,
                    dao = dao,
                    testClock = mutableClock,
                ),
            )

            runCurrent()
            first.selectSessionLimit(15)
            first.continueToTargetApp()

            val totalMillis = PauseSettings.DEFAULT_BREATHING_SECONDS * 1_000L
            mutableClock.advanceBy(2_000L)
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(totalMillis - 2_000L, first.uiState.value.remainingMillis)

            // Simulate process death mid-breathing step.
            firstStore.clear()
            mutableClock.advanceBy(1_000L)

            val restoredStore = ViewModelStore()
            val restored = attachToStore(
                restoredStore,
                viewModel(
                    manager = manager,
                    modes = modes,
                    savedStateHandle = handle,
                    dao = dao,
                    testClock = mutableClock,
                ),
            )
            val effects = collectEffects(restored)

            // Restored ViewModel resumes from the saved deadline (2s left), not a reset 5s.
            assertEquals(FrictionMode.BREATHING, restored.uiState.value.mode)
            assertEquals(1, restored.uiState.value.stepIndex)
            assertEquals(15, restored.uiState.value.selectedLimitMinutes)
            assertEquals(totalMillis - 3_000L, restored.uiState.value.remainingMillis)
            assertFalse(restored.uiState.value.isUnlocked)
            assertEquals(1, dao.inserted.size)

            mutableClock.advanceBy(totalMillis - 3_000L)
            advanceTimeBy(50L)
            runCurrent()
            assertTrue(restored.uiState.value.isUnlocked)

            restored.continueToTargetApp()
            runCurrent()
            runCurrent()

            assertEquals(1, effects.size)
            assertTrue(effects.single() is InterventionEffect.OpenTargetApp)
            assertEquals(15, persistence.stored?.limitMinutes)
            val completion = dao.completions.single()
            assertEquals(InterventionOutcome.PROCEEDED, completion.outcome)
            assertEquals(15, completion.sessionLimitMinutes)
            restoredStore.clear()
        }

    @Test
    fun `handleNewIntervention switches apps, closes prior attempt, and starts new timer`() =
        runTest(dispatcher) {
            val mutableClock = MutableClock(1_000_000L)
            val dao = FakeInterventionLogDao()
            val cooldowns = CooldownTracker(FakeTimeSource())
            val persistence = GatedSessionPersistence(CompletableDeferred(Unit))
            val manager = manager(persistence, this, mutableClock)
            val initialPackage = "com.instagram.android"
            val nextPackage = context.packageName
            val initialModes = listOf(FrictionMode.INTENTION, FrictionMode.TIME_LIMIT)
            val handle = SavedStateHandle(
                mapOf(
                    PauseInterventionActivity.EXTRA_PACKAGE_NAME to initialPackage,
                    PauseInterventionActivity.EXTRA_FRICTION_MODES to
                        ArrayList(initialModes.map { it.name }),
                ),
            )
            cooldowns.setCooldown(initialPackage, 4_000L)

            val store = ViewModelStore()
            val vm = attachToStore(
                store,
                viewModel(
                    manager = manager,
                    modes = initialModes,
                    savedStateHandle = handle,
                    dao = dao,
                    cooldownTracker = cooldowns,
                    testClock = mutableClock,
                ),
            )
            val effects = collectEffects(vm)

            runCurrent()
            vm.selectReason("Check a message")
            vm.continueToTargetApp()
            vm.selectSessionLimit(15)
            assertEquals(FrictionMode.TIME_LIMIT, vm.uiState.value.mode)

            // Same active package (not completing) and blank package are ignored.
            vm.handleNewIntervention(initialPackage, listOf(FrictionMode.BREATHING))
            vm.handleNewIntervention("   ", listOf(FrictionMode.BREATHING))
            runCurrent()
            assertEquals(initialPackage, vm.uiState.value.packageName)
            assertEquals(FrictionMode.TIME_LIMIT, vm.uiState.value.mode)
            assertEquals(1, dao.inserted.size)
            assertTrue(dao.completions.isEmpty())

            // Switching to a different app closes the prior attempt as WALKED_AWAY,
            // clears its launch guard, resets state/SavedStateHandle, and starts BREATHING.
            vm.handleNewIntervention(
                nextPackage,
                listOf(FrictionMode.BREATHING, FrictionMode.INTENTION),
            )
            runCurrent()

            assertFalse(cooldowns.isCoolingDown(initialPackage))
            assertTrue(effects.isEmpty())
            assertEquals(2, dao.inserted.size)
            assertEquals(nextPackage, dao.inserted[1].packageName)

            val priorCompletion = dao.completions.single()
            assertEquals(1L, priorCompletion.id)
            assertEquals(InterventionOutcome.WALKED_AWAY, priorCompletion.outcome)
            assertEquals("Check a message", priorCompletion.intention)
            assertNull(priorCompletion.sessionLimitMinutes)

            val state = vm.uiState.value
            val totalMillis = PauseSettings.DEFAULT_BREATHING_SECONDS * 1_000L
            assertEquals(nextPackage, state.packageName)
            assertEquals(FrictionMode.BREATHING, state.mode)
            assertEquals(2, state.modeCount)
            assertEquals(0, state.stepIndex)
            assertEquals(totalMillis, state.remainingMillis)
            assertFalse(state.isUnlocked)
            assertNull(state.selectedReason)
            assertEquals("", state.note)
            assertNull(state.selectedLimitMinutes)

            assertEquals(nextPackage, handle.get<String>(PauseInterventionActivity.EXTRA_PACKAGE_NAME))
            assertEquals(2L, handle.get<Long>("attempt_id"))
            assertNull(handle.get<String>("selected_reason"))
            assertNull(handle.get<Int>("selected_limit"))

            // Breathing timer runs for the new intervention and unlocks on expiry.
            mutableClock.advanceBy(totalMillis)
            advanceTimeBy(50L)
            runCurrent()
            assertTrue(vm.uiState.value.isUnlocked)
            store.clear()
        }

    @Test
    fun `handleNewIntervention after completion restarts without double-closing prior attempt`() =
        runTest(dispatcher) {
            val dao = FakeInterventionLogDao()
            val persistence = GatedSessionPersistence(CompletableDeferred(Unit))
            val manager = manager(persistence, this)
            val vm = viewModel(
                manager = manager,
                modes = listOf(FrictionMode.INTENTION),
                dao = dao,
            )
            collectEffects(vm)

            runCurrent()
            vm.walkAway()
            runCurrent()
            assertTrue(vm.uiState.value.isCompleting)
            assertEquals(1, dao.completions.size)

            // Re-entering for the same package after completion starts a fresh intervention.
            vm.handleNewIntervention(context.packageName, listOf(FrictionMode.TIME_LIMIT))
            runCurrent()

            assertFalse(vm.uiState.value.isCompleting)
            assertEquals(FrictionMode.TIME_LIMIT, vm.uiState.value.mode)
            assertEquals(2, dao.inserted.size)
            assertEquals(1, dao.completions.size)
        }

    @Test
    fun `handleNewIntervention immediately after restore closes restored attempt id without clobbering new one`() =
        runTest(dispatcher) {
            val dao = FakeInterventionLogDao()
            val persistence = GatedSessionPersistence(CompletableDeferred(Unit))
            val manager = manager(persistence, this)
            val restoredAttemptId = 42L
            val handle = SavedStateHandle(
                mapOf(
                    PauseInterventionActivity.EXTRA_PACKAGE_NAME to "com.instagram.android",
                    PauseInterventionActivity.EXTRA_FRICTION_MODES to
                        arrayListOf(FrictionMode.INTENTION.name),
                    "attempt_id" to restoredAttemptId,
                ),
            )
            val vm = viewModel(
                manager = manager,
                modes = listOf(FrictionMode.INTENTION),
                savedStateHandle = handle,
                dao = dao,
            )

            // Invoke handleNewIntervention before runCurrent() so no init coroutines have run yet.
            vm.handleNewIntervention(context.packageName, listOf(FrictionMode.TIME_LIMIT))
            runCurrent()

            // Only the new intervention inserted a row; the restored attempt was closed by its saved ID.
            assertEquals(1, dao.inserted.size)
            assertEquals(context.packageName, dao.inserted.single().packageName)
            assertEquals(1L, handle.get<Long>("attempt_id"))

            val completion = dao.completions.single()
            assertEquals(restoredAttemptId, completion.id)
            assertEquals(InterventionOutcome.WALKED_AWAY, completion.outcome)
        }

    private fun attachToStore(
        store: ViewModelStore,
        instance: InterventionViewModel,
    ): InterventionViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = instance as T
        },
    )[InterventionViewModel::class.java]

    private fun TestScope.collectEffects(
        viewModel: InterventionViewModel,
    ): List<InterventionEffect> {
        val effects = mutableListOf<InterventionEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        return effects
    }

    private fun manager(
        persistence: SessionPersistence,
        scope: TestScope,
        testClock: Clock = clock,
    ): SessionLimitManager = SessionLimitManager(
        scope = scope.backgroundScope,
        persistence = persistence,
        alarmScheduler = RecordingAlarmScheduler(),
        notifier = RecordingNotifier(),
        clock = testClock,
    )

    private fun TestScope.viewModel(
        manager: SessionLimitManager,
        modes: List<FrictionMode> = listOf(FrictionMode.TIME_LIMIT),
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf(
                PauseInterventionActivity.EXTRA_PACKAGE_NAME to context.packageName,
                PauseInterventionActivity.EXTRA_FRICTION_MODES to
                    ArrayList(modes.map { it.name }),
            ),
        ),
        dao: FakeInterventionLogDao = FakeInterventionLogDao(),
        cooldownTracker: CooldownTracker = CooldownTracker(FakeTimeSource()),
        testClock: Clock = clock,
    ): InterventionViewModel {
        val settings = SettingsRepository(
            dataStore = FakePreferencesDataStore(),
            scope = backgroundScope,
        )
        val installedApps = InstalledAppsRepository(
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val interception = InterceptionRepository(
            dao = dao,
            clock = testClock,
        )
        return InterventionViewModel(
            savedStateHandle = savedStateHandle,
            settingsRepository = settings,
            installedAppsRepository = installedApps,
            interceptionRepository = interception,
            sessionStarter = manager,
            cooldownTracker = cooldownTracker,
            clock = testClock,
        )
    }

    private class MutableClock(
        private var currentMillis: Long,
        private val zoneId: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        fun advanceBy(millis: Long) {
            currentMillis += millis
        }

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentMillis, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
    }

    private class GatedSessionPersistence(
        private val gate: CompletableDeferred<Unit>,
        var fail: Boolean = false,
    ) : SessionPersistence {
        val writeStarted = CompletableDeferred<Unit>()
        var stored: ActiveSession? = null

        override suspend fun read(): ActiveSession? = stored

        override suspend fun write(session: ActiveSession) {
            writeStarted.complete(Unit)
            gate.await()
            if (fail) throw IOException("disk full")
            stored = session
        }

        override suspend fun clear() {
            stored = null
        }
    }

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

    private class FakeInterventionLogDao : InterventionLogDao {
        data class Completion(
            val id: Long,
            val outcome: InterventionOutcome,
            val intention: String?,
            val sessionLimitMinutes: Int?,
        )

        private var nextId = 1L
        val inserted = mutableListOf<InterventionLogEntity>()
        val completed = mutableListOf<Long>()
        val completions = mutableListOf<Completion>()

        override suspend fun insert(log: InterventionLogEntity): Long {
            val id = nextId++
            inserted += log.copy(id = id)
            return id
        }

        override suspend fun complete(
            id: Long,
            outcome: InterventionOutcome,
            intention: String?,
            sessionLimitMinutes: Int?,
        ) {
            completed += id
            completions += Completion(id, outcome, intention, sessionLimitMinutes)
        }

        override suspend fun completeAllPending(outcome: InterventionOutcome) = Unit

        override suspend fun completeAllPendingBefore(
            outcome: InterventionOutcome,
            beforeMillis: Long,
        ) = Unit

        override fun observeAttemptCount(startMillis: Long, endMillis: Long): Flow<Int> =
            flowOf(0)

        override fun observeWalkedAwayCount(startMillis: Long, endMillis: Long): Flow<Int> =
            flowOf(0)

        override fun observeMostTriggered(
            startMillis: Long,
            endMillis: Long,
        ): Flow<AppTriggerCount?> = flowOf(null)

        override fun observeRecent(limit: Int): Flow<List<InterventionLogEntity>> =
            flowOf(emptyList())

        override suspend fun deleteOlderThan(cutoffMillis: Long) = Unit

        override suspend fun getAll(): List<InterventionLogEntity> = emptyList()

        override suspend fun insertAll(logs: List<InterventionLogEntity>): List<Long> =
            logs.map { insert(it) }

        override suspend fun deleteAll() = Unit
    }

    private class RecordingAlarmScheduler : SessionAlarmScheduler {
        override fun schedule(session: ActiveSession) = Unit
        override fun cancel() = Unit
    }

    private class RecordingNotifier : SessionReminderSink {
        override fun show(appLabel: String) = Unit
    }
}
