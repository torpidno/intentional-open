package com.pause.app.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.domain.model.InstalledApp
import com.pause.app.domain.model.StrictModeChallenge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppPickerUiState(
    val query: String = "",
    val apps: List<InstalledApp> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val strictModeEnabled: Boolean = false,
    val strictModeChallenge: StrictModeChallenge = StrictModeChallenge.DELAY,
    val strictModeDelaySeconds: Int = PauseSettings.DEFAULT_STRICT_MODE_DELAY_SECONDS,
    val strictModePhrase: String = PauseSettings.DEFAULT_STRICT_MODE_PHRASE,
)

@HiltViewModel
class AppPickerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val isRefreshing = MutableStateFlow(true)

    val uiState: StateFlow<AppPickerUiState> = combine(
        installedAppsRepository.apps,
        settingsRepository.settings,
        query,
        isRefreshing,
    ) { apps, settings, currentQuery, refreshing ->
        val filtered = if (currentQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(currentQuery, ignoreCase = true) ||
                    app.packageName.contains(currentQuery, ignoreCase = true)
            }
        }
        AppPickerUiState(
            query = currentQuery,
            apps = filtered,
            selectedPackages = settings.monitoredPackages,
            isLoading = refreshing && apps.isEmpty(),
            strictModeEnabled = settings.strictModeEnabled,
            strictModeChallenge = settings.strictModeChallenge,
            strictModeDelaySeconds = settings.strictModeDelaySeconds,
            strictModePhrase = settings.effectiveStrictModePhrase,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPickerUiState(),
    )

    init {
        viewModelScope.launch {
            try {
                installedAppsRepository.refresh()
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * Toggling is delegated to a single atomic DataStore edit, so tapping two
     * rows in quick succession cannot lose either selection.
     */
    fun toggle(packageName: String) {
        viewModelScope.launch { settingsRepository.toggleMonitored(packageName) }
    }
}
