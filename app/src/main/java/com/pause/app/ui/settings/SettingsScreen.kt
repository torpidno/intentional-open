package com.pause.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.app.BuildConfig
import com.pause.app.R
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StrictModeChallenge
import com.pause.app.ui.components.ChoiceChips
import com.pause.app.ui.components.FrictionSequenceEditor
import com.pause.app.ui.components.PauseCard
import com.pause.app.ui.components.PrivacyFooter
import com.pause.app.ui.components.SectionHeader
import com.pause.app.ui.components.StatusPill
import com.pause.app.ui.components.StrictModeChallengeDialog
import com.pause.app.ui.components.pauseClickable
import com.pause.app.ui.theme.PauseDimens
import com.pause.app.util.PermissionSnapshot
import com.pause.app.util.PermissionType
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_CUSTOM_PROFILE_NAME_LENGTH = 40

private val WEEKDAYS_SET = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

private val WEEKEND_SET = setOf(
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var is24Hour by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    var pendingStrictAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    // Re-check permissions and clock format every time the user returns from a system screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
        is24Hour = DateFormat.is24HourFormat(context)
    }

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

    fun guardWeakeningAction(
        isWeakening: Boolean,
        actionDescription: String,
        action: () -> Unit,
    ) {
        if (isWeakening && state.settings.strictModeEnabled) {
            pendingStrictAction = actionDescription to action
        } else {
            action()
        }
    }

    val activeProfileName = state.settings.activeProfile?.name

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
                    OptionalPermissionCard(
                        title = stringResource(R.string.permission_usage_access_title),
                        body = stringResource(R.string.permission_usage_access_body),
                        granted = state.permissions.usageAccessGranted,
                        onOpen = { viewModel.openPermissionSettings(PermissionType.USAGE_ACCESS) },
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
                            onSelect = { newCooldown ->
                                guardWeakeningAction(
                                    isWeakening = newCooldown > state.settings.cooldownMinutes,
                                    actionDescription = context.getString(
                                        R.string.strict_action_lengthen_cooldown,
                                        newCooldown,
                                    ),
                                ) {
                                    viewModel.setCooldownMinutes(newCooldown)
                                }
                            },
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
                            onSelect = { newSeconds ->
                                guardWeakeningAction(
                                    isWeakening = newSeconds < state.settings.breathingDurationSeconds,
                                    actionDescription = context.getString(
                                        R.string.strict_action_shorten_breathing,
                                        newSeconds,
                                    ),
                                ) {
                                    viewModel.setBreathingDurationSeconds(newSeconds)
                                }
                            },
                        )
                    }
                }

                item {
                    ProfilesCard(
                        settings = state.settings,
                        is24Hour = is24Hour,
                        onSelectProfile = viewModel::selectProfile,
                        onSaveProfile = viewModel::saveProfile,
                        onDeleteProfile = { profile ->
                            guardWeakeningAction(
                                isWeakening = true,
                                actionDescription = context.getString(
                                    R.string.strict_action_delete_profile,
                                    profile.name,
                                ),
                            ) {
                                viewModel.deleteProfile(profile.id)
                            }
                        },
                        onAutoSwitchChange = viewModel::setAutoSwitchProfiles,
                    )
                }

                item {
                    PreferenceCard(
                        title = stringResource(R.string.settings_default_mode_title),
                        body = stringResource(R.string.settings_default_mode_body),
                        supportingCaption = activeProfileName?.let {
                            stringResource(R.string.settings_profiles_applies_to_active, it)
                        },
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
                    SchedulePreferenceCard(
                        settings = state.settings,
                        is24Hour = is24Hour,
                        activeProfileName = activeProfileName,
                        onEnabledChange = { enabled ->
                            guardWeakeningAction(
                                isWeakening = !enabled,
                                actionDescription = context.getString(
                                    R.string.strict_action_disable_schedule,
                                ),
                            ) {
                                viewModel.setScheduleEnabled(enabled)
                            }
                        },
                        onStartChange = { start ->
                            viewModel.setScheduleWindow(
                                startMinutes = start,
                                endMinutes = state.settings.scheduleEndMinutes,
                            )
                        },
                        onEndChange = { end ->
                            viewModel.setScheduleWindow(
                                startMinutes = state.settings.scheduleStartMinutes,
                                endMinutes = end,
                            )
                        },
                        onDayToggle = viewModel::toggleScheduleDay,
                    )
                }

                item {
                    StrictModeCard(
                        settings = state.settings,
                        onEnabledChange = { enabled ->
                            guardWeakeningAction(
                                isWeakening = !enabled,
                                actionDescription = context.getString(
                                    R.string.strict_action_disable_strict_mode,
                                ),
                            ) {
                                viewModel.setStrictModeEnabled(enabled)
                            }
                        },
                        onChallengeChange = viewModel::setStrictModeChallenge,
                        onDelayChange = { newDelay ->
                            guardWeakeningAction(
                                isWeakening = newDelay < state.settings.strictModeDelaySeconds,
                                actionDescription = context.getString(
                                    R.string.strict_action_shorten_strict_delay,
                                    newDelay,
                                ),
                            ) {
                                viewModel.setStrictModeDelaySeconds(newDelay)
                            }
                        },
                        onPhraseSave = viewModel::setStrictModePhrase,
                    )
                }

                item {
                    PreferenceCard(
                        title = stringResource(R.string.settings_retention_title),
                        body = stringResource(R.string.settings_retention_body),
                    ) {
                        ChoiceChips(
                            options = PauseSettings.HISTORY_RETENTION_OPTIONS,
                            selected = state.settings.historyRetentionDays,
                            label = { days ->
                                if (days == 0) {
                                    stringResource(R.string.settings_retention_forever)
                                } else {
                                    stringResource(R.string.settings_retention_days, days)
                                }
                            },
                            onSelect = viewModel::setHistoryRetentionDays,
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

    pendingStrictAction?.let { (description, confirmedAction) ->
        StrictModeChallengeDialog(
            actionDescription = description,
            challenge = state.settings.strictModeChallenge,
            delaySeconds = state.settings.strictModeDelaySeconds,
            phrase = state.settings.effectiveStrictModePhrase,
            onDismiss = { pendingStrictAction = null },
            onConfirmed = {
                pendingStrictAction = null
                confirmedAction()
            },
        )
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
private fun OptionalPermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    PauseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (granted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (granted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
            if (granted) {
                StatusPill(granted = true)
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = stringResource(R.string.permission_optional_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = PauseDimens.space16,
                            vertical = PauseDimens.space8,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
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
                OutlinedButton(
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
    supportingCaption: String? = null,
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
        if (!supportingCaption.isNullOrBlank()) {
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = supportingCaption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(PauseDimens.space16))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilesCard(
    settings: PauseSettings,
    is24Hour: Boolean,
    onSelectProfile: (String?) -> Unit,
    onSaveProfile: (PauseProfile, Boolean) -> Unit,
    onDeleteProfile: (PauseProfile) -> Unit,
    onAutoSwitchChange: (Boolean) -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    val workLabel = stringResource(R.string.settings_profiles_preset_work)
    val eveningLabel = stringResource(R.string.settings_profiles_preset_evening)
    val weekendLabel = stringResource(R.string.settings_profiles_preset_weekend)

    PreferenceCard(
        title = stringResource(R.string.settings_profiles_title),
        body = stringResource(R.string.settings_profiles_body),
    ) {
        val activeProfile = settings.activeProfile
        if (activeProfile != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = PauseDimens.space16,
                        end = PauseDimens.space8,
                        top = PauseDimens.space8,
                        bottom = PauseDimens.space8,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_profiles_active_banner,
                            activeProfile.name,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onSelectProfile(null) },
                        modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_profiles_unlink),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(PauseDimens.space16))
        }

        if (settings.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_profiles_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(PauseDimens.space8)) {
                settings.profiles.forEach { profile ->
                    key(profile.id) {
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == settings.activeProfileId,
                            is24Hour = is24Hour,
                            onSelect = {
                                if (profile.id != settings.activeProfileId) {
                                    onSelectProfile(profile.id)
                                }
                            },
                            onDelete = { onDeleteProfile(profile) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = stringResource(R.string.settings_profiles_quick_add),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(PauseDimens.space8))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PauseDimens.space8),
            verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
        ) {
            OutlinedButton(
                onClick = {
                    val (id, name) = nextUniquePreset("work", workLabel, settings.profiles)
                    onSaveProfile(
                        PauseProfile.workPreset(
                            id = id,
                            monitoredPackages = settings.monitoredPackages,
                            name = name,
                        ),
                        true,
                    )
                },
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(workLabel)
            }
            OutlinedButton(
                onClick = {
                    val (id, name) = nextUniquePreset("evening", eveningLabel, settings.profiles)
                    onSaveProfile(
                        PauseProfile.eveningPreset(
                            id = id,
                            monitoredPackages = settings.monitoredPackages,
                            name = name,
                        ),
                        true,
                    )
                },
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(eveningLabel)
            }
            OutlinedButton(
                onClick = {
                    val (id, name) = nextUniquePreset("weekend", weekendLabel, settings.profiles)
                    onSaveProfile(
                        PauseProfile.weekendPreset(
                            id = id,
                            monitoredPackages = settings.monitoredPackages,
                            name = name,
                        ),
                        true,
                    )
                },
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(weekendLabel)
            }
            OutlinedButton(
                onClick = { showCustomDialog = true },
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.settings_profiles_preset_custom))
            }
        }

        if (settings.profiles.isNotEmpty()) {
            Spacer(Modifier.height(PauseDimens.space16))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_profiles_auto_switch_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(PauseDimens.space4))
                    Text(
                        text = stringResource(R.string.settings_profiles_auto_switch_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(PauseDimens.space16))
                Switch(
                    checked = settings.autoSwitchProfiles,
                    onCheckedChange = onAutoSwitchChange,
                )
            }
        }
    }

    if (showCustomDialog) {
        CreateCustomProfileDialog(
            onDismiss = { showCustomDialog = false },
            onCreate = { trimmedName ->
                val profile = PauseProfile(
                    id = uniqueCustomProfileId(trimmedName, settings.profiles),
                    name = trimmedName,
                    monitoredPackages = settings.monitoredPackages,
                    pausedPackages = settings.pausedPackages,
                    defaultFrictionModes = settings.defaultFrictionModes,
                    appModeOverrides = settings.appModeOverrides,
                    scheduleEnabled = settings.scheduleEnabled,
                    scheduleDays = settings.scheduleDays,
                    scheduleStartMinutes = settings.scheduleStartMinutes,
                    scheduleEndMinutes = settings.scheduleEndMinutes,
                )
                onSaveProfile(profile, true)
                showCustomDialog = false
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: PauseProfile,
    selected: Boolean,
    is24Hour: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val scheduleSummary = profileScheduleSummary(profile = profile, is24Hour = is24Hour)
    val appCountText = pluralStringResource(
        R.plurals.settings_profiles_app_count,
        profile.monitoredPackages.size,
        profile.monitoredPackages.size,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pauseClickable(shape = shape, onClick = onSelect)
                .padding(
                    start = PauseDimens.space16,
                    end = PauseDimens.space8,
                    top = PauseDimens.space8,
                    bottom = PauseDimens.space8,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PauseDimens.space8),
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_profiles_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(
                                    horizontal = PauseDimens.space8,
                                    vertical = 2.dp,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PauseDimens.space4))
                Text(
                    text = stringResource(
                        R.string.settings_profiles_row_meta,
                        scheduleSummary,
                        appCountText,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_profile, profile.name),
                    tint = subtitleColor,
                )
            }
        }
    }
}

@Composable
private fun CreateCustomProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_profiles_custom_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_profiles_custom_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PauseDimens.space16))
                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        name = input.take(MAX_CUSTOM_PROFILE_NAME_LENGTH)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_profiles_custom_name_label)) },
                    placeholder = {
                        Text(stringResource(R.string.settings_profiles_custom_name_placeholder))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (trimmed.isNotEmpty()) onCreate(trimmed)
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(trimmed) },
                enabled = trimmed.isNotEmpty(),
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.settings_profiles_custom_create))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.settings_schedule_cancel))
            }
        },
    )
}

