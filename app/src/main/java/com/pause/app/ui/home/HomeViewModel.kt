package com.pause.app.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.util.PermissionChecker
import com.pause.app.util.PermissionSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
)

data class HomeUiState(
    val isEnabled: Boolean = true,
    val attemptsToday: Int = 0,
    val walkedAwayToday: Int = 0,
    val mostTriggeredLabel: String? = null,
    val mostTriggeredCount: Int = 0,
    val monitoredApps: List<MonitoredAppUi> = emptyList(),
    val defaultModes: List<FrictionMode> = listOf(FrictionMode.BREATHING),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
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
    private val installedAppsRepository: InstalledAppsRepository,
    private val permissionChecker: PermissionChecker,
    private val clock: Clock,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionChecker.snapshot())
    private val currentDay = MutableStateFlow(LocalDate.now(clock))

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        currentDay.flatMapLatest { interceptionRepository.observeTodayStats() },
        installedAppsRepository.apps,
        permissions,
    ) { settings, stats, installedApps, permissionSnapshot ->
        val nowMillis = clock.millis()
        val installedByPackage = installedApps.associateBy { it.packageName }
        val catalogLoaded = installedApps.isNotEmpty()

        val monitored = settings.monitoredPackages
            .mapNotNull { packageName ->
                val installed = installedByPackage[packageName]
                // Hide apps that have been uninstalled, but only once the
                // catalog has loaded so the list does not flicker on startup.
                if (installed == null && catalogLoaded) return@mapNotNull null

                MonitoredAppUi(
                    packageName = packageName,
                    label = installed?.label ?: installedAppsRepository.labelFor(packageName),
                    icon = installed?.icon ?: installedAppsRepository.iconFor(packageName),
                    isPaused = packageName in settings.pausedPackages,
                    modeOverride = settings.appModeOverrides[packageName],
                    snoozedUntilMillis = settings.snoozedUntilMillis[packageName]
                        ?.takeIf { it > nowMillis },
                )
            }
            .sortedBy { it.label.lowercase() }

        HomeUiState(
            isEnabled = settings.isEnabled,
            attemptsToday = stats.attempts,
            walkedAwayToday = stats.walkedAway,
            mostTriggeredLabel = stats.mostTriggeredLabel,
            mostTriggeredCount = stats.mostTriggeredCount,
            monitoredApps = monitored,
            defaultModes = settings.defaultFrictionModes,
            permissions = permissionSnapshot,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch { runCatching { installedAppsRepository.refresh() } }
    }

    /** Re-reads permissions and refreshes the active day; call whenever the screen resumes. */
    fun onResume() {
        refreshPermissions()
        refreshDay()
    }

    /** Re-reads permission state; call whenever the screen resumes. */
    fun refreshPermissions() {
        permissions.value = permissionChecker.snapshot()
    }

    /** Updates the observed local day so Today's stats roll over after midnight. */
    fun refreshDay() {
        currentDay.value = LocalDate.now(clock)
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
}
