package com.pause.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.pause.app.BuildConfig
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.intervention.PauseInterventionActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The heart of Pause.
 *
 * Efficiency rules:
 *  1. Only TYPE_WINDOW_STATE_CHANGED is subscribed in the XML configuration.
 *  2. The service narrows AccessibilityServiceInfo.packageNames to the apps the
 *     user actually monitors, so unrelated foreground changes never wake us.
 *  3. Everything on the hot path reads an in-memory StateFlow; no disk or
 *     PackageManager work happens before the decision to intercept.
 *  4. Ignored packages (launcher, System UI, Pause itself) are dropped first.
 *
 * Correctness rules:
 *  - A device that is locked or has the screen off never shows the pause.
 *  - A short launch guard swallows the burst of window events an app emits
 *    while it starts (splash → main activity), preventing overlay loops.
 *  - A configurable cooldown keeps the pause from reappearing while the user is
 *    actively using the app or briefly switching away.
 */
@AndroidEntryPoint
class PauseAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var cooldownTracker: CooldownTracker

    @Inject
    lateinit var interceptionGate: InterceptionGate

    @Inject
    lateinit var sessionLimitManager: SessionLimitManager

    @Inject
    lateinit var sessionReminderOverlay: SessionReminderOverlay

    @Inject
    lateinit var sessionReminderNotifier: SessionReminderNotifier

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ignoredPackages: Set<String> = emptySet()
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignoredPackages = buildIgnoredPackages()
        // Resolve system services once; onAccessibilityEvent is a hot path.
        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "service connected: eventTypes=${serviceInfo?.eventTypes} " +
                    "flags=${serviceInfo?.flags}",
            )
        }
        applyPackageFilter(activePackageFilter(settingsRepository.settings.value))

        // Keep the framework-side package filter in sync with the master switch and the user's picks.
        serviceScope.launch {
            settingsRepository.settings
                .map(::activePackageFilter)
                .distinctUntilChanged()
                .collect { packages -> applyPackageFilter(packages) }
        }

        // Session-end reminders.
        serviceScope.launch {
            sessionLimitManager.finished.collect { session ->
                cooldownTracker.setCooldown(session.packageName, SESSION_END_GRACE_MS)
                // Never wake the display or draw over the keyguard for a reminder,
                // but still leave a notification for when the user looks.
                if (powerManager?.isInteractive != true || keyguardManager?.isKeyguardLocked == true) {
                    sessionReminderNotifier.show(session.appLabel)
                    return@collect
                }
                val shown = sessionReminderOverlay.show(
                    host = this@PauseAccessibilityService,
                    appLabel = session.appLabel,
                    onCloseApp = { performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) },
                    onDismiss = { /* keep using the app; no extra friction */ },
                )
                if (!shown) {
                    sessionReminderNotifier.show(session.appLabel)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "event ${event?.let { AccessibilityEvent.eventTypeToString(it.eventType) }} " +
                    "from ${event?.packageName}",
            )
        }
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val packageName = event.packageName?.toString() ?: return
        val settings = settingsRepository.settings.value

        // The gate owns the whole decision: master switch, monitoring, ignore
        // list, lock/screen-off, duplicate-event debounce and cooldowns.
        val decision = interceptionGate.decide(
            packageName = packageName,
            settings = settings,
            ignoredPackages = ignoredPackages,
            isDeviceInteractive = powerManager?.isInteractive == true,
            isDeviceLocked = keyguardManager?.isKeyguardLocked == true,
        )
        if (decision != InterceptionDecision.INTERCEPT) {
            if (BuildConfig.DEBUG) Log.d(TAG, "skip $packageName: $decision")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "intercept $packageName")

        launchIntervention(packageName, settings.frictionModesFor(packageName))
    }

    private fun launchIntervention(packageName: String, modes: List<FrictionMode>) {
        val intent = PauseInterventionActivity.intent(this, packageName, modes)
        runCatching { startActivity(intent) }
            .onFailure {
                // If the OS refuses the background activity start (for example
                // because "Display over other apps" was never granted) let the
                // next real app launch try again instead of blocking for minutes.
                if (BuildConfig.DEBUG) Log.d(TAG, "launch failed for $packageName", it)
                interceptionGate.onLaunchFailed(packageName)
            }
    }

    /**
     * Builds the ignore list. Launchers are resolved rather than hard-coded so
     * third-party launchers (Nova, Lawnchair, OEM launchers) are all covered.
     */
    private fun buildIgnoredPackages(): Set<String> {
        val ignored = mutableSetOf(
            "android",
            packageName,
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        ).filter { it.priority >= 0 }
            .mapNotNullTo(ignored) { it.activityInfo?.packageName }
        return ignored
    }

    /**
     * Returns the package filter to register with the framework. When Pause is
     * switched off globally, return an empty set so the framework does not wake
     * the service for monitored apps only for [InterceptionGate] to drop them.
     */
    private fun activePackageFilter(settings: PauseSettings): Set<String> =
        if (settings.isEnabled) settings.effectiveMonitoredPackages else emptySet()

    /**
     * Applies the runtime package filter. An empty selection is replaced with
     * our own package so the framework has literally nothing else to deliver.
     */
    private fun applyPackageFilter(packages: Set<String>) {
        val info = serviceInfo ?: return
        info.packageNames = if (packages.isEmpty()) {
            arrayOf(packageName)
        } else {
            packages.toTypedArray()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "package filter: ${info.packageNames?.joinToString()}")
        runCatching { setServiceInfo(info) }
            .onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "setServiceInfo failed", it) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        sessionReminderOverlay.hide()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PauseInterception"

        /** Gives the user a moment after a session ends before pausing again. */
        const val SESSION_END_GRACE_MS = 60_000L
    }
}
