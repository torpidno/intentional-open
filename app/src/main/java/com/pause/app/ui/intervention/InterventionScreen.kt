package com.pause.app.ui.intervention

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pause.app.R
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.FrictionMode
import com.pause.app.ui.components.AppGlyph
import com.pause.app.ui.components.pauseClickable
import com.pause.app.ui.theme.PauseDimens

/**
 * Full-screen pause surface. The active friction steps live in the middle; the
 * close (primary) and continue (secondary) actions stay pinned to the bottom so
 * they remain reachable on tall phones and in landscape.
 */
@Composable
fun InterventionScreen(
    state: InterventionUiState,
    onReasonSelected: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onLimitSelected: (Int) -> Unit,
    onWalkAway: () -> Unit,
    onContinue: () -> Unit,
    onRetrySessionStart: () -> Unit,
    onDismissSessionStartError: () -> Unit,
) {
    // Back behaves exactly like the primary CTA: no silent bypass.
    BackHandler(enabled = !state.isCompleting) { onWalkAway() }

    val appName = state.appLabel.ifBlank { stringResource(R.string.unknown_app) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PauseDimens.readingMaxWidth)
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = PauseDimens.space24),
        ) {
            PauseHeader(state = state, appName = appName)

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state.isDailyLimitBlocked) {
                        DailyLimitBlockedContent(
                            state = state,
                            appName = appName,
                        )
                    } else {
                        when (state.mode) {
                            FrictionMode.BREATHING -> BreathingContent(state = state)
                            FrictionMode.INTENTION -> IntentionContent(
                                state = state,
                                onReasonSelected = onReasonSelected,
                                onNoteChanged = onNoteChanged,
                            )

                            FrictionMode.TIME_LIMIT -> TimeLimitContent(
                                state = state,
                                onLimitSelected = onLimitSelected,
                            )
                        }
                    }
                }
            }

            PauseActions(
                state = state,
                appName = appName,
                onContinue = onContinue,
                onWalkAway = onWalkAway,
            )
        }
    }

    if (!state.isDailyLimitBlocked && state.sessionStartError) {
        AlertDialog(
            onDismissRequest = onDismissSessionStartError,
            title = { Text(stringResource(R.string.session_start_error_title)) },
            text = {
                Text(stringResource(R.string.session_start_error_body, appName))
            },
            confirmButton = {
                TextButton(onClick = onRetrySessionStart) {
                    Text(stringResource(R.string.session_start_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSessionStartError) {
                    Text(stringResource(R.string.session_start_keep_closed))
                }
            },
        )
    }
}

@Composable
private fun PauseHeader(state: InterventionUiState, appName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PauseDimens.space16, bottom = PauseDimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppGlyph(
            icon = state.appIcon,
            label = appName,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(PauseDimens.space16))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.intervention_pausing),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.intervention_opening, appName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.isDailyLimitBlocked && state.modeCount > 1) {
                Text(
                    text = stringResource(
                        R.string.intervention_step,
                        state.stepIndex + 1,
                        state.modeCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PauseActions(
    state: InterventionUiState,
    appName: String,
    onContinue: () -> Unit,
    onWalkAway: () -> Unit,
) {
    val hint = when {
        state.isCompleting -> stringResource(R.string.hint_working)
        state.isDailyLimitBlocked -> null
        state.hasNextStep && state.isUnlocked -> stringResource(R.string.hint_next_step)
        !state.isUnlocked && state.mode == FrictionMode.BREATHING ->
            stringResource(R.string.hint_breathing_locked, state.remainingSeconds)

        !state.isUnlocked && state.mode == FrictionMode.INTENTION ->
            stringResource(R.string.hint_intention)

        !state.isUnlocked -> stringResource(R.string.hint_time_limit)
        state.mode == FrictionMode.BREATHING -> stringResource(R.string.hint_breathing_ready)
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PauseDimens.space16, bottom = PauseDimens.space16),
        verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(PauseDimens.space8))

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = PauseDimens.space4),
            )
        }

        if (!state.isDailyLimitBlocked) {
            // Secondary action: continue into the app (unlocked by the pause).
            OutlinedButton(
                onClick = onContinue,
                enabled = state.isUnlocked && !state.isCompleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (state.isUnlocked && !state.isCompleting) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = if (state.hasNextStep) {
                        stringResource(R.string.continue_next)
                    } else {
                        stringResource(R.string.continue_to_app, appName)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Primary action: walk away and go home.
        Button(
            onClick = onWalkAway,
            enabled = !state.isCompleting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(stringResource(R.string.close_and_leave))
        }
    }
}

@Composable
private fun DailyLimitBlockedContent(
    state: InterventionUiState,
    appName: String,
) {
    val usageBody = if (state.dailyLimitMinutes > 0) {
        stringResource(
            R.string.budget_blocked_usage,
            state.dailyUsedMinutes,
            state.dailyLimitMinutes,
            appName,
        )
    } else {
        stringResource(R.string.budget_blocked_usage_generic, appName)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.budget_blocked_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = usageBody,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space24))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = stringResource(R.string.budget_blocked_resets),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PauseDimens.space16),
            )
        }
    }
}

