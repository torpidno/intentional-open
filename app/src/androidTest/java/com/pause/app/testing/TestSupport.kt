package com.pause.app.testing

import android.app.Application
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pause.app.domain.model.FrictionMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A package that is guaranteed not to be installed; Continue ends on Home. */
const val TEST_TARGET_PACKAGE = "com.pause.test.target"

/** A launchable system app that exists on AOSP and Google emulator images. */
const val SETTINGS_PACKAGE = "com.android.settings"

/**
 * Resolves the debug-only test entry point from the real Hilt application.
 * Instrumentation runs inside the app process, so this is the same graph the
 * service and activities use.
 */
fun testEntryPoint(): PauseTestEntryPoint {
    val application = ApplicationProvider.getApplicationContext<Application>()
    return EntryPointAccessors.fromApplication(application, PauseTestEntryPoint::class.java)
}

/** Small wrapper around UiAutomation's shell execution for emulator setup. */
object Shell {
    /**
     * A plain UiAutomation connection - the one UiDevice opens - temporarily
     * disables every other accessibility service, including Pause's own. That
     * makes `enabled_accessibility_services` look accepted while the service
     * can never connect, so shell access here always reconnects with
     * FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES.
     */
    private fun automation(): UiAutomation =
        InstrumentationRegistry.getInstrumentation().getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )

    fun run(command: String): String {
        val automation = automation()
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }
}

/**
 * Every text currently exposed to accessibility in the window hierarchy.
 *
 * Compose only publishes semantics for content that is not clipped away, so this
 * doubles as a "what is actually on screen" dump when a UI assertion fails.
 */
fun visibleTexts(): List<String> {
    val device = androidx.test.uiautomator.UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation(),
    )
    val out = java.io.ByteArrayOutputStream()
    runCatching { device.dumpWindowHierarchy(out) }
    return Regex("text=\"([^\"]+)\"")
        .findAll(out.toString("UTF-8"))
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

/** Polling helper for framework state that has no Flow to observe. */
fun waitUntil(
    timeoutMillis: Long = 10_000L,
    intervalMillis: Long = 100L,
    description: String = "condition",
    condition: () -> Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return
        SystemClock.sleep(intervalMillis)
    }
    throw AssertionError("Timed out waiting for $description after $timeoutMillis ms")
}

/**
 * Clears history and resets the settings that the instrumented tests rely on.
 * Intended for a disposable emulator.
 */
fun resetAppState(entryPoint: PauseTestEntryPoint) = runBlocking {
    withContext(Dispatchers.IO) {
        entryPoint.pauseDatabase().clearAllTables()
    }
    entryPoint.sessionLimitManager().cancelSession(null)
    entryPoint.settingsRepository().apply {
        setEnabled(true)
        setDefaultFrictionMode(FrictionMode.BREATHING)
        setBreathingDurationSeconds(5)
        setCooldownMinutes(5)
    }
}

/** Writes the breathing duration and waits for the hot StateFlow to observe it. */
fun configureBreathingSeconds(entryPoint: PauseTestEntryPoint, seconds: Int) = runBlocking {
    entryPoint.settingsRepository().setBreathingDurationSeconds(seconds)
    withTimeout(10_000L) {
        entryPoint.settingsRepository().settings.first { it.breathingDurationSeconds == seconds }
    }
}

/** Enables/restores the accessibility service without leaving the emulator changed. */
class AccessibilityServiceController(private val context: android.content.Context) {

    private val component = android.content.ComponentName(
        context,
        com.pause.app.service.PauseAccessibilityService::class.java,
    )

    private var previousEnabledServices: String? = null
    private var previousAccessibilityEnabled: String? = null
    private var wasEnabled = false

    fun enable() {
        previousEnabledServices =
            Shell.run("settings get secure enabled_accessibility_services").trim()
        previousAccessibilityEnabled =
            Shell.run("settings get secure accessibility_enabled").trim()

        // Sideloaded debug builds on API 33+ need the restricted-settings app-op
        // before the accessibility service can be switched on.
        Shell.run("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
        Shell.run(
            "settings put secure enabled_accessibility_services ${component.flattenToString()}",
        )
        Shell.run("settings put secure accessibility_enabled 1")
        wasEnabled = true

        waitUntil(
            timeoutMillis = 20_000L,
            description = "Pause accessibility service to connect",
        ) { isConnected() }
    }

    fun restore() {
        if (!wasEnabled) return
        wasEnabled = false

        val accessibilityEnabled = previousAccessibilityEnabled
        if (accessibilityEnabled.isNullOrBlank() || accessibilityEnabled == "null") {
            Shell.run("settings delete secure accessibility_enabled")
        } else {
            Shell.run("settings put secure accessibility_enabled $accessibilityEnabled")
        }

        val services = previousEnabledServices
        if (services.isNullOrBlank() || services == "null") {
            Shell.run("settings delete secure enabled_accessibility_services")
        } else {
            Shell.run("settings put secure enabled_accessibility_services $services")
        }
    }

    private fun isConnected(): Boolean {
        val manager = context.getSystemService(
            android.view.accessibility.AccessibilityManager::class.java,
        ) ?: return false
        return manager
            .getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }
}
