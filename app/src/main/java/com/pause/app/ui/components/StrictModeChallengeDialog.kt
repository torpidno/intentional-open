package com.pause.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pause.app.R
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.domain.model.StrictModePolicy
import com.pause.app.ui.theme.PauseDimens
import kotlinx.coroutines.delay

/**
 * Calm, always-recoverable confirmation dialog shown before weakening Pause
 * settings when Strict Mode is enabled.
 *
 * Supports either a brief [StrictModeChallenge.DELAY] countdown or a
 * [StrictModeChallenge.PHRASE] typing check, and always exposes a Cancel action.
 */
@Composable
fun StrictModeChallengeDialog(
    actionDescription: String,
    challenge: StrictModeChallenge,
    delaySeconds: Int,
    phrase: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    val totalSeconds = delaySeconds.coerceAtLeast(1)
    val targetPhrase = remember(phrase) {
        phrase.trim().ifEmpty { PauseSettings.DEFAULT_STRICT_MODE_PHRASE }
    }

    var remainingSeconds by remember(challenge, totalSeconds) {
        mutableIntStateOf(totalSeconds)
    }
    var typedPhrase by remember(challenge, targetPhrase) {
        mutableStateOf("")
    }

    LaunchedEffect(challenge, totalSeconds) {
        if (challenge == StrictModeChallenge.DELAY) {
            remainingSeconds = totalSeconds
            while (remainingSeconds > 0) {
                delay(1_000L)
                remainingSeconds -= 1
            }
        }
    }

    val canConfirm = when (challenge) {
        StrictModeChallenge.DELAY -> remainingSeconds == 0
        StrictModeChallenge.PHRASE -> StrictModePolicy.matchesPhrase(typedPhrase, targetPhrase)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.strict_mode_challenge_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (actionDescription.isNotBlank()) {
                    Text(
                        text = actionDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(PauseDimens.space8))
                }
                Text(
                    text = stringResource(R.string.strict_mode_challenge_reassurance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PauseDimens.space16))

                when (challenge) {
                    StrictModeChallenge.DELAY -> {
                        val progress = ((totalSeconds - remainingSeconds).toFloat() / totalSeconds)
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(PauseDimens.space8))
                        Text(
                            text = if (remainingSeconds > 0) {
                                stringResource(
                                    R.string.strict_mode_delay_remaining,
                                    remainingSeconds,
                                )
                            } else {
                                stringResource(R.string.strict_mode_delay_ready)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    StrictModeChallenge.PHRASE -> {
                        Text(
                            text = stringResource(R.string.strict_mode_phrase_instruction),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(PauseDimens.space8))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(
                                text = targetPhrase,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(PauseDimens.space16),
                            )
                        }
                        Spacer(Modifier.height(PauseDimens.space16))
                        OutlinedTextField(
                            value = typedPhrase,
                            onValueChange = { typedPhrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.strict_mode_phrase_label))
                            },
                            placeholder = {
                                Text(stringResource(R.string.strict_mode_phrase_placeholder))
                            },
                            supportingText = if (canConfirm) {
                                {
                                    Text(stringResource(R.string.strict_mode_phrase_matched))
                                }
                            } else {
                                null
                            },
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (canConfirm) onConfirmed()
                                },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmed,
                enabled = canConfirm,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.strict_mode_challenge_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.strict_mode_challenge_cancel))
            }
        },
    )
}
