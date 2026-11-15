package com.pause.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.pause.app.di.ApplicationScope
import com.pause.app.domain.model.FrictionMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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

    /** Compatibility helper for a single-mode default; used by tests/imports. */
    suspend fun setDefaultFrictionMode(mode: FrictionMode) {
        setDefaultFrictionModes(listOf(mode))
    }

    suspend fun setDefaultFrictionModes(modes: Collection<FrictionMode>) {
        val normalized = normalizeFrictionModes(modes)
        if (normalized.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[Keys.DEFAULT_FRICTION_MODES] = normalized.map { it.name }.toSet()
            // Keep the legacy key in sync for downgrade/import paths.
            preferences[Keys.DEFAULT_FRICTION_MODE] = normalized.first().name
        }
    }

    /** Toggles one mode while guaranteeing at least one remains enabled. */
    suspend fun toggleDefaultFrictionMode(mode: FrictionMode) {
        dataStore.edit { preferences ->
            val current = preferences.defaultFrictionModes()
            val next = if (mode in current) current - mode else current + mode
            if (next.isEmpty()) return@edit
            val normalized = normalizeFrictionModes(next)
            preferences[Keys.DEFAULT_FRICTION_MODES] = normalized.map { it.name }.toSet()
            preferences[Keys.DEFAULT_FRICTION_MODE] = normalized.first().name
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
            }
            preferences[Keys.MONITORED_PACKAGES] = monitoredPackages
            preferences[Keys.PAUSED_PACKAGES] = pausedPackages
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
            if (nowMonitored) monitoredPackages += packageName else monitoredPackages -= packageName
            pausedPackages -= packageName
            preferences[Keys.MONITORED_PACKAGES] = monitoredPackages
            preferences[Keys.PAUSED_PACKAGES] = pausedPackages
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
        }
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
            "$packageName$VALUE_SEPARATOR${modes.joinToString(MODE_SEPARATOR) { it.name }}"
        }

internal fun decodeOverrides(raw: String): Map<String, List<FrictionMode>> =
    raw.split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(VALUE_SEPARATOR, limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
            val modes = parts[1].split(MODE_SEPARATOR)
                .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
                .distinct()
                .sortedBy { it.ordinal }
            if (modes.isEmpty()) return@mapNotNull null
            parts[0] to modes
        }
        .toMap()

private fun normalizeFrictionModes(modes: Collection<FrictionMode>): List<FrictionMode> =
    modes.distinct().sortedBy { it.ordinal }

/** Reads the new multi-mode key, falling back to the single-mode legacy key. */
private fun Preferences.defaultFrictionModes(): List<FrictionMode> {
    val stored = this[Keys.DEFAULT_FRICTION_MODES].orEmpty()
        .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
        .distinct()
        .sortedBy { it.ordinal }
    if (stored.isNotEmpty()) return stored

    val legacy = this[Keys.DEFAULT_FRICTION_MODE]
        ?.let { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
    return listOfNotNull(legacy).ifEmpty { listOf(FrictionMode.BREATHING) }
}

private fun Preferences.toPauseSettings(): PauseSettings =
    PauseSettings(
        isEnabled = this[Keys.ENABLED] ?: true,
        cooldownMinutes = this[Keys.COOLDOWN_MINUTES]
            ?: PauseSettings.DEFAULT_COOLDOWN_MINUTES,
        breathingDurationSeconds = this[Keys.BREATHING_SECONDS]
            ?: PauseSettings.DEFAULT_BREATHING_SECONDS,
        defaultFrictionModes = defaultFrictionModes(),
        monitoredPackages = this[Keys.MONITORED_PACKAGES].orEmpty(),
        pausedPackages = this[Keys.PAUSED_PACKAGES].orEmpty(),
        appModeOverrides = decodeOverrides(this[Keys.APP_MODE_OVERRIDES].orEmpty()),
    )

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
    val MONITORED_PACKAGES = stringSetPreferencesKey("monitored_packages")
    val PAUSED_PACKAGES = stringSetPreferencesKey("paused_packages")
    val APP_MODE_OVERRIDES = stringPreferencesKey("app_mode_overrides")
}
