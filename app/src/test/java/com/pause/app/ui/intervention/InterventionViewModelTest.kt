package com.pause.app.ui.intervention

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.AppTriggerCount
import com.pause.app.data.local.InterventionLogDao
import com.pause.app.data.local.InterventionLogEntity
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    ): SessionLimitManager = SessionLimitManager(
        scope = scope.backgroundScope,
        persistence = persistence,
        alarmScheduler = RecordingAlarmScheduler(),
        notifier = RecordingNotifier(),
        clock = clock,
    )

    private fun TestScope.viewModel(manager: SessionLimitManager): InterventionViewModel {
        val settings = SettingsRepository(
            dataStore = FakePreferencesDataStore(),
            scope = backgroundScope,
        )
        val installedApps = InstalledAppsRepository(
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val interception = InterceptionRepository(
            dao = FakeInterventionLogDao(),
            clock = clock,
        )
        return InterventionViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    PauseInterventionActivity.EXTRA_PACKAGE_NAME to context.packageName,
                    PauseInterventionActivity.EXTRA_FRICTION_MODES to
                        arrayListOf(FrictionMode.TIME_LIMIT.name),
                ),
            ),
            settingsRepository = settings,
            installedAppsRepository = installedApps,
            interceptionRepository = interception,
            sessionStarter = manager,
            cooldownTracker = CooldownTracker(FakeTimeSource()),
            clock = clock,
        )
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
        private var nextId = 1L
        val completed = mutableListOf<Long>()

        override suspend fun insert(log: InterventionLogEntity): Long {
            val id = nextId++
            return id
        }

        override suspend fun complete(
            id: Long,
            outcome: InterventionOutcome,
            intention: String?,
            sessionLimitMinutes: Int?,
        ) {
            completed += id
        }

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
    }

    private class RecordingAlarmScheduler : SessionAlarmScheduler {
        override fun schedule(session: ActiveSession) = Unit
        override fun cancel() = Unit
    }

    private class RecordingNotifier : SessionReminderSink {
        override fun show(appLabel: String) = Unit
    }
}
