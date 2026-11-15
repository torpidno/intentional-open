package com.pause.app.ui.intervention

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.data.preferences.SettingsRepository
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.service.CooldownTracker
import com.pause.app.service.SessionStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot navigation effects from the pause screen to the Activity. */
sealed interface InterventionEffect {
    data object GoHome : InterventionEffect
    data class OpenTargetApp(val intent: Intent?) : InterventionEffect
}

/**
 * Immutable UI state for the pause surface.
 *
 * The countdown is derived from an end timestamp rather than a mutable counter,
 * so rotation and process death cannot reset or skip the pause. [stepIndex] and
 * [modeCount] describe where the current mode sits in a multi-mode sequence.
 */
data class InterventionUiState(
    val packageName: String = "",
    val appLabel: String = "",
    val appIcon: ImageBitmap? = null,
    val mode: FrictionMode = FrictionMode.BREATHING,
    val modeCount: Int = 1,
    val stepIndex: Int = 0,
    val breathingSeconds: Int = PauseSettings.DEFAULT_BREATHING_SECONDS,
    val remainingMillis: Long = 0L,
    val breathProgress: Float = 0f,
    val isUnlocked: Boolean = false,
    val selectedReason: String? = null,
    val note: String = "",
    val selectedLimitMinutes: Int? = null,
    val isCompleting: Boolean = false,
    /** Shown after a session deadline could not be persisted. */
    val sessionStartError: Boolean = false,
) {
    val hasNextStep: Boolean
        get() = stepIndex < modeCount - 1

    val remainingSeconds: Int
        get() = ((remainingMillis + 999L) / 1000L).toInt()
}

