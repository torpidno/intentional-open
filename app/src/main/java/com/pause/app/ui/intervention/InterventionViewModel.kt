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
    val isDailyLimitBlocked: Boolean = false,
    val dailyUsedMinutes: Int = 0,
    val dailyLimitMinutes: Int = 0,
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

    private var packageName: String =
        savedStateHandle.get<String>(PauseInterventionActivity.EXTRA_PACKAGE_NAME).orEmpty()

    private var isDailyLimitBlocked: Boolean =
        savedStateHandle.get<Boolean>(PauseInterventionActivity.EXTRA_DAILY_LIMIT_BLOCKED) == true

    private var dailyUsedMinutes: Int =
        (savedStateHandle.get<Int>(PauseInterventionActivity.EXTRA_DAILY_USED_MINUTES) ?: 0)
            .coerceAtLeast(0)

    private var dailyLimitMinutes: Int =
        (savedStateHandle.get<Int>(PauseInterventionActivity.EXTRA_DAILY_LIMIT_MINUTES) ?: 0)
            .coerceAtLeast(0)

    /**
     * Ordered modes for this pause. The activity passes the list; the single
     * legacy extra keeps old callers and instrumentation tests working.
     */
    private var modes: List<FrictionMode> = readModes(savedStateHandle)

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

    private val initialAppLabel: String = if (packageName.isBlank()) {
        ""
    } else {
        installedAppsRepository.labelFor(packageName)
    }

    private var settings: PauseSettings = settingsRepository.settings.value
    private var breathingSeconds: Int = settings.breathingDurationSeconds

    /**
     * End timestamp for the active BREATHING step. Initialized only when
     * entering a BREATHING step (or restored from SavedStateHandle after
     * process death) so non-first breathing steps get their full countdown.
     */
    private var pauseEndsAtMillis: Long? =
        if (!isDailyLimitBlocked && restoredMode == FrictionMode.BREATHING) {
            savedStateHandle.get<Long>(KEY_PAUSE_ENDS_AT)
                ?: (clock.millis() + breathingSeconds * 1_000L).also {
                    savedStateHandle[KEY_PAUSE_ENDS_AT] = it
                }
        } else {
            null
        }

    /** Monotonic counter so async work from a superseded intervention is ignored. */
    private var interventionGeneration: Long = 0L

    /**
     * Inserting the PENDING log row happens once per intervention. Rotation reuses
     * the ViewModel; process death restores the id from SavedStateHandle.
     */
    private var attemptId: Deferred<Long> =
        if (packageName.isBlank()) {
            CompletableDeferred(0L)
        } else {
            savedStateHandle.get<Long>(KEY_ATTEMPT_ID)?.let(::CompletableDeferred)
                ?: run {
                    val targetPackage = packageName
                    val targetLabel = initialAppLabel
                    val targetModes = modes
                    val generation = interventionGeneration
                    viewModelScope.async {
                        interceptionRepository
                            .recordAttempt(targetPackage, targetLabel, targetModes)
                            .also { id ->
                                if (interventionGeneration == generation) {
                                    savedStateHandle[KEY_ATTEMPT_ID] = id
                                }
                            }
                    }
                }
        }

    private val initialRemainingMillis: Long =
        if (!isDailyLimitBlocked && restoredMode == FrictionMode.BREATHING) {
            ((pauseEndsAtMillis ?: clock.millis()) - clock.millis()).coerceAtLeast(0L)
        } else {
            0L
        }

    private val initialUnlocked: Boolean = if (isDailyLimitBlocked) {
        false
    } else {
        when (restoredMode) {
            FrictionMode.BREATHING -> initialRemainingMillis == 0L
            FrictionMode.INTENTION -> restoredReason != null || restoredNote.isNotBlank()
            FrictionMode.TIME_LIMIT -> restoredLimit != null
        }
    }

    private val _uiState = MutableStateFlow(
        InterventionUiState(
            packageName = packageName,
            appLabel = initialAppLabel,
            mode = restoredMode,
            modeCount = modes.size,
            stepIndex = restoredStepIndex,
            breathingSeconds = breathingSeconds,
            remainingMillis = initialRemainingMillis,
            breathProgress = if (!isDailyLimitBlocked && restoredMode == FrictionMode.BREATHING) {
                val totalMillis = (breathingSeconds * 1_000L).coerceAtLeast(1L)
                ((totalMillis - initialRemainingMillis).toFloat() / totalMillis)
                    .coerceIn(0f, 1f)
            } else {
                0f
            },
            isUnlocked = initialUnlocked,
            selectedReason = restoredReason,
            note = restoredNote,
            selectedLimitMinutes = restoredLimit,
            isDailyLimitBlocked = isDailyLimitBlocked,
            dailyUsedMinutes = dailyUsedMinutes,
            dailyLimitMinutes = dailyLimitMinutes,
        ),
    )
    val uiState: StateFlow<InterventionUiState> = _uiState.asStateFlow()

    private val _effects = Channel<InterventionEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var breathingJob: Job? = null
    private var iconJob: Job? = null

    init {
        if (packageName.isNotBlank()) {
            loadAppIcon(packageName)
        }
        if (!isDailyLimitBlocked && restoredMode == FrictionMode.BREATHING) {
            startBreathingTimer()
        }
    }

    private fun loadAppIcon(targetPackage: String) {
        iconJob?.cancel()
        iconJob = viewModelScope.launch {
            val icon = installedAppsRepository.loadIconFor(targetPackage)
            _uiState.update { state ->
                if (state.packageName == targetPackage) state.copy(appIcon = icon) else state
            }
        }
    }

    private fun startBreathingTimer() {
        breathingJob?.cancel()
        val endsAt = pauseEndsAtMillis ?: return
        val totalMillis = (breathingSeconds * 1_000L).coerceAtLeast(1L)
        val generation = interventionGeneration
        breathingJob = viewModelScope.launch {
            while (isActive) {
                val remaining = (endsAt - clock.millis()).coerceAtLeast(0L)
                val progress = ((totalMillis - remaining).toFloat() / totalMillis)
                    .coerceIn(0f, 1f)
                if (interventionGeneration != generation) break
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

    /**
     * Switches the singleTop pause surface to a newly intercepted app without
     * recreating the Activity. Closes any unfinished prior attempt as walked away.
     */
    fun handleNewIntervention(
        newPackageName: String,
        newModes: List<FrictionMode>,
        dailyLimitBlocked: Boolean = false,
        dailyUsedMinutes: Int = 0,
        dailyLimitMinutes: Int = 0,
    ) {
        if (newPackageName.isBlank()) return
        val previousState = _uiState.value
        if (newPackageName == packageName &&
            !previousState.isCompleting &&
            dailyLimitBlocked == previousState.isDailyLimitBlocked
        ) {
            return
        }

        val previousPackage = packageName
        val previousAttemptId = attemptId
        val previousIntention = previousState.intentionText()

        breathingJob?.cancel()
        breathingJob = null
        iconJob?.cancel()
        iconJob = null

        if (!previousState.isCompleting && previousPackage.isNotBlank()) {
            cooldownTracker.clear(previousPackage)
            viewModelScope.launch {
                runCatching {
                    interceptionRepository.completeAttempt(
                        id = previousAttemptId.await(),
                        outcome = InterventionOutcome.WALKED_AWAY,
                        intention = previousIntention,
                        sessionLimitMinutes = null,
                    )
                }
            }
        }

        val normalizedModes = newModes.distinct().ifEmpty { listOf(FrictionMode.BREATHING) }
        val firstMode = normalizedModes.first()
        val normalizedUsedMinutes = dailyUsedMinutes.coerceAtLeast(0)
        val normalizedLimitMinutes = dailyLimitMinutes.coerceAtLeast(0)
        val generation = ++interventionGeneration

        packageName = newPackageName
        modes = normalizedModes
        isDailyLimitBlocked = dailyLimitBlocked
        this.dailyUsedMinutes = normalizedUsedMinutes
        this.dailyLimitMinutes = normalizedLimitMinutes
        settings = settingsRepository.settings.value
        breathingSeconds = settings.breathingDurationSeconds

        savedStateHandle[PauseInterventionActivity.EXTRA_PACKAGE_NAME] = newPackageName
        savedStateHandle[PauseInterventionActivity.EXTRA_FRICTION_MODES] =
            ArrayList(normalizedModes.map { it.name })
        savedStateHandle[PauseInterventionActivity.EXTRA_FRICTION_MODE] = firstMode.name
        savedStateHandle[PauseInterventionActivity.EXTRA_DAILY_LIMIT_BLOCKED] = dailyLimitBlocked
        savedStateHandle[PauseInterventionActivity.EXTRA_DAILY_USED_MINUTES] = normalizedUsedMinutes
        savedStateHandle[PauseInterventionActivity.EXTRA_DAILY_LIMIT_MINUTES] = normalizedLimitMinutes
        savedStateHandle.remove<Long>(KEY_ATTEMPT_ID)
        savedStateHandle.remove<Long>(KEY_PAUSE_ENDS_AT)
        savedStateHandle.remove<Int>(KEY_STEP_INDEX)
        savedStateHandle.remove<String>(KEY_MODE)
        savedStateHandle.remove<String>(KEY_SELECTED_REASON)
        savedStateHandle.remove<String>(KEY_NOTE)
        savedStateHandle.remove<Int>(KEY_SELECTED_LIMIT)

        val newLabel = installedAppsRepository.labelFor(newPackageName)
        val durationMillis = (breathingSeconds * 1_000L).coerceAtLeast(0L)
        if (!dailyLimitBlocked && firstMode == FrictionMode.BREATHING) {
            val endsAt = clock.millis() + durationMillis
            pauseEndsAtMillis = endsAt
            savedStateHandle[KEY_PAUSE_ENDS_AT] = endsAt
        } else {
            pauseEndsAtMillis = null
        }

        attemptId = viewModelScope.async {
            interceptionRepository
                .recordAttempt(newPackageName, newLabel, normalizedModes)
                .also { id ->
                    if (interventionGeneration == generation) {
                        savedStateHandle[KEY_ATTEMPT_ID] = id
                    }
                }
        }

        val remainingMillis = if (!dailyLimitBlocked && firstMode == FrictionMode.BREATHING) {
            durationMillis
        } else {
            0L
        }
        _uiState.value = InterventionUiState(
            packageName = newPackageName,
            appLabel = newLabel,
            appIcon = null,
            mode = firstMode,
            modeCount = normalizedModes.size,
            stepIndex = 0,
            breathingSeconds = breathingSeconds,
            remainingMillis = remainingMillis,
            breathProgress = 0f,
            isUnlocked = false,
            selectedReason = null,
            note = "",
            selectedLimitMinutes = null,
            isCompleting = false,
            sessionStartError = false,
            isDailyLimitBlocked = dailyLimitBlocked,
            dailyUsedMinutes = normalizedUsedMinutes,
            dailyLimitMinutes = normalizedLimitMinutes,
        )

        loadAppIcon(newPackageName)
        if (!dailyLimitBlocked && firstMode == FrictionMode.BREATHING) {
            startBreathingTimer()
        }
    }

    /** Tapping a selected intention again clears it, re-locking the CTA. */
    fun selectReason(reason: String) {
        if (_uiState.value.isCompleting || _uiState.value.isDailyLimitBlocked) return
        _uiState.update { state ->
            val next = if (state.selectedReason == reason) null else reason
            state.copy(selectedReason = next).withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun updateNote(note: String) {
        if (_uiState.value.isCompleting || _uiState.value.isDailyLimitBlocked) return
        _uiState.update { state ->
            state.copy(note = note.take(PauseSettings.MAX_INTENTION_LENGTH))
                .withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun selectSessionLimit(minutes: Int) {
        if (_uiState.value.isCompleting || _uiState.value.isDailyLimitBlocked) return
        if (minutes !in PauseSettings.SESSION_LIMIT_OPTIONS) return
        _uiState.update {
            it.copy(selectedLimitMinutes = minutes).withIntentionUnlock()
        }
        persistPauseProgress()
    }

    fun continueToTargetApp() {
        val state = _uiState.value
        if (state.isDailyLimitBlocked || !state.isUnlocked || state.isCompleting || packageName.isBlank()) {
            return
        }

        // An intermediate step advances the sequence instead of launching the
        // target app. No cooldown/log outcome is written until the final step.
        if (state.hasNextStep) {
            advanceToNextStep(state)
            return
        }

        val targetPackage = packageName
        val currentAttemptId = attemptId
        val currentSettings = settings
        val intention = state.intentionText()
        val limitMinutes = state.selectedLimitMinutes
        val generation = interventionGeneration
        _uiState.update { it.copy(isCompleting = true, sessionStartError = false) }

        viewModelScope.launch {
            val attemptRowId = try {
                currentAttemptId.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (interventionGeneration == generation) {
                    _uiState.update {
                        it.copy(isCompleting = false, sessionStartError = true)
                    }
                }
                return@launch
            }

            if (limitMinutes != null) {
                // The target app stays closed until the deadline is safely on
                // disk. A failure surfaces a retry path instead of silently
                // opening the app without its session. The attempt id travels
                // with the deadline so startup can repair a half-finished start.
                val sessionResult = try {
                    sessionStarter.startSession(
                        packageName = targetPackage,
                        appLabel = state.appLabel,
                        limitMinutes = limitMinutes,
                        attemptId = attemptRowId,
                        intention = intention,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                if (sessionResult.isFailure) {
                    if (interventionGeneration == generation) {
                        _uiState.update {
                            it.copy(isCompleting = false, sessionStartError = true)
                        }
                    }
                    return@launch
                }
            }

            runCatching {
                val cooldownMillis = limitMinutes?.let { it * 60_000L }
                    ?: (currentSettings.cooldownMinutes * 60_000L)
                cooldownTracker.setCooldown(targetPackage, cooldownMillis)
                interceptionRepository.completeAttempt(
                    id = attemptRowId,
                    outcome = InterventionOutcome.PROCEEDED,
                    intention = intention,
                    sessionLimitMinutes = limitMinutes,
                )
            }
            if (interventionGeneration != generation) return@launch
            val launchIntent = installedAppsRepository.launchIntentFor(targetPackage)
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
        if (_uiState.value.isDailyLimitBlocked || !_uiState.value.sessionStartError) return
        _uiState.update { it.copy(sessionStartError = false) }
        continueToTargetApp()
    }

    /**
     * Moves to the next mode in the user's ordered friction sequence. Entering
     * a BREATHING step initializes and persists a fresh countdown deadline;
     * intention and time-limit steps unlock from the user's selections.
     */
    private fun advanceToNextStep(state: InterventionUiState) {
        val nextIndex = state.stepIndex + 1
        val nextMode = modes.getOrNull(nextIndex) ?: return
        breathingJob?.cancel()
        breathingJob = null

        if (nextMode == FrictionMode.BREATHING) {
            val durationMillis = (breathingSeconds * 1_000L).coerceAtLeast(0L)
            val endsAt = clock.millis() + durationMillis
            pauseEndsAtMillis = endsAt
            savedStateHandle[KEY_PAUSE_ENDS_AT] = endsAt
            _uiState.update {
                it.copy(
                    mode = nextMode,
                    stepIndex = nextIndex,
                    remainingMillis = durationMillis,
                    breathProgress = 0f,
                    isUnlocked = false,
                )
            }
            persistPauseProgress()
            startBreathingTimer()
            return
        }

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
        val targetPackage = packageName
        val currentAttemptId = attemptId
        val intention = state.intentionText()
        val generation = interventionGeneration
        breathingJob?.cancel()
        breathingJob = null
        _uiState.update { it.copy(isCompleting = true) }

        viewModelScope.launch {
            runCatching {
                sessionStarter.cancelSession(targetPackage)
                // Leaving the pause should not grant a free pass for the few
                // seconds of the launch guard; the next launch is intercepted.
                cooldownTracker.clear(targetPackage)
                interceptionRepository.completeAttempt(
                    id = currentAttemptId.await(),
                    outcome = InterventionOutcome.WALKED_AWAY,
                    intention = intention,
                    sessionLimitMinutes = null,
                )
            }
            if (interventionGeneration == generation) {
                _effects.send(InterventionEffect.GoHome)
            }
        }
    }

    private fun InterventionUiState.withIntentionUnlock(): InterventionUiState {
        if (isDailyLimitBlocked) return copy(isUnlocked = false)
        val unlocked = when (mode) {
            FrictionMode.BREATHING -> remainingMillis == 0L
            FrictionMode.INTENTION -> selectedReason != null || note.isNotBlank()
            FrictionMode.TIME_LIMIT -> selectedLimitMinutes != null
        }
        return copy(isUnlocked = unlocked)
    }

    private fun InterventionUiState.intentionText(): String? {
        // Preserve an intention entered in an earlier step of this pause.
        if (isDailyLimitBlocked || FrictionMode.INTENTION !in modes) return null
        val parts = listOfNotNull(
            selectedReason?.takeIf { it.isNotBlank() },
            note.trim().takeIf { it.isNotBlank() },
        )
        return parts.joinToString(separator = " — ").takeIf { it.isNotBlank() }
    }

    override fun onCleared() {
        breathingJob?.cancel()
        iconJob?.cancel()
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

internal fun parseModes(
    encoded: List<String>?,
    legacy: String?,
): List<FrictionMode> {
    val parsed = encoded
        .orEmpty()
        .mapNotNull { runCatching { FrictionMode.valueOf(it) }.getOrNull() }
    val fallback = legacy
        ?.let { runCatching { FrictionMode.valueOf(it) }.getOrNull() }

    return parsed.ifEmpty { listOfNotNull(fallback) }
        .distinct()
        .ifEmpty { listOf(FrictionMode.BREATHING) }
}

private fun readModes(savedStateHandle: SavedStateHandle): List<FrictionMode> = parseModes(
    encoded = savedStateHandle.get<ArrayList<String>>(PauseInterventionActivity.EXTRA_FRICTION_MODES),
    legacy = savedStateHandle.get<String>(PauseInterventionActivity.EXTRA_FRICTION_MODE),
)
