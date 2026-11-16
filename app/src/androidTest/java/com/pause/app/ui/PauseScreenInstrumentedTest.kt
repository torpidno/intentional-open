package com.pause.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.pause.app.domain.model.FrictionMode
import com.pause.app.testing.PauseTestEntryPoint
import com.pause.app.testing.TEST_TARGET_PACKAGE
import com.pause.app.testing.configureBreathingSeconds
import com.pause.app.testing.resetAppState
import com.pause.app.testing.testEntryPoint
import com.pause.app.testing.visibleTexts
import com.pause.app.ui.intervention.PauseInterventionActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real pause Activity on an emulator in each friction mode.
 *
 * UiAutomator is used instead of Compose's test rule because the breathing
 * screen intentionally runs an infinite animation; UiAutomator does not wait
 * for Compose animation quiescence.
 */
@RunWith(AndroidJUnit4::class)
class PauseScreenInstrumentedTest {

    private lateinit var context: Context
    private lateinit var entryPoint: PauseTestEntryPoint
    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<PauseInterventionActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entryPoint = testEntryPoint()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        resetAppState(entryPoint)
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        scenario = null
        runBlocking { entryPoint.sessionLimitManager().cancelSession(null) }
        configureBreathingSeconds(entryPoint, 5)
    }

    @Test
    fun breathingPauseLocksContinueUntilTheCountdownEnds() {
        // Ten seconds keeps the CTA locked while the test tries to bypass it.
        configureBreathingSeconds(entryPoint, 10)
        launch(FrictionMode.BREATHING)

        val continueAction = requireNotNull(
            device.wait(Until.findObject(By.text(CONTINUE_LABEL)), 10_000L),
        ) { "Continue CTA was not rendered" }

        // Tapping the locked CTA must not dismiss the pause.
        continueAction.click()
        SystemClock.sleep(700L)
        assertNotNull("Continue dismissed the pause before it unlocked", device.findObject(By.text(PAUSING)))

        clickText(CLOSE_LABEL)
        assertTrue(device.wait(Until.gone(By.text(PAUSING)), 15_000L))

        val stats = runBlocking {
            withTimeout(10_000L) {
                entryPoint.interceptionRepository().observeTodayStats()
                    .first { it.walkedAway == 1 }
            }
        }
        assertEquals(1, stats.attempts)
        assertEquals(1, stats.walkedAway)
    }

    @Test
    fun intentionPauseUnlocksAfterChoosingAReason() {
        launch(FrictionMode.INTENTION)

        val continueAction = requireNotNull(
            device.wait(Until.findObject(By.text(CONTINUE_LABEL)), 10_000L),
        )
        continueAction.click()
        SystemClock.sleep(500L)
        assertNotNull("Continue dismissed the pause before an intention was chosen", device.findObject(By.text(PAUSING)))

        clickText("Check a message")
        clickText(CONTINUE_LABEL)

        assertTrue(
            "Pause did not close after choosing an intention and continuing",
            device.wait(Until.gone(By.text(PAUSING)), 15_000L),
        )
    }

    @Test
    fun sessionLimitPauseUnlocksAfterChoosingADuration() {
        launch(FrictionMode.TIME_LIMIT)

        val continueAction = requireNotNull(
            device.wait(Until.findObject(By.text(CONTINUE_LABEL)), 10_000L),
        )
        continueAction.click()
        SystemClock.sleep(500L)
        assertNotNull("Continue dismissed the pause before a limit was chosen", device.findObject(By.text(PAUSING)))

        clickText("5 minutes")
        clickText(CONTINUE_LABEL)

        assertTrue(
            "Pause did not close after choosing a session limit and continuing",
            device.wait(Until.gone(By.text(PAUSING)), 15_000L),
        )
    }

    private fun clickText(text: String) {
        // findObject() is a single snapshot; a slower emulator can still be
        // composing the step right after the previous click.
        val node = requireNotNull(device.wait(Until.findObject(By.text(text)), 10_000L)) {
            "Expected a UI element with text '$text'. On screen: ${visibleTexts()}"
        }
        node.click()
    }

    private fun launch(mode: FrictionMode) {
        val intent = Intent(context, PauseInterventionActivity::class.java).apply {
            putExtra(PauseInterventionActivity.EXTRA_PACKAGE_NAME, TEST_TARGET_PACKAGE)
            putExtra(PauseInterventionActivity.EXTRA_FRICTION_MODE, mode.name)
        }
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "Pause screen did not appear for $mode",
            device.wait(Until.findObject(By.text(PAUSING)), 20_000L),
        )
    }

    private companion object {
        const val PAUSING = "PAUSING"
        const val CONTINUE_LABEL = "Continue to $TEST_TARGET_PACKAGE"
        const val CLOSE_LABEL = "Close & do something else"
    }
}
