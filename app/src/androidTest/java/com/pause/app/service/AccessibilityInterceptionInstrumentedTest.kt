package com.pause.app.service

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.pause.app.domain.model.FrictionMode
import com.pause.app.testing.AccessibilityServiceController
import com.pause.app.testing.PauseTestEntryPoint
import com.pause.app.testing.SETTINGS_PACKAGE
import com.pause.app.testing.Shell
import com.pause.app.testing.resetAppState
import com.pause.app.testing.testEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full interception path on an emulator:
 *
 *   monitored app enters foreground
 *     -> real AccessibilityService receives TYPE_WINDOW_STATE_CHANGED
 *     -> InterceptionGate allows it
 *     -> PauseInterventionActivity is launched over the app
 *     -> "Close" goes Home and a WALKED_AWAY row is written to Room.
 *
 * Uses the system Settings app as the target so no external APK is needed.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityInterceptionInstrumentedTest {

    private lateinit var context: Context
    private lateinit var entryPoint: PauseTestEntryPoint
    private lateinit var device: UiDevice
    private lateinit var serviceController: AccessibilityServiceController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entryPoint = testEntryPoint()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        serviceController = AccessibilityServiceController(context)

        val settingsInstalled = runCatching {
            context.packageManager.getApplicationInfo(SETTINGS_PACKAGE, 0)
        }.isSuccess
        assumeTrue(
            "This test needs the AOSP/Google Settings package ($SETTINGS_PACKAGE)",
            settingsInstalled,
        )

        resetAppState(entryPoint)
        runBlocking {
            entryPoint.settingsRepository().apply {
                setEnabled(true)
                setMonitored(SETTINGS_PACKAGE, true)
                setDefaultFrictionMode(FrictionMode.BREATHING)
                setBreathingDurationSeconds(10)
                setCooldownMinutes(5)
            }
            withTimeout(5_000L) {
                entryPoint.settingsRepository().settings
                    .first { it.isMonitored(SETTINGS_PACKAGE) }
            }
        }

        // Overlay permission gives the service a background-activity-start
        // exemption on top of the accessibility-service exemption.
        Shell.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        serviceController.enable()

        // Let the service apply its runtime package filter.
        SystemClock.sleep(2_000L)
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        serviceController.restore()
        runBlocking {
            entryPoint.sessionLimitManager().cancelSession()
            entryPoint.settingsRepository().apply {
                setMonitored(SETTINGS_PACKAGE, false)
                setBreathingDurationSeconds(5)
            }
            withContext(Dispatchers.IO) { entryPoint.pauseDatabase().clearAllTables() }
        }
    }

    @Test
    fun openingAMonitoredAppShowsPauseAndClosingRecordsAWalkAway() {
        // Start the monitored app through the shell so the launch is independent
        // of the instrumentation's own background-activity restrictions.
        Shell.run("am start -a android.settings.SETTINGS -f 0x10000000")

        val pause = device.wait(Until.findObject(By.text("PAUSING")), 30_000L)
        assertNotNull(
            "Pause screen did not appear after opening $SETTINGS_PACKAGE",
            pause,
        )

        val close = requireNotNull(
            device.wait(Until.findObject(By.text("Close & do something else")), 5_000L),
        ) { "Close CTA missing from the pause screen" }
        close.click()

        assertTrue(
            "Pause screen did not close after walking away",
            device.wait(Until.gone(By.text("PAUSING")), 15_000L),
        )

        runBlocking {
            val stats = withTimeout(15_000L) {
                entryPoint.interceptionRepository().observeTodayStats()
                    .first { it.walkedAway >= 1 }
            }
            assertTrue("Expected at least one intercepted attempt", stats.attempts >= 1)
            assertTrue("Expected the walk-away to be recorded", stats.walkedAway >= 1)
        }
    }
}
