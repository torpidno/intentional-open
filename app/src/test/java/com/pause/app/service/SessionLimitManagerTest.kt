package com.pause.app.service

import com.pause.app.domain.model.ActiveSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLimitManagerTest {

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)

    @Test
    fun `emits a finished session when the limit elapses`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        runCurrent()

        advanceTimeBy(FIVE_MINUTES - 1)
        runCurrent()
        assertTrue(finished.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(INSTAGRAM, finished.single().packageName)
        assertEquals("Instagram", finished.single().appLabel)
        assertEquals(5, finished.single().limitMinutes)
        assertNull(persistence.stored)
    }

    @Test
    fun `cancelling the session prevents the reminder`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        runCurrent()
        advanceTimeBy(60_000L)
        manager.cancelSession(INSTAGRAM)
        runCurrent()

        advanceTimeBy(TEN_MINUTES)
        runCurrent()

        assertTrue(finished.isEmpty())
        assertNull(persistence.stored)
        assertNull(scheduler.scheduled)
    }

    @Test
    fun `cancelling a different package leaves the session running`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        runCurrent()
        manager.cancelSession(TIKTOK)

        advanceTimeBy(FIVE_MINUTES + 1)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(INSTAGRAM, finished.single().packageName)
    }

    @Test
    fun `starting a new session replaces the previous timer`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        runCurrent()
        advanceTimeBy(60_000L)

        manager.startSession(INSTAGRAM, "Instagram", 15)
        runCurrent()
        advanceTimeBy(FIVE_MINUTES)
        runCurrent()
        assertTrue(finished.isEmpty())

        advanceTimeBy(TEN_MINUTES + 1)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(15, finished.single().limitMinutes)
    }

    @Test
    fun `the active session and alarm are persisted when it starts`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)

        manager.startSession(INSTAGRAM, "Instagram", 5)

        // startSession is suspend and must persist before it returns.
        val stored = persistence.stored
        assertNotNull(stored)
        assertEquals(INSTAGRAM, stored?.packageName)
        assertEquals(5, stored?.limitMinutes)
        assertEquals(clock.millis() + FIVE_MINUTES, stored?.endsAtEpochMillis)
        assertEquals(stored, scheduler.scheduled)
    }

    @Test
    fun `restores an unexpired session after a restart`() = runTest {
        val persistence = InMemorySessionPersistence().apply {
            stored = ActiveSession(
                packageName = INSTAGRAM,
                appLabel = "Instagram",
                limitMinutes = 5,
                endsAtEpochMillis = clock.millis() + TWO_MINUTES,
            )
        }
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        runCurrent()
        assertEquals(persistence.stored, scheduler.scheduled)

        advanceTimeBy(TWO_MINUTES + 1)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(INSTAGRAM, finished.single().packageName)
    }

    @Test
    fun `falls back to a notification when no reminder collector is attached`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val notifier = RecordingNotifier()
        val manager = manager(persistence, scheduler, notifier)

        manager.startSession(INSTAGRAM, "Instagram", 5)
        runCurrent()
        advanceTimeBy(FIVE_MINUTES + 1)
        runCurrent()

        assertEquals(listOf("Instagram"), notifier.labels)
    }

    @Test
    fun `non-positive limits are ignored`() = runTest {
        val persistence = InMemorySessionPersistence()
        val scheduler = RecordingAlarmScheduler()
        val manager = manager(persistence, scheduler)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 0)
        manager.startSession(INSTAGRAM, "Instagram", -5)
        advanceTimeBy(TEN_MINUTES)
        runCurrent()

        assertTrue(finished.isEmpty())
        assertNull(persistence.stored)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        persistence: SessionPersistence,
        scheduler: SessionAlarmScheduler,
        notifier: SessionReminderSink = RecordingNotifier(),
    ) = SessionLimitManager(
        scope = this,
        persistence = persistence,
        alarmScheduler = scheduler,
        notifier = notifier,
        clock = clock,
    )

    private class InMemorySessionPersistence : SessionPersistence {
        var stored: ActiveSession? = null

        override suspend fun read(): ActiveSession? = stored

        override suspend fun write(session: ActiveSession) {
            stored = session
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private class RecordingAlarmScheduler : SessionAlarmScheduler {
        var scheduled: ActiveSession? = null

        override fun schedule(session: ActiveSession) {
            scheduled = session
        }

        override fun cancel() {
            scheduled = null
        }
    }

    private class RecordingNotifier : SessionReminderSink {
        val labels = mutableListOf<String>()

        override fun show(appLabel: String) {
            labels += appLabel
        }
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
        const val TWO_MINUTES = 2 * 60_000L
        const val FIVE_MINUTES = 5 * 60_000L
        const val TEN_MINUTES = 10 * 60_000L
    }
}
