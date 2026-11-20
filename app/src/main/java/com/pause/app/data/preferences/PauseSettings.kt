package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.domain.model.isWithinScheduleWindow
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
    /** Optional per-app daily usage budgets, in positive minutes. */
    val dailyLimitsMinutes: Map<String, Int> = emptyMap(),
    /** Saved configuration profiles (Work, Evening, Weekend, custom). */
    val profiles: List<PauseProfile> = emptyList(),
    /** Currently selected manual profile id, or null when unlinked. */
    val activeProfileId: String? = null,
    /** When true, scheduled profiles take effect automatically during their window. */
    val autoSwitchProfiles: Boolean = false,
    /** When true, weakening protections requires completing a brief challenge. */
    val strictModeEnabled: Boolean = false,
    val strictModeChallenge: StrictModeChallenge = StrictModeChallenge.DELAY,
    val strictModeDelaySeconds: Int = DEFAULT_STRICT_MODE_DELAY_SECONDS,
    val strictModePhrase: String = DEFAULT_STRICT_MODE_PHRASE,
) {
    /** True when the app is selected and not switched off indefinitely. */
    fun isMonitored(packageName: String): Boolean =
        packageName in monitoredPackages && packageName !in pausedPackages

    /** True while a temporary snooze is still in effect. */
    fun isSnoozed(packageName: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        (snoozedUntilMillis[packageName] ?: 0L) > nowMillis

    /**
     * Global active-hours check. [now] must be in the device's local zone.
     * A start equal to the end means the whole day is active. Overnight windows
     * (`start > end`) belong to the day they start on, so their post-midnight
     * portion checks whether the previous day is selected.
     */
    fun isScheduleActive(now: ZonedDateTime): Boolean {
        if (!scheduleEnabled) return true
        return isWithinScheduleWindow(
            now = now,
            scheduleDays = scheduleDays,
            scheduleStartMinutes = scheduleStartMinutes,
            scheduleEndMinutes = scheduleEndMinutes,
        )
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

    /** Optional daily usage limit for [packageName] in minutes, or null when unset. */
    fun dailyLimitMinutesFor(packageName: String): Int? =
        dailyLimitsMinutes[packageName]?.takeIf { it > 0 }

    /** Currently selected profile, if [activeProfileId] matches a saved profile. */
    val activeProfile: PauseProfile?
        get() = activeProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

    /**
     * First scheduled profile whose active window matches [now] when
     * [autoSwitchProfiles] is enabled; null otherwise.
     */
    fun scheduledProfileAt(now: ZonedDateTime): PauseProfile? =
        if (!autoSwitchProfiles) {
            null
        } else {
            profiles.firstOrNull { it.scheduleEnabled && it.isScheduleActive(now) }
        }

    /**
     * Resolves the settings active at [now], overlaying any matching scheduled
     * profile when [autoSwitchProfiles] is enabled.
     */
    fun effectiveAt(now: ZonedDateTime): PauseSettings {
        val matched = scheduledProfileAt(now) ?: return this
        return copy(
            activeProfileId = matched.id,
            monitoredPackages = matched.monitoredPackages,
            pausedPackages = matched.pausedPackages,
            defaultFrictionModes = matched.defaultFrictionModes
                .distinct()
                .ifEmpty { listOf(FrictionMode.BREATHING) },
            appModeOverrides = matched.appModeOverrides,
            scheduleEnabled = true,
            scheduleDays = matched.scheduleDays,
            scheduleStartMinutes = matched.scheduleStartMinutes,
            scheduleEndMinutes = matched.scheduleEndMinutes,
        )
    }

    /** Package filter applied to the accessibility service (paused apps removed). */
    val effectiveMonitoredPackages: Set<String>
        get() = monitoredPackages - pausedPackages

    /**
     * Complete set of packages the accessibility service should listen for,
     * including any scheduled profiles when [autoSwitchProfiles] is enabled.
     */
    val allAccessibleMonitoredPackages: Set<String>
        get() = when {
            !isEnabled -> emptySet()
            autoSwitchProfiles ->
                effectiveMonitoredPackages +
                    profiles
                        .filter { it.scheduleEnabled }
                        .flatMap { it.effectiveMonitoredPackages }
            else -> effectiveMonitoredPackages
        }

    /** Non-blank confirmation phrase used for [StrictModeChallenge.PHRASE]. */
    val effectiveStrictModePhrase: String
        get() = strictModePhrase.trim().ifEmpty { DEFAULT_STRICT_MODE_PHRASE }

    companion object {
        const val DEFAULT_COOLDOWN_MINUTES = 5
        const val DEFAULT_BREATHING_SECONDS = 5
        const val MAX_INTENTION_LENGTH = 140

        const val DEFAULT_SCHEDULE_START_MINUTES = 9 * 60
        const val DEFAULT_SCHEDULE_END_MINUTES = 18 * 60
        const val DEFAULT_HISTORY_RETENTION_DAYS = 0
        const val MINUTES_PER_DAY = 24 * 60

        const val DEFAULT_STRICT_MODE_DELAY_SECONDS = 15
        const val DEFAULT_STRICT_MODE_PHRASE = "I choose to change this"
        const val MAX_STRICT_MODE_PHRASE_LENGTH = 80
        const val MIN_STRICT_MODE_DELAY_SECONDS = 5
        const val MAX_STRICT_MODE_DELAY_SECONDS = 300

        val COOLDOWN_OPTIONS = listOf(5, 10, 15)
        val BREATHING_OPTIONS = listOf(3, 5, 10)
        val SESSION_LIMIT_OPTIONS = listOf(5, 15, 30)
        val HISTORY_RETENTION_OPTIONS = listOf(30, 90, 0)
        val DAILY_LIMIT_OPTIONS = listOf(15, 30, 45, 60, 90, 120)
        val STRICT_MODE_DELAY_OPTIONS = listOf(15, 30, 60)
    }
}
