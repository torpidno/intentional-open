package com.pause.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.pause.app.di.ApplicationScope
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.domain.model.decodeProfiles
import com.pause.app.domain.model.encodeProfiles
import com.pause.app.domain.model.moveFrictionMode
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Single source of truth for preferences.
 *
 * Writes are serialized by DataStore. The exposed [settings] StateFlow is kept
 * hot (Eagerly) so the accessibility service can check a value synchronously.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    val settings: StateFlow<PauseSettings> = dataStore.data
        .catch { throwable ->
            // A corrupt or unreadable file should never crash the service; fall
            // back to defaults instead of propagating an IOException.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toPauseSettings() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = PauseSettings(),
        )

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setCooldownMinutes(minutes: Int) {
        if (minutes <= 0) return
        dataStore.edit { it[Keys.COOLDOWN_MINUTES] = minutes }
    }

    suspend fun setBreathingDurationSeconds(seconds: Int) {
        if (seconds <= 0) return
        dataStore.edit { it[Keys.BREATHING_SECONDS] = seconds }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.SCHEDULE_ENABLED] = enabled
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Sets an inclusive start and exclusive end, in minutes since midnight. */
    suspend fun setScheduleWindow(startMinutes: Int, endMinutes: Int) {
        if (startMinutes !in 0 until PauseSettings.MINUTES_PER_DAY) return
        if (endMinutes !in 0 until PauseSettings.MINUTES_PER_DAY) return
        dataStore.edit { preferences ->
            preferences[Keys.SCHEDULE_START_MINUTES] = startMinutes
            preferences[Keys.SCHEDULE_END_MINUTES] = endMinutes
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Toggles a schedule day while keeping at least one active day. */
    suspend fun toggleScheduleDay(day: DayOfWeek) {
        dataStore.edit { preferences ->
            val days = preferences.scheduleDays().toMutableSet()
            if (day in days) {
                if (days.size == 1) return@edit
                days -= day
            } else {
                days += day
            }
            preferences[Keys.SCHEDULE_DAYS] = days.map { it.name }.toSet()
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Temporarily switches an app off for [minutes]; the deadline is checked by the gate. */
    suspend fun snoozeApp(packageName: String, minutes: Int) {
        if (packageName.isBlank() || minutes <= 0) return
        setAppSnoozedUntil(
            packageName = packageName,
            untilMillis = clock.millis() + TimeUnit.MINUTES.toMillis(minutes.toLong()),
        )
    }

    suspend fun setAppSnoozedUntil(packageName: String, untilMillis: Long) {
        if (packageName.isBlank() || untilMillis <= 0L) return
        dataStore.edit { preferences ->
            val now = clock.millis()
            val existing = preferences.snoozedUntilMillis()
            val snoozes = existing.filterValues { it > now }.toMutableMap()
            if (untilMillis > now) {
                snoozes[packageName] = untilMillis
            } else {
                snoozes.remove(packageName)
            }
            if (snoozes != existing) {
                preferences[Keys.SNOOZED_UNTIL] = encodeSnoozes(snoozes)
            }
        }
    }

    suspend fun clearAppSnooze(packageName: String) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            preferences.pruneAndRemoveSnooze(packageName)
        }
    }

    /** 0 keeps history forever; other values are positive day counts. */
    suspend fun setHistoryRetentionDays(days: Int) {
        if (days < 0 || days > 3650) return
        dataStore.edit { it[Keys.HISTORY_RETENTION_DAYS] = days }
    }

    /**
     * Reads the value directly from disk instead of the Eagerly-seeded flow, so
     * startup maintenance cannot mistake the temporary default for the user's
     * chosen retention policy.
     */
    suspend fun currentHistoryRetentionDays(): Int =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .first()[Keys.HISTORY_RETENTION_DAYS]
            ?: PauseSettings.DEFAULT_HISTORY_RETENTION_DAYS

    /** Sets an optional daily usage budget in minutes, or removes it when null or <= 0. */
    suspend fun setDailyLimitMinutes(packageName: String, minutes: Int?) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            val limits = decodeDailyLimits(preferences[Keys.DAILY_LIMITS_MINUTES].orEmpty())
                .toMutableMap()
            if (minutes == null || minutes <= 0) {
                limits.remove(packageName)
            } else {
                limits[packageName] = minutes
            }
            preferences[Keys.DAILY_LIMITS_MINUTES] = encodeDailyLimits(limits)
        }
    }

    /**
     * Upserts [profile] by [PauseProfile.id], ignoring blank ids or names.
     * When [activate] is true or [profile] is already active, top-level keys are
     * atomically synced from [profile] in the same DataStore edit.
     */
    suspend fun saveProfile(profile: PauseProfile, activate: Boolean = false) {
        val normalized = profile.normalized() ?: return
        dataStore.edit { preferences ->
            val currentActiveId = preferences[Keys.ACTIVE_PROFILE_ID]?.takeIf { it.isNotBlank() }
            if (activate && currentActiveId != null && currentActiveId != normalized.id) {
                preferences.syncActiveProfileFromTopLevel()
            }
            val profiles = decodeProfiles(preferences[Keys.PROFILES_JSON].orEmpty()).toMutableList()
            val existingIndex = profiles.indexOfFirst { it.id == normalized.id }
            if (existingIndex >= 0) {
                profiles[existingIndex] = normalized
            } else {
                profiles += normalized
            }
            preferences[Keys.PROFILES_JSON] = encodeProfiles(profiles)
            if (activate || currentActiveId == normalized.id) {
                preferences.applyProfileToTopLevel(normalized)
                preferences[Keys.ACTIVE_PROFILE_ID] = normalized.id
            }
        }
    }

    /**
     * Atomically snapshots current top-level settings into the currently active
     * profile (if any), then switches to [profileId] (or unlinks when null).
     */
    suspend fun selectProfile(profileId: String?) {
        dataStore.edit { preferences ->
            preferences.syncActiveProfileFromTopLevel()
            if (profileId.isNullOrBlank()) {
                preferences.remove(Keys.ACTIVE_PROFILE_ID)
                return@edit
            }
            val target = decodeProfiles(preferences[Keys.PROFILES_JSON].orEmpty())
                .firstOrNull { it.id == profileId }
                ?: return@edit
            preferences.applyProfileToTopLevel(target)
            preferences[Keys.ACTIVE_PROFILE_ID] = target.id
        }
    }

    /**
     * Removes the profile with [profileId]. If it was active, [Keys.ACTIVE_PROFILE_ID]
     * is cleared while keeping the user's current top-level settings intact.
     */
    suspend fun deleteProfile(profileId: String) {
        if (profileId.isBlank()) return
        dataStore.edit { preferences ->
            val profiles = decodeProfiles(preferences[Keys.PROFILES_JSON].orEmpty())
            val remaining = profiles.filterNot { it.id == profileId }
            if (remaining.size != profiles.size) {
                preferences[Keys.PROFILES_JSON] = encodeProfiles(remaining)
            }
            if (preferences[Keys.ACTIVE_PROFILE_ID] == profileId) {
                preferences.remove(Keys.ACTIVE_PROFILE_ID)
            }
        }
    }

    suspend fun setAutoSwitchProfiles(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SWITCH_PROFILES] = enabled }
    }

    suspend fun setStrictModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.STRICT_MODE_ENABLED] = enabled }
    }

    suspend fun setStrictModeChallenge(challenge: StrictModeChallenge) {
        dataStore.edit { it[Keys.STRICT_MODE_CHALLENGE] = challenge.name }
    }

    suspend fun setStrictModeDelaySeconds(seconds: Int) {
        if (seconds !in PauseSettings.MIN_STRICT_MODE_DELAY_SECONDS..PauseSettings.MAX_STRICT_MODE_DELAY_SECONDS) {
            return
        }
        dataStore.edit { it[Keys.STRICT_MODE_DELAY_SECONDS] = seconds }
    }

    suspend fun setStrictModePhrase(phrase: String) {
        val normalized = phrase.trim().take(PauseSettings.MAX_STRICT_MODE_PHRASE_LENGTH)
        dataStore.edit { it[Keys.STRICT_MODE_PHRASE] = normalized }
    }

    /** Compatibility helper for a single-mode default; used by tests/imports. */
    suspend fun setDefaultFrictionMode(mode: FrictionMode) {
        setDefaultFrictionModes(listOf(mode))
    }

    suspend fun setDefaultFrictionModes(modes: Collection<FrictionMode>) {
        val normalized = normalizeFrictionModes(modes)
        if (normalized.isEmpty()) return
        dataStore.edit { preferences ->
            preferences.writeDefaultFrictionModes(normalized)
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Toggles one mode while guaranteeing at least one remains enabled. */
    suspend fun toggleDefaultFrictionMode(mode: FrictionMode) {
        dataStore.edit { preferences ->
            val current = preferences.defaultFrictionModes()
            val next = if (mode in current) current - mode else current + mode
            if (next.isEmpty()) return@edit
            preferences.writeDefaultFrictionModes(normalizeFrictionModes(next))
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Moves a mode by one position in the global sequence. */
    suspend fun moveDefaultFrictionMode(mode: FrictionMode, offset: Int) {
        dataStore.edit { preferences ->
            val current = preferences.defaultFrictionModes()
            val moved = current.moveFrictionMode(mode, offset)
            if (moved != current) {
                preferences.writeDefaultFrictionModes(moved)
                preferences.syncActiveProfileFromTopLevel()
            }
        }
    }

    /** Adds or removes an app from the monitored list. */
    suspend fun setMonitored(packageName: String, monitored: Boolean) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            val monitoredPackages = preferences[Keys.MONITORED_PACKAGES].orEmpty().toMutableSet()
            val pausedPackages = preferences[Keys.PAUSED_PACKAGES].orEmpty().toMutableSet()
            if (monitored) {
                monitoredPackages += packageName
                pausedPackages -= packageName
            } else {
                monitoredPackages -= packageName
                pausedPackages -= packageName
                preferences.pruneAndRemoveSnooze(packageName)
            }
            preferences[Keys.MONITORED_PACKAGES] = monitoredPackages
            preferences[Keys.PAUSED_PACKAGES] = pausedPackages
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /**
     * Atomically flips monitoring for an app. Used by the multi-select picker so
     * quick successive taps cannot read a stale selection.
     */
    suspend fun toggleMonitored(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        var nowMonitored = false
        dataStore.edit { preferences ->
            val monitoredPackages = preferences[Keys.MONITORED_PACKAGES].orEmpty().toMutableSet()
            val pausedPackages = preferences[Keys.PAUSED_PACKAGES].orEmpty().toMutableSet()
            nowMonitored = packageName !in monitoredPackages
            if (nowMonitored) {
                monitoredPackages += packageName
            } else {
                monitoredPackages -= packageName
                preferences.pruneAndRemoveSnooze(packageName)
            }
            pausedPackages -= packageName
            preferences[Keys.MONITORED_PACKAGES] = monitoredPackages
            preferences[Keys.PAUSED_PACKAGES] = pausedPackages
            preferences.syncActiveProfileFromTopLevel()
        }
        return nowMonitored
    }

    /** Temporarily switches a monitored app off without forgetting its settings. */
    suspend fun setAppPaused(packageName: String, paused: Boolean) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            val pausedPackages = preferences[Keys.PAUSED_PACKAGES].orEmpty().toMutableSet()
            if (paused) pausedPackages += packageName else pausedPackages -= packageName
            preferences[Keys.PAUSED_PACKAGES] = pausedPackages
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    /** Compatibility helper for a single-mode app override. */
    suspend fun setAppModeOverride(packageName: String, mode: FrictionMode?) {
        setAppModeOverrides(packageName, mode?.let { listOf(it) })
    }

    /** Pass null or an empty list to fall back to the global sequence. */
    suspend fun setAppModeOverrides(
        packageName: String,
        modes: Collection<FrictionMode>?,
    ) {
        if (packageName.isBlank()) return
        dataStore.edit { preferences ->
            val overrides = decodeOverrides(preferences[Keys.APP_MODE_OVERRIDES].orEmpty())
                .toMutableMap()
            val normalized = modes?.let { normalizeFrictionModes(it) }.orEmpty()
            if (normalized.isEmpty()) {
                overrides.remove(packageName)
            } else {
                overrides[packageName] = normalized
            }
            preferences[Keys.APP_MODE_OVERRIDES] = encodeOverrides(overrides)
            preferences.syncActiveProfileFromTopLevel()
        }
    }

    private fun MutablePreferences.pruneAndRemoveSnooze(packageName: String) {
        val now = clock.millis()
        val existing = snoozedUntilMillis()
        val updated = existing.filter { (pkg, until) -> pkg != packageName && until > now }
        if (updated != existing) {
            this[Keys.SNOOZED_UNTIL] = encodeSnoozes(updated)
        }
    }
}

private fun PauseProfile.normalized(): PauseProfile? {
    val cleanId = id.trim()
    val cleanName = name.trim()
    if (cleanId.isEmpty() || cleanName.isEmpty()) return null
    val cleanMonitored = monitoredPackages.filter { it.isNotBlank() }.toSet()
    val cleanPaused = pausedPackages.filter { it.isNotBlank() }.toSet()
    val cleanModes = normalizeFrictionModes(defaultFrictionModes)
        .ifEmpty { listOf(FrictionMode.BREATHING) }
    val cleanOverrides = appModeOverrides.entries
        .mapNotNull { (pkg, modes) ->
            val cleanPkg = pkg.trim()
            val normalizedModes = normalizeFrictionModes(modes)
            if (cleanPkg.isEmpty() || normalizedModes.isEmpty()) null else cleanPkg to normalizedModes
        }
        .toMap()
    return copy(
        id = cleanId,
        name = cleanName,
        monitoredPackages = cleanMonitored,
        pausedPackages = cleanPaused,
        defaultFrictionModes = cleanModes,
        appModeOverrides = cleanOverrides,
        scheduleStartMinutes = scheduleStartMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1),
        scheduleEndMinutes = scheduleEndMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1),
    )
}

private fun MutablePreferences.applyProfileToTopLevel(profile: PauseProfile) {
    val modes = normalizeFrictionModes(profile.defaultFrictionModes)
        .ifEmpty { listOf(FrictionMode.BREATHING) }
    this[Keys.MONITORED_PACKAGES] = profile.monitoredPackages
    this[Keys.PAUSED_PACKAGES] = profile.pausedPackages
    writeDefaultFrictionModes(modes)
    this[Keys.APP_MODE_OVERRIDES] = encodeOverrides(profile.appModeOverrides)
    this[Keys.SCHEDULE_ENABLED] = profile.scheduleEnabled
    this[Keys.SCHEDULE_DAYS] = profile.scheduleDays.map { it.name }.toSet()
    this[Keys.SCHEDULE_START_MINUTES] =
        profile.scheduleStartMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1)
    this[Keys.SCHEDULE_END_MINUTES] =
        profile.scheduleEndMinutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1)
}

