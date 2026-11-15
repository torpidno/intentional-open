package com.pause.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.app.BuildConfig
import com.pause.app.R
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.ui.components.ChoiceChips
import com.pause.app.ui.components.FrictionSequenceEditor
import com.pause.app.ui.components.PauseCard
import com.pause.app.ui.components.PrivacyFooter
import com.pause.app.ui.components.SectionHeader
import com.pause.app.ui.components.StatusPill
import com.pause.app.ui.theme.PauseDimens
import com.pause.app.util.PermissionSnapshot
import com.pause.app.util.PermissionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    // Re-check permissions every time the user returns from a system screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    LaunchedEffect(viewModel) {
        viewModel.openSettings.collect { intent ->
            runCatching { context.startActivity(intent) }
                .onFailure {
                    // Some OEMs do not implement a specific page; fall back to
                    // the top-level settings app rather than doing nothing.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = PauseDimens.contentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = PauseDimens.space24,
                    end = PauseDimens.space24,
                    top = PauseDimens.space8,
                    bottom = PauseDimens.space32,
                ),
                verticalArrangement = Arrangement.spacedBy(PauseDimens.space16),
            ) {
                item {
                    SetupProgressCard(permissions = state.permissions)
                }

                item {
                    PermissionCard(
                        step = 1,
                        title = stringResource(R.string.permission_accessibility_title),
                        body = stringResource(R.string.permission_accessibility_body),
                        granted = state.permissions.accessibilityGranted,
                        onOpen = {
                            viewModel.openPermissionSettings(PermissionType.ACCESSIBILITY)
                        },
                    )
                }
                item {
                    PermissionCard(
                        step = 2,
                        title = stringResource(R.string.permission_overlay_title),
                        body = stringResource(R.string.permission_overlay_body),
                        granted = state.permissions.overlayGranted,
                        onOpen = { viewModel.openPermissionSettings(PermissionType.OVERLAY) },
                    )
                }
                item {
                    PermissionCard(
                        step = 3,
                        title = stringResource(R.string.permission_battery_title),
                        body = stringResource(R.string.permission_battery_body),
                        granted = state.permissions.batteryOptimizationIgnored,
                        onOpen = {
                            viewModel.openPermissionSettings(PermissionType.BATTERY_OPTIMIZATION)
                        },
                        secondaryActionLabel = stringResource(R.string.permission_battery_list),
                        onSecondaryAction = viewModel::openBatteryOptimizationList,
                    )
                }

                item {
                    PermissionCard(
                        step = 4,
                        title = stringResource(R.string.permission_notifications_title),
                        body = stringResource(R.string.permission_notifications_body),
                        granted = state.permissions.notificationsGranted,
                        onOpen = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !state.permissions.notificationsGranted
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            } else {
                                viewModel.openPermissionSettings(PermissionType.NOTIFICATIONS)
                            }
                        },
                        secondaryActionLabel = stringResource(
                            R.string.permission_notifications_settings,
                        ),
                        onSecondaryAction = {
                            viewModel.openPermissionSettings(PermissionType.NOTIFICATIONS)
                        },
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.settings_preferences_title),
                        modifier = Modifier.padding(top = PauseDimens.space8),
                    )
                }

                item {
                    PreferenceCard(
                        title = stringResource(R.string.settings_cooldown_title),
                        body = stringResource(R.string.settings_cooldown_body),
                    ) {
                        ChoiceChips(
                            options = PauseSettings.COOLDOWN_OPTIONS,
                            selected = state.settings.cooldownMinutes,
                            label = { stringResource(R.string.settings_cooldown_option, it) },
                            onSelect = viewModel::setCooldownMinutes,
                        )
                    }
                }

                item {
                    PreferenceCard(
                        title = stringResource(R.string.settings_breathing_title),
                        body = stringResource(R.string.settings_breathing_body),
                    ) {
                        ChoiceChips(
                            options = PauseSettings.BREATHING_OPTIONS,
                            selected = state.settings.breathingDurationSeconds,
                            label = { stringResource(R.string.settings_breathing_option, it) },
                            onSelect = viewModel::setBreathingDurationSeconds,
                        )
                    }
                }

                item {
                    PreferenceCard(
                        title = stringResource(R.string.settings_default_mode_title),
                        body = stringResource(R.string.settings_default_mode_body),
                    ) {
                        FrictionSequenceEditor(
                            selectedModes = state.settings.defaultFrictionModes,
                            onToggle = viewModel::toggleDefaultFrictionMode,
                            onMove = viewModel::moveDefaultFrictionMode,
                        )
                        Spacer(Modifier.height(PauseDimens.space8))
                        Text(
                            text = stringResource(R.string.settings_default_mode_minimum),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    PrivacyCard()
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PauseDimens.space8),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupProgressCard(permissions: PermissionSnapshot) {
    PauseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_permissions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.permission_progress,
                    permissions.grantedCount,
                    PermissionSnapshot.TOTAL_COUNT,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(PauseDimens.space16))
        LinearProgressIndicator(
            progress = {
                permissions.grantedCount.toFloat() / PermissionSnapshot.TOTAL_COUNT
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = stringResource(R.string.settings_permissions_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(
    step: Int,
    title: String,
    body: String,
    granted: Boolean,
    onOpen: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    PauseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (granted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = step.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (granted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
            Spacer(Modifier.width(PauseDimens.space16))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(PauseDimens.space8))
            StatusPill(granted = granted)
        }
        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                PauseDimens.space8,
                Alignment.End,
            ),
            verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
        ) {
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                ) {
                    Text(
                        text = secondaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (granted) {
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                ) {
                    Text(
                        text = stringResource(R.string.permission_open),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(
                        horizontal = PauseDimens.space24,
                        vertical = PauseDimens.space8,
                    ),
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PauseCard {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(PauseDimens.space8))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        content()
    }
}

@Composable
private fun PrivacyCard() {
    PauseCard {
        Text(
            text = stringResource(R.string.settings_privacy_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(PauseDimens.space8))
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        PrivacyFooter()
    }
}
