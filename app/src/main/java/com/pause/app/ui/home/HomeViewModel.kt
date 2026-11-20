package com.pause.app.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.DailyBudgetRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.RangeStats
import com.pause.app.domain.model.StatsRange
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.util.PermissionChecker
import com.pause.app.util.PermissionSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A monitored app rendered on the dashboard. */
data class MonitoredAppUi(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isPaused: Boolean,
    val modeOverride: List<FrictionMode>?,
    val snoozedUntilMillis: Long? = null,
    val dailyLimitMinutes: Int? = null,
    val usedTodayMinutes: Int = 0,
)

data class HomeUiState(
    val isEnabled: Boolean = true,
    val statsRange: StatsRange = StatsRange.TODAY,
    val rangeStats: RangeStats = RangeStats(),
    val attemptsToday: Int = 0,
    val walkedAwayToday: Int = 0,
    val mostTriggeredLabel: String? = null,
    val mostTriggeredCount: Int = 0,
    val monitoredApps: List<MonitoredAppUi> = emptyList(),
    val defaultModes: List<FrictionMode> = listOf(FrictionMode.BREATHING),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val profiles: List<PauseProfile> = emptyList(),
    val activeProfileId: String? = null,
    val effectiveProfileName: String? = null,
    val autoSwitchProfiles: Boolean = false,
    val isScheduledProfileActive: Boolean = false,
    val strictModeEnabled: Boolean = false,
    val strictModeChallenge: StrictModeChallenge = StrictModeChallenge.DELAY,
    val strictModeDelaySeconds: Int = PauseSettings.DEFAULT_STRICT_MODE_DELAY_SECONDS,
    val strictModePhrase: String = PauseSettings.DEFAULT_STRICT_MODE_PHRASE,
)

