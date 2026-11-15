package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode

/**
 * Everything the user can configure, persisted in a single Preferences DataStore.
 *
 * The accessibility service reads [PauseSettings] from a cached StateFlow, so
 * deciding whether to intercept never blocks on disk.
 */
data class PauseSettings(
    val isEnabled: Boolean = true,
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    val breathingDurationSeconds: Int = DEFAULT_BREATHING_SECONDS,
    /** Ordered set of frictions used for every app without an override. */
    val defaultFrictionModes: List<FrictionMode> = listOf(FrictionMode.BREATHING),
    /** Every app the user selected in the app picker. */
    val monitoredPackages: Set<String> = emptySet(),
    /** Selected apps that are temporarily switched off on the home screen. */
    val pausedPackages: Set<String> = emptySet(),
    /** Per-app friction overrides. Apps without an entry use [defaultFrictionModes]. */
    val appModeOverrides: Map<String, FrictionMode> = emptyMap(),
) {
    /** True when the app should trigger a pause right now. */
    fun isMonitored(packageName: String): Boolean =
        packageName in monitoredPackages && packageName !in pausedPackages

    /**
     * The effective friction sequence for an app, in canonical order:
     * breathing -> intention -> session limit. An app override replaces the
     * whole global sequence for that app.
     */
    fun frictionModesFor(packageName: String): List<FrictionMode> {
        val override = appModeOverrides[packageName]
        val modes = if (override != null) listOf(override) else defaultFrictionModes
        return modes.distinct().sortedBy { it.ordinal }
            .ifEmpty { listOf(FrictionMode.BREATHING) }
    }

    /** The first effective mode, for call sites that need one representative. */
    fun frictionModeFor(packageName: String): FrictionMode =
        frictionModesFor(packageName).first()

    /** Package filter applied to the accessibility service (paused apps removed). */
    val effectiveMonitoredPackages: Set<String>
        get() = monitoredPackages - pausedPackages

    companion object {
        const val DEFAULT_COOLDOWN_MINUTES = 5
        const val DEFAULT_BREATHING_SECONDS = 5
        const val MAX_INTENTION_LENGTH = 140

        val COOLDOWN_OPTIONS = listOf(5, 10, 15)
        val BREATHING_OPTIONS = listOf(3, 5, 10)
        val SESSION_LIMIT_OPTIONS = listOf(5, 15, 30)
    }
}