private fun MutablePreferences.syncActiveProfileFromTopLevel() {
    val activeId = this[Keys.ACTIVE_PROFILE_ID]?.takeIf { it.isNotBlank() } ?: return
    val profiles = decodeProfiles(this[Keys.PROFILES_JSON].orEmpty())
    val index = profiles.indexOfFirst { it.id == activeId }
    if (index < 0) return
    val existing = profiles[index]
    val updated = existing.copy(
        monitoredPackages = this[Keys.MONITORED_PACKAGES].orEmpty(),
        pausedPackages = this[Keys.PAUSED_PACKAGES].orEmpty(),
        defaultFrictionModes = defaultFrictionModes(),
        appModeOverrides = decodeOverrides(this[Keys.APP_MODE_OVERRIDES].orEmpty()),
        scheduleEnabled = this[Keys.SCHEDULE_ENABLED] ?: false,
        scheduleDays = scheduleDays(),
        scheduleStartMinutes = this[Keys.SCHEDULE_START_MINUTES]
            ?: PauseSettings.DEFAULT_SCHEDULE_START_MINUTES,
        scheduleEndMinutes = this[Keys.SCHEDULE_END_MINUTES]
            ?: PauseSettings.DEFAULT_SCHEDULE_END_MINUTES,
    )
    if (updated != existing) {
        val mutable = profiles.toMutableList()
        mutable[index] = updated
        this[Keys.PROFILES_JSON] = encodeProfiles(mutable)
    }
}