/**
 * Unidirectional dashboard state: DataStore + Room + PackageManager snapshots
 * are combined into one immutable [HomeUiState]; every user action is a method
 * that writes to the repositories and lets the flows re-emit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    interceptionRepository: InterceptionRepository,
    private val dailyBudgetRepository: DailyBudgetRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val permissionChecker: PermissionChecker,
    private val clock: Clock,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionChecker.snapshot())
    private val currentDay = MutableStateFlow(LocalDate.now(clock))
    private val selectedRange = MutableStateFlow(StatsRange.TODAY)
    private val refreshedAtMillis = MutableStateFlow(clock.millis())

    private val rangeStatsFlow = combine(currentDay, selectedRange) { day, range ->
        day to range
    }.flatMapLatest { (_, range) ->
        interceptionRepository.observeStats(range)
    }

    private val todayUsageFlow = currentDay.flatMapLatest { day ->
        dailyBudgetRepository.observeTodayUsage(day)
    }

    private val environmentFlow = combine(
        permissions,
        refreshedAtMillis,
    ) { permissionSnapshot, _ ->
        permissionSnapshot
    }

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        rangeStatsFlow,
        todayUsageFlow,
        installedAppsRepository.apps,
        environmentFlow,
    ) { settings, rangeStats, todayUsage, installedApps, permissionSnapshot ->
        val nowMillis = clock.millis()
        val nowZoned = ZonedDateTime.now(clock)
        val scheduledProfile = settings.scheduledProfileAt(nowZoned)
        val effectiveSettings = settings.effectiveAt(nowZoned)
        val installedByPackage = installedApps.associateBy { it.packageName }
        val catalogLoaded = installedApps.isNotEmpty()

        val monitored = settings.monitoredPackages
            .mapNotNull { packageName ->
                val installed = installedByPackage[packageName]
                // Hide apps that have been uninstalled, but only once the
                // catalog has loaded so the list does not flicker on startup.
                if (installed == null && catalogLoaded) return@mapNotNull null

                val usedMillis = (todayUsage[packageName] ?: 0L).coerceAtLeast(0L)
                MonitoredAppUi(
                    packageName = packageName,
                    label = installed?.label ?: installedAppsRepository.labelFor(packageName),
                    icon = installed?.icon ?: installedAppsRepository.iconFor(packageName),
                    isPaused = packageName in settings.pausedPackages,
                    modeOverride = settings.appModeOverrides[packageName],
                    snoozedUntilMillis = settings.snoozedUntilMillis[packageName]
                        ?.takeIf { it > nowMillis },
                    dailyLimitMinutes = settings.dailyLimitMinutesFor(packageName),
                    usedTodayMinutes = (usedMillis / MILLIS_PER_MINUTE).toInt(),
                )
            }
            .sortedBy { it.label.lowercase() }

        HomeUiState(
            isEnabled = settings.isEnabled,
            statsRange = rangeStats.range,
            rangeStats = rangeStats,
            attemptsToday = rangeStats.attempts,
            walkedAwayToday = rangeStats.walkedAway,
            mostTriggeredLabel = rangeStats.mostTriggeredLabel,
            mostTriggeredCount = rangeStats.mostTriggeredCount,
            monitoredApps = monitored,
            defaultModes = settings.defaultFrictionModes,
            permissions = permissionSnapshot,
            profiles = settings.profiles,
            activeProfileId = settings.activeProfileId,
            effectiveProfileName = effectiveSettings.activeProfile?.name,
            autoSwitchProfiles = settings.autoSwitchProfiles,
            isScheduledProfileActive = scheduledProfile != null,
            strictModeEnabled = settings.strictModeEnabled,
            strictModeChallenge = settings.strictModeChallenge,
            strictModeDelaySeconds = settings.strictModeDelaySeconds,
            strictModePhrase = settings.effectiveStrictModePhrase,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch { runCatching { installedAppsRepository.refresh() } }
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.dailyLimitsMinutes.keys }
                .distinctUntilChanged()
                .collect { pkgs ->
                    if (pkgs.isNotEmpty()) {
                        runCatching { dailyBudgetRepository.syncUsageSnapshot(pkgs) }
                    }
                }
        }
    }

    /** Re-reads permissions, refreshes the active day, and syncs daily usage; call on resume. */
    fun onResume() {
        refreshPermissions()
        refreshDay()
        refreshedAtMillis.value = clock.millis()
        syncDailyUsage()
    }

    /** Re-reads permission state; call whenever the screen resumes. */
    fun refreshPermissions() {
        permissions.value = permissionChecker.snapshot()
    }

    /** Updates the observed local day so Today's stats and daily usage roll over after midnight. */
    fun refreshDay() {
        currentDay.value = LocalDate.now(clock)
    }

    fun setStatsRange(range: StatsRange) {
        selectedRange.value = range
    }

    fun selectProfile(profileId: String?) {
        viewModelScope.launch { settingsRepository.selectProfile(profileId) }
    }

    fun setDailyLimitMinutes(packageName: String, minutes: Int?) {
        viewModelScope.launch {
            settingsRepository.setDailyLimitMinutes(packageName, minutes)
            if (minutes != null && minutes > 0) {
                runCatching { dailyBudgetRepository.syncUsageSnapshot(setOf(packageName)) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEnabled(enabled) }
    }

    fun setAppPaused(packageName: String, paused: Boolean) {
        viewModelScope.launch { settingsRepository.setAppPaused(packageName, paused) }
    }

    fun setAppModeOverride(packageName: String, modes: List<FrictionMode>?) {
        viewModelScope.launch { settingsRepository.setAppModeOverrides(packageName, modes) }
    }

    fun snoozeApp(packageName: String, minutes: Int) {
        viewModelScope.launch { settingsRepository.snoozeApp(packageName, minutes) }
    }

    fun clearAppSnooze(packageName: String) {
        viewModelScope.launch { settingsRepository.clearAppSnooze(packageName) }
    }

    private fun syncDailyUsage() {
        viewModelScope.launch {
            val pkgs = settingsRepository.settings.value.dailyLimitsMinutes.keys
            if (pkgs.isNotEmpty()) {
                runCatching { dailyBudgetRepository.syncUsageSnapshot(pkgs) }
            }
        }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