@Composable
private fun profileScheduleSummary(
    profile: PauseProfile,
    is24Hour: Boolean,
): String {
    if (!profile.scheduleEnabled || profile.scheduleDays.isEmpty()) {
        return stringResource(R.string.settings_profiles_manual_only)
    }
    val daysSummary = when {
        profile.scheduleDays.size == DayOfWeek.entries.size ->
            stringResource(R.string.settings_profiles_days_every_day)
        profile.scheduleDays == WEEKDAYS_SET ->
            stringResource(R.string.settings_profiles_days_weekdays)
        profile.scheduleDays == WEEKEND_SET ->
            stringResource(R.string.settings_profiles_days_weekend)
        else -> DayOfWeek.entries
            .filter { it in profile.scheduleDays }
            .map { stringResource(it.labelRes()) }
            .joinToString(", ")
    }
    val windowSummary = if (profile.scheduleStartMinutes == profile.scheduleEndMinutes) {
        stringResource(R.string.settings_profiles_all_day)
    } else {
        stringResource(
            R.string.settings_profiles_time_range,
            formatScheduleMinutes(profile.scheduleStartMinutes, is24Hour),
            formatScheduleMinutes(profile.scheduleEndMinutes, is24Hour),
        )
    }
    return stringResource(R.string.settings_profiles_schedule_summary, daysSummary, windowSummary)
}