// Package names only contain letters, digits, dots and underscores, so these
// simple separators are unambiguous and keep the DataStore value human-readable.
private const val ENTRY_SEPARATOR = "|"
private const val VALUE_SEPARATOR = "="
private const val MODE_SEPARATOR = ","

internal fun encodeOverrides(overrides: Map<String, List<FrictionMode>>): String =
    overrides.entries
        .mapNotNull { (packageName, modes) ->
            val normalized = normalizeFrictionModes(modes)
            normalized.takeIf { it.isNotEmpty() }?.let { packageName to it }
        }
        .sortedBy { it.first }
        .joinToString(ENTRY_SEPARATOR) { (packageName, modes) ->
            "$packageName$VALUE_SEPARATOR${encodeFrictionSequence(modes)}"
        }

internal fun decodeOverrides(raw: String): Map<String, List<FrictionMode>> =
    raw.split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(VALUE_SEPARATOR, limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            val modes = decodeFrictionSequence(parts[1])
            if (modes.isEmpty()) return@mapNotNull null
            parts[0] to modes
        }
        .toMap()

internal fun encodeDailyLimits(limits: Map<String, Int>): String =
    limits.entries
        .filter { it.key.isNotBlank() && it.value > 0 }
        .sortedBy { it.key }
        .joinToString(ENTRY_SEPARATOR) { (packageName, minutes) ->
            "$packageName$VALUE_SEPARATOR$minutes"
        }

