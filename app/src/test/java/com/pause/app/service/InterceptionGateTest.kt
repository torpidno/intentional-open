package com.pause.app.service

import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.FrictionMode
import com.pause.app.util.FakeTimeSource
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

    private fun decide(
        packageName: String = INSTAGRAM,
        settings: PauseSettings = pauseSettings(),
        ignoredPackages: Set<String> = emptySet(),
        isDeviceInteractive: Boolean = true,
        isDeviceLocked: Boolean = false,
    ): InterceptionDecision = gate.decide(
        packageName = packageName,
        settings = settings,
        ignoredPackages = ignoredPackages,
        isDeviceInteractive = isDeviceInteractive,
        isDeviceLocked = isDeviceLocked,
    )

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