private fun nextUniquePreset(
    baseId: String,
    baseName: String,
    existing: List<PauseProfile>,
): Pair<String, String> {
    val existingIds = existing.map { it.id }.toSet()
    val existingNames = existing.map { it.name.lowercase(Locale.ROOT) }.toSet()
    if (baseId !in existingIds && baseName.lowercase(Locale.ROOT) !in existingNames) {
        return baseId to baseName
    }
    var suffix = 2
    while (
        "$baseId-$suffix" in existingIds ||
        "$baseName $suffix".lowercase(Locale.ROOT) in existingNames
    ) {
        suffix++
    }
    return "$baseId-$suffix" to "$baseName $suffix"
}

private fun uniqueCustomProfileId(
    name: String,
    existing: List<PauseProfile>,
): String {
    val existingIds = existing.map { it.id }.toSet()
    val slug = name.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "custom" }
    if (slug !in existingIds) return slug
    var suffix = 2
    while ("$slug-$suffix" in existingIds) {
        suffix++
    }
    return "$slug-$suffix"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrictModeCard(
    settings: PauseSettings,
    onEnabledChange: (Boolean) -> Unit,
    onChallengeChange: (StrictModeChallenge) -> Unit,
    onDelayChange: (Int) -> Unit,
    onPhraseSave: (String) -> Unit,
) {
    var phraseDraft by remember(settings.strictModePhrase) {
        mutableStateOf(settings.effectiveStrictModePhrase)
    }
    val trimmedDraft = phraseDraft.trim()
    val canSavePhrase = trimmedDraft.isNotEmpty() &&
        trimmedDraft != settings.strictModePhrase.trim()
    val canResetPhrase = settings.effectiveStrictModePhrase != PauseSettings.DEFAULT_STRICT_MODE_PHRASE ||
        trimmedDraft != PauseSettings.DEFAULT_STRICT_MODE_PHRASE

    PreferenceCard(
        title = stringResource(R.string.settings_strict_mode_title),
        body = stringResource(R.string.settings_strict_mode_body),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_strict_mode_enabled),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(PauseDimens.space16))
            Switch(
                checked = settings.strictModeEnabled,
                onCheckedChange = onEnabledChange,
            )
        }

        if (settings.strictModeEnabled) {
            Spacer(Modifier.height(PauseDimens.space16))
            Text(
                text = stringResource(R.string.settings_strict_mode_challenge_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PauseDimens.space8))
            ChoiceChips(
                options = StrictModeChallenge.entries,
                selected = settings.strictModeChallenge,
                label = { challenge ->
                    when (challenge) {
                        StrictModeChallenge.DELAY ->
                            stringResource(R.string.settings_strict_mode_challenge_delay)
                        StrictModeChallenge.PHRASE ->
                            stringResource(R.string.settings_strict_mode_challenge_phrase)
                    }
                },
                onSelect = onChallengeChange,
            )

            Spacer(Modifier.height(PauseDimens.space16))
            when (settings.strictModeChallenge) {
                StrictModeChallenge.DELAY -> {
                    Text(
                        text = stringResource(R.string.settings_strict_mode_delay_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(PauseDimens.space8))
                    ChoiceChips(
                        options = PauseSettings.STRICT_MODE_DELAY_OPTIONS,
                        selected = settings.strictModeDelaySeconds,
                        label = { seconds ->
                            stringResource(R.string.settings_strict_mode_delay_option, seconds)
                        },
                        onSelect = onDelayChange,
                    )
                }

                StrictModeChallenge.PHRASE -> {
                    OutlinedTextField(
                        value = phraseDraft,
                        onValueChange = { input ->
                            phraseDraft = input.take(PauseSettings.MAX_STRICT_MODE_PHRASE_LENGTH)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(stringResource(R.string.settings_strict_mode_phrase_label))
                        },
                        supportingText = {
                            Text(stringResource(R.string.settings_strict_mode_phrase_caption))
                        },
                        maxLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (canSavePhrase) onPhraseSave(trimmedDraft)
                            },
                        ),
                    )
                    Spacer(Modifier.height(PauseDimens.space8))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            PauseDimens.space8,
                            Alignment.End,
                        ),
                        verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
                    ) {
                        TextButton(
                            onClick = {
                                phraseDraft = PauseSettings.DEFAULT_STRICT_MODE_PHRASE
                                onPhraseSave(PauseSettings.DEFAULT_STRICT_MODE_PHRASE)
                            },
                            enabled = canResetPhrase,
                            modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                        ) {
                            Text(stringResource(R.string.settings_strict_mode_phrase_reset))
                        }
                        Button(
                            onClick = { onPhraseSave(trimmedDraft) },
                            enabled = canSavePhrase,
                            modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(R.string.settings_strict_mode_phrase_save))
                        }
                    }
                }
            }
        }
    }
}