internal fun decodeDailyLimits(raw: String): Map<String, Int> =
    raw.split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(VALUE_SEPARATOR, limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            val minutes = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (minutes <= 0) return@mapNotNull null
            parts[0] to minutes
        }
        .toMap()

private fun normalizeFrictionModes(modes: Collection<FrictionMode>): List<FrictionMode> =
    modes.distinct()

private fun encodeFrictionSequence(modes: List<FrictionMode>): String =
    modes.joinToString(MODE_SEPARATOR) { it.name }

private fun decodeFrictionSequence(raw: String?): List<FrictionMode> =
    raw.orEmpty()
        .split(MODE_SEPARATOR)
        .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
        .distinct()

/**
 * Reads the ordered key first. Older installs only have the unordered set (or a
 * single legacy mode), so those fall back to canonical order. The ordered list
 * is the only representation written by current versions.
 */
private fun Preferences.defaultFrictionModes(): List<FrictionMode> {
    decodeFrictionSequence(this[Keys.DEFAULT_FRICTION_SEQUENCE])
        .takeIf { it.isNotEmpty() }
        ?.let { return it }

    val legacySet = this[Keys.DEFAULT_FRICTION_MODES].orEmpty()
        .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
        .distinct()
        .sortedBy { it.ordinal }
    if (legacySet.isNotEmpty()) return legacySet

    val legacy = this[Keys.DEFAULT_FRICTION_MODE]
        ?.let { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
    return listOfNotNull(legacy).ifEmpty { listOf(FrictionMode.BREATHING) }
}

private fun MutablePreferences.writeDefaultFrictionModes(
    modes: List<FrictionMode>,
) {
    this[Keys.DEFAULT_FRICTION_SEQUENCE] = encodeFrictionSequence(modes)
    // Keep the unordered keys in sync for downgrade/import paths. The ordered
    // key is authoritative because a StringSet cannot preserve sequence order.
    this[Keys.DEFAULT_FRICTION_MODES] = modes.map { it.name }.toSet()
    this[Keys.DEFAULT_FRICTION_MODE] = modes.first().name
}

private const val SNOOZE_SEPARATOR = "|"
private const val SNOOZE_VALUE_SEPARATOR = "="

internal fun encodeSnoozes(snoozes: Map<String, Long>): String =
    snoozes.entries
        .filter { it.key.isNotBlank() && it.value > 0L }
        .sortedBy { it.key }
        .joinToString(SNOOZE_SEPARATOR) { (packageName, until) ->
            "$packageName$SNOOZE_VALUE_SEPARATOR$until"
        }

internal fun decodeSnoozes(raw: String): Map<String, Long> =
    raw.split(SNOOZE_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(SNOOZE_VALUE_SEPARATOR, limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            val until = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (until <= 0L) return@mapNotNull null
            parts[0] to until
        }
        .toMap()

private fun Preferences.snoozedUntilMillis(): Map<String, Long> =
    decodeSnoozes(this[Keys.SNOOZED_UNTIL].orEmpty())

private fun Preferences.scheduleDays(): Set<DayOfWeek> {
    val stored = this[Keys.SCHEDULE_DAYS].orEmpty()
        .mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
        .toSet()
    return stored.ifEmpty { DayOfWeek.entries.toSet() }
}

private fun Preferences.strictModeChallenge(): StrictModeChallenge =
    this[Keys.STRICT_MODE_CHALLENGE]
        ?.let { runCatching { StrictModeChallenge.valueOf(it) }.getOrNull() }
        ?: StrictModeChallenge.DELAY

private fun Preferences.toPauseSettings(): PauseSettings {
    val profiles = decodeProfiles(this[Keys.PROFILES_JSON].orEmpty())
    val activeProfileId = this[Keys.ACTIVE_PROFILE_ID]
        ?.takeIf { id -> id.isNotBlank() && profiles.any { it.id == id } }
    return PauseSettings(
        isEnabled = this[Keys.ENABLED] ?: true,
        cooldownMinutes = this[Keys.COOLDOWN_MINUTES]
            ?: PauseSettings.DEFAULT_COOLDOWN_MINUTES,
        breathingDurationSeconds = this[Keys.BREATHING_SECONDS]
            ?: PauseSettings.DEFAULT_BREATHING_SECONDS,
        defaultFrictionModes = defaultFrictionModes(),
        monitoredPackages = this[Keys.MONITORED_PACKAGES].orEmpty(),
        pausedPackages = this[Keys.PAUSED_PACKAGES].orEmpty(),
        appModeOverrides = decodeOverrides(this[Keys.APP_MODE_OVERRIDES].orEmpty()),
        scheduleEnabled = this[Keys.SCHEDULE_ENABLED] ?: false,
        scheduleDays = scheduleDays(),
        scheduleStartMinutes = this[Keys.SCHEDULE_START_MINUTES]
            ?: PauseSettings.DEFAULT_SCHEDULE_START_MINUTES,
        scheduleEndMinutes = this[Keys.SCHEDULE_END_MINUTES]
            ?: PauseSettings.DEFAULT_SCHEDULE_END_MINUTES,
        snoozedUntilMillis = snoozedUntilMillis(),
        historyRetentionDays = this[Keys.HISTORY_RETENTION_DAYS]
            ?: PauseSettings.DEFAULT_HISTORY_RETENTION_DAYS,
        dailyLimitsMinutes = decodeDailyLimits(this[Keys.DAILY_LIMITS_MINUTES].orEmpty()),
        profiles = profiles,
        activeProfileId = activeProfileId,
        autoSwitchProfiles = this[Keys.AUTO_SWITCH_PROFILES] ?: false,
        strictModeEnabled = this[Keys.STRICT_MODE_ENABLED] ?: false,
        strictModeChallenge = strictModeChallenge(),
        strictModeDelaySeconds = (this[Keys.STRICT_MODE_DELAY_SECONDS]
            ?: PauseSettings.DEFAULT_STRICT_MODE_DELAY_SECONDS)
            .coerceIn(
                PauseSettings.MIN_STRICT_MODE_DELAY_SECONDS,
                PauseSettings.MAX_STRICT_MODE_DELAY_SECONDS,
            ),
        strictModePhrase = this[Keys.STRICT_MODE_PHRASE]
            ?: PauseSettings.DEFAULT_STRICT_MODE_PHRASE,
    )
}

/**
 * DataStore keys shared by the repository and the read-only snapshot mapper.
 * Kept private to this file so the on-disk schema has exactly one owner.
 */
private object Keys {
    val ENABLED = booleanPreferencesKey("enabled")
    val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
    val BREATHING_SECONDS = intPreferencesKey("breathing_seconds")
    val DEFAULT_FRICTION_MODE = stringPreferencesKey("default_friction_mode")
    val DEFAULT_FRICTION_MODES = stringSetPreferencesKey("default_friction_modes")
    val DEFAULT_FRICTION_SEQUENCE = stringPreferencesKey("default_friction_sequence")
    val MONITORED_PACKAGES = stringSetPreferencesKey("monitored_packages")
    val PAUSED_PACKAGES = stringSetPreferencesKey("paused_packages")
    val APP_MODE_OVERRIDES = stringPreferencesKey("app_mode_overrides")
    val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
    val SCHEDULE_DAYS = stringSetPreferencesKey("schedule_days")
    val SCHEDULE_START_MINUTES = intPreferencesKey("schedule_start_minutes")
    val SCHEDULE_END_MINUTES = intPreferencesKey("schedule_end_minutes")
    val SNOOZED_UNTIL = stringPreferencesKey("snoozed_until")
    val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
    val DAILY_LIMITS_MINUTES = stringPreferencesKey("daily_limits_minutes")
    val PROFILES_JSON = stringPreferencesKey("profiles_json")
    val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
    val AUTO_SWITCH_PROFILES = booleanPreferencesKey("auto_switch_profiles")
    val STRICT_MODE_ENABLED = booleanPreferencesKey("strict_mode_enabled")
    val STRICT_MODE_CHALLENGE = stringPreferencesKey("strict_mode_challenge")
    val STRICT_MODE_DELAY_SECONDS = intPreferencesKey("strict_mode_delay_seconds")
    val STRICT_MODE_PHRASE = stringPreferencesKey("strict_mode_phrase")
}
