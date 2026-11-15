package com.pause.app.service

import com.pause.app.util.FakeTimeSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CooldownTrackerTest {

    private lateinit var time: FakeTimeSource
    private lateinit var tracker: CooldownTracker

    @Before
    fun setUp() {
        time = FakeTimeSource(nowMillis = 5_000L)
        tracker = CooldownTracker(time)
    }

    @Test
    fun `no cooldown by default`() {
        assertFalse(tracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `cooldown is active until its deadline`() {
        tracker.setCooldown(INSTAGRAM, FIVE_MINUTES)

        assertTrue(tracker.isCoolingDown(INSTAGRAM))

        time.advanceBy(FIVE_MINUTES - 1)
        assertTrue(tracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `cooldown expires exactly at the deadline`() {
        tracker.setCooldown(INSTAGRAM, 1_000L)

        time.advanceBy(1_000L)

        assertFalse(tracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `cooldowns are tracked per package`() {
        tracker.setCooldown(INSTAGRAM, FIVE_MINUTES)

        assertTrue(tracker.isCoolingDown(INSTAGRAM))
        assertFalse(tracker.isCoolingDown(TIKTOK))
    }

    @Test
    fun `a shorter cooldown never shortens an existing one`() {
        tracker.setCooldown(INSTAGRAM, TEN_MINUTES)
        time.advanceBy(1_000L)

        // The user chose a 5 s cooldown while the 10 min one is still running.
        tracker.setCooldown(INSTAGRAM, 5_000L)
        time.advanceBy(6_000L)

        assertTrue(tracker.isCoolingDown(INSTAGRAM))

        time.advanceBy(TEN_MINUTES)
        assertFalse(tracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `a longer cooldown extends an existing one`() {
        tracker.setCooldown(INSTAGRAM, 1_000L)

        tracker.setCooldown(INSTAGRAM, TEN_MINUTES)
        time.advanceBy(1_000L)

        assertTrue(tracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `clear removes a single cooldown`() {
        tracker.setCooldown(INSTAGRAM, FIVE_MINUTES)
        tracker.setCooldown(TIKTOK, FIVE_MINUTES)

        tracker.clear(INSTAGRAM)

        assertFalse(tracker.isCoolingDown(INSTAGRAM))
        assertTrue(tracker.isCoolingDown(TIKTOK))
    }

    @Test
    fun `clearAll removes every cooldown`() {
        tracker.setCooldown(INSTAGRAM, FIVE_MINUTES)
        tracker.setCooldown(TIKTOK, FIVE_MINUTES)

        tracker.clearAll()

        assertFalse(tracker.isCoolingDown(INSTAGRAM))
        assertFalse(tracker.isCoolingDown(TIKTOK))
    }

    @Test
    fun `zero and negative durations are ignored`() {
        tracker.setCooldown(INSTAGRAM, 0L)
        tracker.setCooldown(TIKTOK, -1L)

        assertFalse(tracker.isCoolingDown(INSTAGRAM))
        assertFalse(tracker.isCoolingDown(TIKTOK))
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
        const val FIVE_MINUTES = 5 * 60_000L
        const val TEN_MINUTES = 10 * 60_000L
    }
}