private enum class ScheduleTimeField { START, END }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePreferenceCard(
    settings: PauseSettings,
    is24Hour: Boolean,
    activeProfileName: String?,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    onDayToggle: (DayOfWeek) -> Unit,
) {
    var editing by remember { mutableStateOf<ScheduleTimeField?>(null) }

    PreferenceCard(
        title = stringResource(R.string.settings_schedule_title),
        body = stringResource(R.string.settings_schedule_body),
        supportingCaption = activeProfileName?.let {
            stringResource(R.string.settings_profiles_applies_to_active, it)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_schedule_enabled),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.scheduleEnabled,
                onCheckedChange = onEnabledChange,
            )
        }

        if (settings.scheduleEnabled) {
            Spacer(Modifier.height(PauseDimens.space16))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PauseDimens.space8),
                verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
            ) {
                OutlinedButton(onClick = { editing = ScheduleTimeField.START }) {
                    Text(
                        text = stringResource(
                            R.string.settings_schedule_start,
                            formatScheduleMinutes(settings.scheduleStartMinutes, is24Hour),
                        ),
                    )
                }
                OutlinedButton(onClick = { editing = ScheduleTimeField.END }) {
                    Text(
                        text = stringResource(
                            R.string.settings_schedule_end,
                            formatScheduleMinutes(settings.scheduleEndMinutes, is24Hour),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(PauseDimens.space16))
            Text(
                text = stringResource(R.string.settings_schedule_days),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PauseDimens.space8))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PauseDimens.space8),
                verticalArrangement = Arrangement.spacedBy(PauseDimens.space8),
            ) {
                DayOfWeek.entries.forEach { day ->
                    val selected = day in settings.scheduleDays
                    FilterChip(
                        selected = selected,
                        onClick = { onDayToggle(day) },
                        enabled = !(selected && settings.scheduleDays.size == 1),
                        label = { Text(stringResource(day.labelRes())) },
                    )
                }
            }
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(R.string.settings_schedule_overnight),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { field ->
        key(field, is24Hour) {
            val isStart = field == ScheduleTimeField.START
            ScheduleTimeDialog(
                title = stringResource(
                    if (isStart) R.string.settings_schedule_start_title
                    else R.string.settings_schedule_end_title,
                ),
                initialMinutes = if (isStart) {
                    settings.scheduleStartMinutes
                } else {
                    settings.scheduleEndMinutes
                },
                is24Hour = is24Hour,
                onDismiss = { editing = null },
                onConfirm = { minutes ->
                    if (isStart) onStartChange(minutes) else onEndChange(minutes)
                    editing = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimeDialog(
    title: String,
    initialMinutes: Int,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = is24Hour,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.settings_schedule_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_schedule_cancel))
            }
        },
    )
}

private fun formatScheduleMinutes(
    minutes: Int,
    is24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val normalized = minutes.coerceIn(0, PauseSettings.MINUTES_PER_DAY - 1)
    val time = LocalTime.of(normalized / 60, normalized % 60)
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, locale))
}

private fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.day_monday
    DayOfWeek.TUESDAY -> R.string.day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.day_wednesday
    DayOfWeek.THURSDAY -> R.string.day_thursday
    DayOfWeek.FRIDAY -> R.string.day_friday
    DayOfWeek.SATURDAY -> R.string.day_saturday
    DayOfWeek.SUNDAY -> R.string.day_sunday
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
