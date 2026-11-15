package com.pause.app.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.HistoryRetentionManager
import com.pause.app.domain.model.FrictionMode
import com.pause.app.util.PermissionChecker
import com.pause.app.util.PermissionSnapshot
import com.pause.app.util.PermissionType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: PauseSettings = PauseSettings(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val historyRetentionManager: HistoryRetentionManager,
    private val permissionChecker: PermissionChecker,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionChecker.snapshot())

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        permissions,
    ) { settings, snapshot ->
        SettingsUiState(settings = settings, permissions = snapshot)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * System-settings navigation is a UI side effect. The ViewModel emits the
     * exact Intent and the composable launches it, which keeps Activity context
     * out of the ViewModel.
     */
    private val _openSettings = Channel<Intent>(Channel.BUFFERED)
    val openSettings = _openSettings.receiveAsFlow()

    fun refreshPermissions() {
        permissions.value = permissionChecker.snapshot()
    }

    fun openPermissionSettings(type: PermissionType) {
        _openSettings.trySend(permissionChecker.settingsIntent(type))
    }

    fun openBatteryOptimizationList() {
        _openSettings.trySend(permissionChecker.batteryOptimizationListIntent())
    }

    fun setCooldownMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setCooldownMinutes(minutes) }
    }

    fun setBreathingDurationSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setBreathingDurationSeconds(seconds) }
    }

    fun toggleDefaultFrictionMode(mode: FrictionMode) {
        viewModelScope.launch { settingsRepository.toggleDefaultFrictionMode(mode) }
    }

    fun moveDefaultFrictionMode(mode: FrictionMode, offset: Int) {
        viewModelScope.launch { settingsRepository.moveDefaultFrictionMode(mode, offset) }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setScheduleEnabled(enabled) }
    }

    fun setScheduleWindow(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch { settingsRepository.setScheduleWindow(startMinutes, endMinutes) }
    }

    fun toggleScheduleDay(day: DayOfWeek) {
        viewModelScope.launch { settingsRepository.toggleScheduleDay(day) }
    }

    fun setHistoryRetentionDays(days: Int) {
        if (days < 0 || days > 3650) return
        viewModelScope.launch {
            settingsRepository.setHistoryRetentionDays(days)
            historyRetentionManager.pruneNow(days)
        }
    }
}