@HiltViewModel
class InterventionViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val interceptionRepository: InterceptionRepository,
    private val sessionStarter: SessionStarter,
    private val cooldownTracker: CooldownTracker,
    private val clock: Clock,
) : ViewModel() {

    private val packageName: String =
        savedStateHandle.get<String>(PauseInterventionActivity.EXTRA_PACKAGE_NAME).orEmpty()

    /**
     * Ordered modes for this pause. The activity passes the list; the single
     * legacy extra keeps old callers and instrumentation tests working.
     */
    private val modes: List<FrictionMode> = readModes(savedStateHandle)

    /** Used for the attempt log, which still has one representative mode. */
    private val primaryMode: FrictionMode = modes.first()

    /**
     * Restore the exact step, not just the first mode. Matching by mode name
     * also keeps a saved pause understandable if a future release reorders the
     * sequence while the process is dead.
     */
    private val restoredStepIndex: Int = savedStateHandle.get<String>(KEY_MODE)
        ?.let { name -> modes.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
        ?: (savedStateHandle.get<Int>(KEY_STEP_INDEX) ?: 0)
            .coerceIn(0, modes.lastIndex)
    private val restoredMode: FrictionMode = modes[restoredStepIndex]
    private val restoredReason: String? = savedStateHandle.get<String>(KEY_SELECTED_REASON)
    private val restoredNote: String = savedStateHandle.get<String>(KEY_NOTE).orEmpty()
    private val restoredLimit: Int? = savedStateHandle.get<Int>(KEY_SELECTED_LIMIT)
        ?.takeIf { it in PauseSettings.SESSION_LIMIT_OPTIONS }

    private val appLabel: String = if (packageName.isBlank()) {
        ""
    } else {
        installedAppsRepository.labelFor(packageName)
    }

    private val settings: PauseSettings = settingsRepository.settings.value
    private val breathingSeconds: Int = settings.breathingDurationSeconds

    private val pauseEndsAtMillis: Long = savedStateHandle.get<Long>(KEY_PAUSE_ENDS_AT)
        ?: (clock.millis() + breathingSeconds * 1_000L).also {
            savedStateHandle[KEY_PAUSE_ENDS_AT] = it
        }

    /**
     * Inserting the PENDING log row happens once per ViewModel. Rotation reuses
     * the ViewModel; process death restores the id from SavedStateHandle.
     */
    private val attemptId: Deferred<Long> =
        if (packageName.isBlank()) {
            CompletableDeferred(0L)
        } else {
            viewModelScope.async {
                savedStateHandle.get<Long>(KEY_ATTEMPT_ID) ?: interceptionRepository
                    .recordAttempt(packageName, appLabel, modes)
                    .also { savedStateHandle[KEY_ATTEMPT_ID] = it }
            }
        }

    private val initialRemainingMillis: Long =
        if (restoredMode == FrictionMode.BREATHING) {
            (pauseEndsAtMillis - clock.millis()).coerceAtLeast(0L)
        } else {
            0L
        }

    private val initialUnlocked: Boolean = when (restoredMode) {
        FrictionMode.BREATHING -> initialRemainingMillis == 0L
        FrictionMode.INTENTION -> restoredReason != null || restoredNote.isNotBlank()
        FrictionMode.TIME_LIMIT -> restoredLimit != null
    }

    private val _uiState = MutableStateFlow(
        InterventionUiState(
            packageName = packageName,
            appLabel = appLabel,
            mode = restoredMode,
            modeCount = modes.size,
            stepIndex = restoredStepIndex,
            breathingSeconds = breathingSeconds,
            remainingMillis = initialRemainingMillis,
            breathProgress = if (restoredMode == FrictionMode.BREATHING) {
                (1f - initialRemainingMillis.toFloat() / (breathingSeconds * 1_000f))
                    .coerceIn(0f, 1f)
            } else {
                0f
            },
            isUnlocked = initialUnlocked,
            selectedReason = restoredReason,
            note = restoredNote,
            selectedLimitMinutes = restoredLimit,
        ),
    )
    val uiState: StateFlow<InterventionUiState> = _uiState.asStateFlow()

    private val _effects = Channel<InterventionEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var breathingJob: Job? = null

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(appIcon = installedAppsRepository.loadIconFor(packageName))
            }
        }
        if (restoredMode == FrictionMode.BREATHING) {
            startBreathingTimer()
        }
    }

    private fun startBreathingTimer() {
        val totalMillis = (breathingSeconds * 1_000L).coerceAtLeast(1L)
        breathingJob = viewModelScope.launch {
            while (isActive) {
                val remaining = (pauseEndsAtMillis - clock.millis()).coerceAtLeast(0L)
                val progress = ((totalMillis - remaining).toFloat() / totalMillis)
                    .coerceIn(0f, 1f)
                _uiState.update {
                    it.copy(
                        remainingMillis = remaining,
                        breathProgress = progress,
                        isUnlocked = remaining == 0L,
                    )
                }
                if (remaining == 0L) break
                delay(TICK_MILLIS)
            }
        }
    }

    /** Tapping a selected intention again clears it, re-locking the CTA. */
    fun selectReason(reason: String) {
        if (_uiState.value.isCompleting) return
        _uiState.update { state ->
            val next = if (state.selectedReason == reason) null else reason
            state.copy(selectedReason = next).withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun updateNote(note: String) {
        if (_uiState.value.isCompleting) return
        _uiState.update { state ->
            state.copy(note = note.take(PauseSettings.MAX_INTENTION_LENGTH))
                .withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun selectSessionLimit(minutes: Int) {
        if (_uiState.value.isCompleting) return
        if (minutes !in PauseSettings.SESSION_LIMIT_OPTIONS) return
        _uiState.update {
            it.copy(selectedLimitMinutes = minutes).withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun continueToTargetApp() {
        val state = _uiState.value
        if (!state.isUnlocked || state.isCompleting || packageName.isBlank()) return

        // An intermediate step advances the sequence instead of launching the
        // target app. No cooldown/log outcome is written until the final step.
        if (state.hasNextStep) {
            advanceToNextStep(state)
            return
        }

        _uiState.update { it.copy(isCompleting = true, sessionStartError = false) }

        viewModelScope.launch {
            val intention = state.intentionText()
            val limitMinutes = state.selectedLimitMinutes

            if (limitMinutes != null) {
                // The target app stays closed until the deadline is safely on
                // disk. A failure surfaces a retry path instead of silently
                // opening the app without its session.
                val sessionResult = try {
                    sessionStarter.startSession(packageName, state.appLabel, limitMinutes)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                if (sessionResult.isFailure) {
                    _uiState.update {
                        it.copy(isCompleting = false, sessionStartError = true)
                    }
                    return@launch
                }
            }

            runCatching {
                val cooldownMillis = limitMinutes?.let { it * 60_000L }
                    ?: (settings.cooldownMinutes * 60_000L)
                cooldownTracker.setCooldown(packageName, cooldownMillis)
                interceptionRepository.completeAttempt(
                    id = attemptId.await(),
                    outcome = InterventionOutcome.PROCEEDED,
                    intention = intention,
                    sessionLimitMinutes = limitMinutes,
                )
            }
            val launchIntent = installedAppsRepository.launchIntentFor(packageName)
            _effects.send(
                if (launchIntent != null) {
                    InterventionEffect.OpenTargetApp(launchIntent)
                } else {
                    InterventionEffect.GoHome
                },
            )
        }
    }

    /** Retries only the persistence step; the pause selections are preserved. */
    fun retrySessionStart() {
        if (!_uiState.value.sessionStartError) return
        _uiState.update { it.copy(sessionStartError = false) }
        continueToTargetApp()
    }

    /**
     * Moves to the next enabled mode. Modes are sorted by enum order, so
     * breathing (the only timed step) is always first; later steps unlock from
     * the user's existing intention/limit selection.
     */
    private fun advanceToNextStep(state: InterventionUiState) {
        val nextIndex = state.stepIndex + 1
        val nextMode = modes.getOrNull(nextIndex) ?: return
        val nextUnlocked = when (nextMode) {
            FrictionMode.BREATHING -> false
            FrictionMode.INTENTION -> state.selectedReason != null || state.note.isNotBlank()
            FrictionMode.TIME_LIMIT -> state.selectedLimitMinutes != null
        }
        _uiState.update {
            it.copy(
                mode = nextMode,
                stepIndex = nextIndex,
                remainingMillis = 0L,
                breathProgress = 0f,
                isUnlocked = nextUnlocked,
            )
        }
        persistPauseProgress()
    }

    /** Keeps the visible step and selections across process death. */
    private fun persistPauseProgress() {
        val state = _uiState.value
        savedStateHandle[KEY_STEP_INDEX] = state.stepIndex
        savedStateHandle[KEY_MODE] = state.mode.name
        savedStateHandle[KEY_SELECTED_REASON] = state.selectedReason
        savedStateHandle[KEY_NOTE] = state.note
        savedStateHandle[KEY_SELECTED_LIMIT] = state.selectedLimitMinutes
    }

    fun walkAway() {
        val state = _uiState.value
        if (state.isCompleting) return
        _uiState.update { it.copy(isCompleting = true) }

        viewModelScope.launch {
            runCatching {
                sessionStarter.cancelSession(packageName)
                // Leaving the pause should not grant a free pass for the few
                // seconds of the launch guard; the next launch is intercepted.
                cooldownTracker.clear(packageName)
                interceptionRepository.completeAttempt(
                    id = attemptId.await(),
                    outcome = InterventionOutcome.WALKED_AWAY,
                    intention = state.intentionText(),
                    sessionLimitMinutes = null,
                )
            }
            _effects.send(InterventionEffect.GoHome)
        }
    }

    private fun InterventionUiState.withIntentionUnlock(): InterventionUiState {
        val unlocked = when (mode) {
            FrictionMode.BREATHING -> remainingMillis == 0L
            FrictionMode.INTENTION -> selectedReason != null || note.isNotBlank()
            FrictionMode.TIME_LIMIT -> selectedLimitMinutes != null
        }
        return copy(isUnlocked = unlocked)
    }

    private fun InterventionUiState.intentionText(): String? {
        // Preserve an intention entered in an earlier step of this pause.
        if (FrictionMode.INTENTION !in modes) return null
        val parts = listOfNotNull(
            selectedReason?.takeIf { it.isNotBlank() },
            note.trim().takeIf { it.isNotBlank() },
        )
        return parts.joinToString(separator = " — ").takeIf { it.isNotBlank() }
    }

    override fun onCleared() {
        breathingJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val KEY_ATTEMPT_ID = "attempt_id"
        const val KEY_PAUSE_ENDS_AT = "pause_ends_at"
        const val KEY_STEP_INDEX = "step_index"
        const val KEY_MODE = "mode"
        const val KEY_SELECTED_REASON = "selected_reason"
        const val KEY_NOTE = "note"
        const val KEY_SELECTED_LIMIT = "selected_limit"
        const val TICK_MILLIS = 50L
    }
}

private fun readModes(savedStateHandle: SavedStateHandle): List<FrictionMode> {
    val encoded = savedStateHandle
        .get<ArrayList<String>>(PauseInterventionActivity.EXTRA_FRICTION_MODES)
        .orEmpty()
        .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
    val legacy = savedStateHandle
        .get<String>(PauseInterventionActivity.EXTRA_FRICTION_MODE)
        ?.let { runCatching { FrictionMode.valueOf(it) }.getOrNull() }

    return encoded.ifEmpty { listOfNotNull(legacy) }
        .distinct()
        .sortedBy { it.ordinal }
        .ifEmpty { listOf(FrictionMode.BREATHING) }
}
