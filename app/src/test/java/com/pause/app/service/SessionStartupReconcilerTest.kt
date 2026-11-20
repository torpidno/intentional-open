package com.pause.app.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.data.local.PauseDatabase
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.ActiveSession
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.util.FakeTimeSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionStartupReconcilerTest {

    private lateinit var database: PauseDatabase
    private lateinit var repository: InterceptionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PauseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InterceptionRepository(
            dao = database.interventionLogDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(CLOCK_MILLIS - 60_000L), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `active session completes its pending attempt and closes other stale rows`() = runTest {
        val activeAttempt = repository.recordAttempt(
            packageName = INSTAGRAM,
            appLabel = "Instagram",
            modes = listOf(FrictionMode.TIME_LIMIT),
        )
        val staleAttempt = repository.recordAttempt(
            packageName = TIKTOK,
            appLabel = "TikTok",
            modes = listOf(FrictionMode.BREATHING),
        )

        val timeSource = FakeTimeSource()
        val cooldowns = CooldownTracker(timeSource)
        val reconciler = SessionStartupReconciler(
            persistence = StaticSessionPersistence(
                ActiveSession(
                    packageName = INSTAGRAM,
                    appLabel = "Instagram",
                    limitMinutes = 15,
                    endsAtEpochMillis = CLOCK_MILLIS + 5 * 60_000L,
                    attemptId = activeAttempt,
                    intention = "Check a message",
                ),
            ),
            interceptionRepository = repository,
            cooldownTracker = cooldowns,
            clock = clock,
        )

        reconciler.reconcile()
        assertTrue(cooldowns.isCoolingDown(INSTAGRAM))

        val rows = repository.observeHistory().first().associateBy { it.id }
        val active = requireNotNull(rows[activeAttempt])
        assertEquals(InterventionOutcome.PROCEEDED, active.outcome)
        assertEquals("Check a message", active.intention)
        assertEquals(15, active.sessionLimitMinutes)
        assertEquals(InterventionOutcome.WALKED_AWAY, requireNotNull(rows[staleAttempt]).outcome)
    }

    @Test
    fun `no active session closes all pending attempts`() = runTest {
        repository.recordAttempt(
            packageName = INSTAGRAM,
            appLabel = "Instagram",
            modes = listOf(FrictionMode.INTENTION),
        )

        val reconciler = SessionStartupReconciler(
            persistence = StaticSessionPersistence(null),
            interceptionRepository = repository,
            cooldownTracker = CooldownTracker(FakeTimeSource()),
            clock = clock,
        )

        reconciler.reconcile()

        val row = repository.observeHistory().first().single()
        assertEquals(InterventionOutcome.WALKED_AWAY, row.outcome)
    }

    @Test
    fun `live pending rows at or after startup are preserved while older rows close`() = runTest {
        val dao = database.interventionLogDao()
        val staleId = dao.insert(
            InterventionLogEntity(
                packageName = TIKTOK,
                appLabel = "TikTok",
                timestampMillis = CLOCK_MILLIS - 1L,
                outcome = InterventionOutcome.PENDING,
                frictionMode = FrictionMode.BREATHING,
                frictionModeSequence = "BREATHING",
            ),
        )
        val liveAtStartupId = dao.insert(
            InterventionLogEntity(
                packageName = INSTAGRAM,
                appLabel = "Instagram",
                timestampMillis = CLOCK_MILLIS,
                outcome = InterventionOutcome.PENDING,
                frictionMode = FrictionMode.INTENTION,
                frictionModeSequence = "INTENTION",
            ),
        )
        val liveAfterStartupId = dao.insert(
            InterventionLogEntity(
                packageName = INSTAGRAM,
                appLabel = "Instagram",
                timestampMillis = CLOCK_MILLIS + 500L,
                outcome = InterventionOutcome.PENDING,
                frictionMode = FrictionMode.TIME_LIMIT,
                frictionModeSequence = "TIME_LIMIT",
            ),
        )

        val reconciler = SessionStartupReconciler(
            persistence = StaticSessionPersistence(null),
            interceptionRepository = repository,
            cooldownTracker = CooldownTracker(FakeTimeSource()),
            clock = Clock.fixed(Instant.ofEpochMilli(CLOCK_MILLIS + 2_000L), ZoneOffset.UTC),
        )

        reconciler.reconcile(startedBeforeMillis = CLOCK_MILLIS)

        val rows = repository.observeHistory().first().associateBy { it.id }
        assertEquals(InterventionOutcome.WALKED_AWAY, requireNotNull(rows[staleId]).outcome)
        assertEquals(InterventionOutcome.PENDING, requireNotNull(rows[liveAtStartupId]).outcome)
        assertEquals(InterventionOutcome.PENDING, requireNotNull(rows[liveAfterStartupId]).outcome)
    }

    private val clock = Clock.fixed(Instant.ofEpochMilli(CLOCK_MILLIS), ZoneOffset.UTC)

    private class StaticSessionPersistence(
        private val active: ActiveSession?,
    ) : SessionPersistence {
        override suspend fun read(): ActiveSession? = active
        override suspend fun write(session: ActiveSession) = Unit
        override suspend fun clear() = Unit
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
        const val CLOCK_MILLIS = 1_000_000L
    }
}
