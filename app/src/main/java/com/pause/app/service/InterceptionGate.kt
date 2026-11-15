package com.pause.app.service

import com.pause.app.data.preferences.PauseSettings
import com.pause.app.util.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/** Why the accessibility service should or should not show the pause. */
enum class InterceptionDecision {
    /** Show the pause surface. */
    INTERCEPT,

    /** The master switch is off. */
    DISABLED,

    /** The app is not selected, or is temporarily paused on the dashboard. */
    NOT_MONITORED,

    /** Launchers, System UI and Pause itself are never intercepted. */
    IGNORED_PACKAGE,

    /** The screen is off or the keyguard is showing. */
    DEVICE_UNAVAILABLE,

    /** The user recently continued into this app (grace period). */
    COOLING_DOWN,

    /** A duplicate window event from the launch that was just handled. */
    DUPLICATE_EVENT,
}

/**
 * Pure decision core for interception.
 *
 * Pulled out of [PauseAccessibilityService] so the policy can be tested without
 * Android: given the current settings, ignore list, device state, cooldowns and
 * debounce window, should Pause step in?
 *
 * Every INTERCEPT also arms a short launch guard. Apps emit several window
 * events while starting (splash → main activity); the guard absorbs that burst
 * so the pause cannot loop on itself.
 *
 * Not thread-safe by design: the accessibility service calls it on the main
 * thread only.
 */
@Singleton
class InterceptionGate @Inject constructor(
    private val cooldownTracker: CooldownTracker,
    private val timeSource: TimeSource,
) {

    private var lastHandledPackage: String? = null
    private var lastHandledAtElapsedMillis: Long = 0L

    fun decide(
        packageName: String,
        settings: PauseSettings,
        ignoredPackages: Set<String>,
        isDeviceInteractive: Boolean,
        isDeviceLocked: Boolean,
    ): InterceptionDecision {
        if (packageName in ignoredPackages) return InterceptionDecision.IGNORED_PACKAGE
        if (!settings.isEnabled) return InterceptionDecision.DISABLED
        if (!settings.isMonitored(packageName)) return InterceptionDecision.NOT_MONITORED
        if (!isDeviceInteractive || isDeviceLocked) return InterceptionDecision.DEVICE_UNAVAILABLE

        val now = timeSource.elapsedRealtimeMillis()
        if (packageName == lastHandledPackage &&
            now - lastHandledAtElapsedMillis < EVENT_DEBOUNCE_MS
        ) {
            return InterceptionDecision.DUPLICATE_EVENT
        }
        if (cooldownTracker.isCoolingDown(packageName)) {
            return InterceptionDecision.COOLING_DOWN
        }

        lastHandledPackage = packageName
        lastHandledAtElapsedMillis = now
        cooldownTracker.setCooldown(packageName, LAUNCH_GUARD_MS)
        return InterceptionDecision.INTERCEPT
    }

    /**
     * Called when the OS refuses the activity start. Drops the launch guard so
     * the next real launch can try again immediately instead of being ignored.
     */
    fun onLaunchFailed(packageName: String) {
        cooldownTracker.clear(packageName)
    }

    companion object {
        /** Collapses duplicate window events for the same package. */
        const val EVENT_DEBOUNCE_MS = 1_200L

        /** Covers the splash → main-activity burst of a single launch. */
        const val LAUNCH_GUARD_MS = 4_000L
    }
}
