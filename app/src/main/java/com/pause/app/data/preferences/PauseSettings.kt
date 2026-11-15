package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import java.time.DayOfWeek
import java.time.ZonedDateTime

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
    /** Ordered frictions used for every app without an override. */
    val defaultFrictionModes: List<FrictionMode> = listOf(FrictionMode.BREATHING),
    /** Every app the user selected in the app picker. */
    val monitoredPackages: Set<String> = emptySet(),
    /** Selected apps that are temporarily switched off on the home screen. */
    val pausedPackages: Set<String> = emptySet(),
    /** Per-app friction sequences. Apps without an entry use [defaultFrictionModes]. */
    val appModeOverrides: Map<String, List<FrictionMode>> = emptyMap(),
    /** Global active-hours schedule. Disabled means all hours are active. */
    val scheduleEnabled: Boolean = false,
    val scheduleDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Minutes since local midnight. Overnight windows are supported. */
    val scheduleStartMinutes: Int = DEFAULT_SCHEDULE_START_MINUTES,
    val scheduleEndMinutes: Int = DEFAULT_SCHEDULE_END_MINUTES,
    /** Temporary per-app mute deadlines, in epoch milliseconds. */
    val snoozedUntilMillis: Map<String, Long> = emptyMap(),
    /** Days of history to retain; 0 keeps history forever. */
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
) {
    /** True when the app is selected and not switched off indefinitely. */
    fun isMonitored(packageName: String): Boolean =
        packageName in monitoredPackages && packageName !in pausedPackages

    /** True while a temporary snooze is still in effect. */
    fun isSnoozed(packageName: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        (snoozedUntilMillis[packageName] ?: 0L) > nowMillis

    /**
     * Global active-hours check. [now] must be in the device's local zone.
     * A start equal to the end means the whole day is active.
     */
    fun isScheduleActive(now: ZonedDateTime): Boolean {
        if (!scheduleEnabled) return true
        if (scheduleDays.isEmpty() || now.dayOfWeek !in scheduleDays) return false

        val minuteOfDay = now.hour * 60 + now.minute
        val start = scheduleStartMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = scheduleEndMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /**
     * The effective friction sequence for an app, in the order the user chose.
     * An app override replaces the whole global sequence for that app.
     */
    fun frictionModesFor(packageName: String): List<FrictionMode> {
        val override = appModeOverrides[packageName]?.takeIf { it.isNotEmpty() }
        val modes = override ?: defaultFrictionModes
        return modes.distinct().ifEmpty { listOf(FrictionMode.BREATHING) }
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

        const val DEFAULT_SCHEDULE_START_MINUTES = 9 * 60
        const val DEFAULT_SCHEDULE_END_MINUTES = 18 * 60
        const val DEFAULT_HISTORY_RETENTION_DAYS = 0
        const val MINUTES_PER_DAY = 24 * 60

        val COOLDOWN_OPTIONS = listOf(5, 10, 15)
        val BREATHING_OPTIONS = listOf(3, 5, 10)
        val SESSION_LIMIT_OPTIONS = listOf(5, 15, 30)
        val HISTORY_RETENTION_OPTIONS = listOf(30, 90, 0)
    }
}
