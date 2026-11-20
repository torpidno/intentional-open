package com.pause.app.service

import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.util.FakeTimeSource
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InterceptionGateTest {

    private lateinit var time: FakeTimeSource
    private lateinit var cooldownTracker: CooldownTracker
    private lateinit var gate: InterceptionGate

    @Before
    fun setUp() {
        time = FakeTimeSource(nowMillis = 10_000L)
        cooldownTracker = CooldownTracker(time)
        gate = InterceptionGate(cooldownTracker, time)
    }

    @Test
    fun `ignored packages are rejected first`() {
        assertEquals(
            InterceptionDecision.IGNORED_PACKAGE,
            decide(ignoredPackages = setOf(INSTAGRAM)),
        )
    }

    @Test
    fun `master switch off rejects every app`() {
        assertEquals(
            InterceptionDecision.DISABLED,
            decide(settings = pauseSettings(enabled = false)),
        )
    }

    @Test
    fun `apps outside the monitored set are rejected`() {
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(settings = pauseSettings(monitored = setOf(TIKTOK))),
        )
    }

    @Test
    fun `temporarily paused apps are rejected`() {
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(settings = pauseSettings(paused = setOf(INSTAGRAM))),
        )
    }

    @Test
    fun `screen off prevents the pause`() {
        assertEquals(
            InterceptionDecision.DEVICE_UNAVAILABLE,
            decide(isDeviceInteractive = false),
        )
    }

    @Test
    fun `lock screen prevents the pause`() {
        assertEquals(
            InterceptionDecision.DEVICE_UNAVAILABLE,
            decide(isDeviceLocked = true),
        )
    }

    @Test
    fun `a monitored app is intercepted and gets a launch guard`() {
        assertEquals(InterceptionDecision.INTERCEPT, decide())

        assertTrue(cooldownTracker.isCoolingDown(INSTAGRAM))
    }

    @Test
    fun `a duplicate event inside the debounce window is rejected`() {
        assertEquals(InterceptionDecision.INTERCEPT, decide())
        time.advanceBy(500L)

        assertEquals(InterceptionDecision.DUPLICATE_EVENT, decide())
    }

    @Test
    fun `after the debounce window the launch guard still blocks`() {
        assertEquals(InterceptionDecision.INTERCEPT, decide())
        time.advanceBy(InterceptionGate.EVENT_DEBOUNCE_MS)

        assertEquals(InterceptionDecision.COOLING_DOWN, decide())
    }

    @Test
    fun `the app can be intercepted again once the launch guard expires`() {
        assertEquals(InterceptionDecision.INTERCEPT, decide())
        time.advanceBy(InterceptionGate.LAUNCH_GUARD_MS)

        assertEquals(InterceptionDecision.INTERCEPT, decide())
    }

    @Test
    fun `the debounce is per package`() {
        val bothMonitored = pauseSettings(monitored = setOf(INSTAGRAM, TIKTOK))

        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(packageName = INSTAGRAM, settings = bothMonitored),
        )
        time.advanceBy(200L)

        // A burst from a different app is not a duplicate of the first one.
        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(packageName = TIKTOK, settings = bothMonitored),
        )
    }

    @Test
    fun `a failed launch clears the guard so the next launch is caught`() {
        assertEquals(InterceptionDecision.INTERCEPT, decide())

        gate.onLaunchFailed(INSTAGRAM)
        time.advanceBy(InterceptionGate.EVENT_DEBOUNCE_MS)

        assertEquals(InterceptionDecision.INTERCEPT, decide())
    }

    @Test
    fun `an existing user cooldown blocks the app`() {
        cooldownTracker.setCooldown(INSTAGRAM, 5 * 60_000L)

        assertEquals(InterceptionDecision.COOLING_DOWN, decide())
        assertFalse(cooldownTracker.isCoolingDown(TIKTOK))
    }

    @Test
    fun `a snoozed app is skipped until its deadline`() {
        val now = 1_000_000L
        val settings = pauseSettings().copy(
            snoozedUntilMillis = mapOf(INSTAGRAM to now + 1_000L),
        )

        assertEquals(
            InterceptionDecision.SNOOZED,
            decide(settings = settings, gate = gateAt(now)),
        )
        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(settings = settings, gate = gateAt(now + 1_000L)),
        )
    }

    @Test
    fun `an app outside the active hours is skipped`() {
        val settings = pauseSettings().copy(
            scheduleEnabled = true,
            scheduleDays = setOf(java.time.DayOfWeek.MONDAY),
            scheduleStartMinutes = 9 * 60,
            scheduleEndMinutes = 17 * 60,
        )

        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(settings = settings, gate = gateAt(mondayAt(hour = 10))),
        )
        assertEquals(
            InterceptionDecision.OUTSIDE_SCHEDULE,
            decide(settings = settings, gate = gateAt(mondayAt(hour = 18))),
        )
    }

    @Test
    fun `auto-switched scheduled profile overrides monitored apps during its window`() {
        val workProfile = PauseProfile.workPreset(monitoredPackages = setOf(TIKTOK))
        val settings = pauseSettings(monitored = setOf(INSTAGRAM)).copy(
            profiles = listOf(workProfile),
            autoSwitchProfiles = true,
        )

        // Monday 10:00 -> Work profile is active (monitors TIKTOK, not INSTAGRAM).
        val duringWork = gateAt(dayAt(DayOfWeek.MONDAY, hour = 10))
        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(packageName = TIKTOK, settings = settings, gate = duringWork),
        )
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(packageName = INSTAGRAM, settings = settings, gate = duringWork),
        )

        // Monday 19:00 -> outside Work window, falls back to base settings (INSTAGRAM).
        val afterWork = gateAt(dayAt(DayOfWeek.MONDAY, hour = 19))
        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(packageName = INSTAGRAM, settings = settings, gate = afterWork),
        )
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(packageName = TIKTOK, settings = settings, gate = afterWork),
        )

        // Master switch off still wins even during an active scheduled profile.
        assertEquals(
            InterceptionDecision.DISABLED,
            decide(
                packageName = TIKTOK,
                settings = settings.copy(isEnabled = false),
                gate = duringWork,
            ),
        )
    }

    @Test
    fun `overnight scheduled profile remains active past midnight on the next morning`() {
        val nightProfile = PauseProfile(
            id = "night",
            name = "Night",
            monitoredPackages = setOf(TIKTOK),
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.SUNDAY),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )
        val settings = pauseSettings(monitored = emptySet()).copy(
            profiles = listOf(nightProfile),
            autoSwitchProfiles = true,
        )

        assertEquals(
            InterceptionDecision.INTERCEPT,
            decide(
                packageName = TIKTOK,
                settings = settings,
                gate = gateAt(dayAt(DayOfWeek.MONDAY, hour = 2)),
            ),
        )
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(
                packageName = TIKTOK,
                settings = settings,
                gate = gateAt(dayAt(DayOfWeek.MONDAY, hour = 6)),
            ),
        )
    }

    @Test
    fun `daily limit exceeded bypasses normal cooldown grace period`() {
        cooldownTracker.setCooldown(INSTAGRAM, 5 * 60_000L)

        assertEquals(
            InterceptionDecision.COOLING_DOWN,
            decide(isDailyLimitExceeded = false),
        )
        assertEquals(
            InterceptionDecision.DAILY_LIMIT_EXCEEDED,
            decide(isDailyLimitExceeded = true),
        )
    }

    @Test
    fun `daily limit exceeded absorbs splash bursts with launch guard and recovers on failure`() {
        assertEquals(
            InterceptionDecision.DAILY_LIMIT_EXCEEDED,
            decide(isDailyLimitExceeded = true),
        )

        // Inside debounce window -> DUPLICATE_EVENT.
        time.advanceBy(500L)
        assertEquals(
            InterceptionDecision.DUPLICATE_EVENT,
            decide(isDailyLimitExceeded = true),
        )

        // Past debounce window, still inside launch guard -> DUPLICATE_EVENT.
        time.advanceBy(InterceptionGate.EVENT_DEBOUNCE_MS)
        assertEquals(
            InterceptionDecision.DUPLICATE_EVENT,
            decide(isDailyLimitExceeded = true),
        )

        // Another package is unaffected by INSTAGRAM's budget launch guard.
        val both = pauseSettings(monitored = setOf(INSTAGRAM, TIKTOK))
        assertEquals(
            InterceptionDecision.DAILY_LIMIT_EXCEEDED,
            decide(packageName = TIKTOK, settings = both, isDailyLimitExceeded = true),
        )

        // After LAUNCH_GUARD_MS elapses, budget block triggers again.
        time.advanceBy(InterceptionGate.LAUNCH_GUARD_MS)
        assertEquals(
            InterceptionDecision.DAILY_LIMIT_EXCEEDED,
            decide(packageName = INSTAGRAM, settings = both, isDailyLimitExceeded = true),
        )

        // onLaunchFailed for another package does NOT clear INSTAGRAM's budget launch guard.
        gate.onLaunchFailed(TIKTOK)
        time.advanceBy(InterceptionGate.EVENT_DEBOUNCE_MS)
        assertEquals(
            InterceptionDecision.DUPLICATE_EVENT,
            decide(packageName = INSTAGRAM, settings = both, isDailyLimitExceeded = true),
        )

        // onLaunchFailed for INSTAGRAM clears its budget launch guard immediately.
        gate.onLaunchFailed(INSTAGRAM)
        assertEquals(
            InterceptionDecision.DAILY_LIMIT_EXCEEDED,
            decide(packageName = INSTAGRAM, settings = both, isDailyLimitExceeded = true),
        )
    }

    @Test
    fun `daily limit exceeded still respects ignored, disabled, unmonitored, snoozed, and locked`() {
        assertEquals(
            InterceptionDecision.IGNORED_PACKAGE,
            decide(ignoredPackages = setOf(INSTAGRAM), isDailyLimitExceeded = true),
        )
        assertEquals(
            InterceptionDecision.DISABLED,
            decide(settings = pauseSettings(enabled = false), isDailyLimitExceeded = true),
        )
        assertEquals(
            InterceptionDecision.NOT_MONITORED,
            decide(settings = pauseSettings(monitored = setOf(TIKTOK)), isDailyLimitExceeded = true),
        )
        assertEquals(
            InterceptionDecision.DEVICE_UNAVAILABLE,
            decide(isDeviceLocked = true, isDailyLimitExceeded = true),
        )

        val now = 1_000_000L
        val snoozed = pauseSettings().copy(snoozedUntilMillis = mapOf(INSTAGRAM to now + 5_000L))
        assertEquals(
            InterceptionDecision.SNOOZED,
            decide(settings = snoozed, gate = gateAt(now), isDailyLimitExceeded = true),
        )
    }

    private fun decide(
        packageName: String = INSTAGRAM,
        settings: PauseSettings = pauseSettings(),
        ignoredPackages: Set<String> = emptySet(),
        isDeviceInteractive: Boolean = true,
        isDeviceLocked: Boolean = false,
        isDailyLimitExceeded: Boolean = false,
        gate: InterceptionGate = this.gate,
    ): InterceptionDecision = gate.decide(
        packageName = packageName,
        settings = settings,
        ignoredPackages = ignoredPackages,
        isDeviceInteractive = isDeviceInteractive,
        isDeviceLocked = isDeviceLocked,
        isDailyLimitExceeded = isDailyLimitExceeded,
    )

    private fun gateAt(nowMillis: Long): InterceptionGate =
        InterceptionGate(
            cooldownTracker = cooldownTracker,
            timeSource = time,
            clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC),
        )

    private fun mondayAt(hour: Int): Long = dayAt(DayOfWeek.MONDAY, hour)

    private fun dayAt(day: DayOfWeek, hour: Int): Long {
        // 2026-11-16 is a Monday.
        val dayOfMonth = 16 + (day.value - DayOfWeek.MONDAY.value)
        return ZonedDateTime.of(2026, 11, dayOfMonth, hour, 0, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    private fun pauseSettings(
        enabled: Boolean = true,
        monitored: Set<String> = setOf(INSTAGRAM),
        paused: Set<String> = emptySet(),
    ) = PauseSettings(
        isEnabled = enabled,
        monitoredPackages = monitored,
        pausedPackages = paused,
        defaultFrictionModes = listOf(FrictionMode.BREATHING),
    )

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
    }
}