@Composable
private fun BreathingContent(state: InterventionUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.breathing_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space8))
        Text(
            text = stringResource(R.string.breathing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space24))

        BreathingRing(
            progress = state.breathProgress,
            remainingSeconds = state.remainingSeconds,
            unlocked = state.isUnlocked,
        )

        Spacer(Modifier.height(PauseDimens.space24))
        Text(
            text = stringResource(
                if (state.isUnlocked) R.string.breathing_ready else R.string.breathing_inhale,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BreathingRing(
    progress: Float,
    remainingSeconds: Int,
    unlocked: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val strokeWidth = 6.dp.toPx()

            drawCircle(
                color = primary.copy(alpha = 0.09f),
                radius = radius * pulse,
            )
            drawCircle(
                color = primary.copy(alpha = 0.13f),
                radius = radius * 0.76f,
            )
            drawCircle(
                color = track,
                radius = radius - strokeWidth / 2f,
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        if (unlocked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = primary,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = remainingSeconds.coerceAtLeast(0).toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    text = stringResource(R.string.breathing_seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentionContent(
    state: InterventionUiState,
    onReasonSelected: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
) {
    val reasons = listOf(
        stringResource(R.string.reason_message),
        stringResource(R.string.reason_lookup),
        stringResource(R.string.reason_bored),
        stringResource(R.string.reason_work),
    )
    val boredReason = stringResource(R.string.reason_bored)
    val remainingChars = (PauseSettings.MAX_INTENTION_LENGTH - state.note.length)
        .coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.intention_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space8))
        Text(
            text = stringResource(R.string.intention_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space24))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                PauseDimens.space8,
                Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
        ) {
            reasons.forEach { reason ->
                FilterChip(
                    selected = state.selectedReason == reason,
                    onClick = { onReasonSelected(reason) },
                    enabled = !state.isCompleting,
                    label = { Text(reason) },
                )
            }
        }

        Spacer(Modifier.height(PauseDimens.space24))

        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isCompleting,
            placeholder = { Text(stringResource(R.string.intention_placeholder)) },
            supportingText = {
                Text(
                    pluralStringResource(
                        R.plurals.intention_chars_left,
                        remainingChars,
                        remainingChars,
                    ),
                )
            },
            minLines = 2,
            maxLines = 3,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        if (state.selectedReason == boredReason) {
            Spacer(Modifier.height(PauseDimens.space8))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.intention_bored_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(PauseDimens.space16),
                )
            }
        }
    }
}

@Composable
private fun TimeLimitContent(
    state: InterventionUiState,
    onLimitSelected: (Int) -> Unit,
) {
    val options = listOf(
        Triple(5, R.string.limit_5_title, R.string.limit_5_body),
        Triple(15, R.string.limit_15_title, R.string.limit_15_body),
        Triple(30, R.string.limit_30_title, R.string.limit_30_body),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.time_limit_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space8))
        Text(
            text = stringResource(R.string.time_limit_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PauseDimens.space24))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
        ) {
            options.forEach { (minutes, titleRes, bodyRes) ->
                val selected = state.selectedLimitMinutes == minutes
                val shape = MaterialTheme.shapes.medium
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = shape,
                        )
                        .pauseClickable(
                            shape = shape,
                            enabled = !state.isCompleting,
                            minHeight = 64.dp,
                            onClick = { onLimitSelected(minutes) },
                        )
                        .padding(
                            horizontal = PauseDimens.space16,
                            vertical = PauseDimens.space16,
                        ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = stringResource(bodyRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        RadioButton(
                            selected = selected,
                            onClick = { onLimitSelected(minutes) },
                            enabled = !state.isCompleting,
                        )
                    }
                }
            }
        }
    }
}
