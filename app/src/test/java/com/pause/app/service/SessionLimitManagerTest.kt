package com.pause.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLimitManagerTest {

    @Test
    fun `emits a finished session when the limit elapses`() = runTest {
        val manager = SessionLimitManager(this)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)

        advanceTimeBy(FIVE_MINUTES - 1)
        runCurrent()
        assertTrue(finished.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(INSTAGRAM, finished.single().packageName)
        assertEquals("Instagram", finished.single().appLabel)
        assertEquals(5, finished.single().limitMinutes)
    }

    @Test
    fun `cancelling the session prevents the reminder`() = runTest {
        val manager = SessionLimitManager(this)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        advanceTimeBy(60_000L)
        manager.cancelSession(INSTAGRAM)

        advanceTimeBy(TEN_MINUTES)
        runCurrent()

        assertTrue(finished.isEmpty())
    }

    @Test
    fun `cancelling a different package leaves the session running`() = runTest {
        val manager = SessionLimitManager(this)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        manager.cancelSession(TIKTOK)

        advanceTimeBy(FIVE_MINUTES + 1)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(INSTAGRAM, finished.single().packageName)
    }

    @Test
    fun `starting a new session replaces the previous timer`() = runTest {
        val manager = SessionLimitManager(this)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 5)
        advanceTimeBy(60_000L)

        manager.startSession(INSTAGRAM, "Instagram", 15)
        advanceTimeBy(FIVE_MINUTES)
        runCurrent()
        assertTrue(finished.isEmpty())

        advanceTimeBy(TEN_MINUTES + 1)
        runCurrent()

        assertEquals(1, finished.size)
        assertEquals(15, finished.single().limitMinutes)
    }

    @Test
    fun `non-positive limits are ignored`() = runTest {
        val manager = SessionLimitManager(this)
        val finished = mutableListOf<SessionLimitManager.Session>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.finished.collect { finished += it }
        }

        manager.startSession(INSTAGRAM, "Instagram", 0)
        manager.startSession(INSTAGRAM, "Instagram", -5)
        advanceTimeBy(TEN_MINUTES)
        runCurrent()

        assertTrue(finished.isEmpty())
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
        const val FIVE_MINUTES = 5 * 60_000L
        const val TEN_MINUTES = 10 * 60_000L
    }
}
