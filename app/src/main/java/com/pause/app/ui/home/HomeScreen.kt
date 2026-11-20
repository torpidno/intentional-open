package com.pause.app.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.app.R
import com.pause.app.data.preferences.PauseSettings
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.RangeStats
import com.pause.app.domain.model.StatsRange
import com.pause.app.domain.model.TimeOfDayBucket
import com.pause.app.domain.model.moveFrictionMode
import com.pause.app.ui.components.AppGlyph
import com.pause.app.ui.components.ChoiceChips
import com.pause.app.ui.components.FrictionSequenceEditor
import com.pause.app.ui.components.PauseCard
import com.pause.app.ui.components.PrivacyFooter
import com.pause.app.ui.components.SectionHeader
import com.pause.app.ui.components.StatTile
import com.pause.app.ui.components.StrictModeChallengeDialog
import com.pause.app.ui.components.pauseClickable
import com.pause.app.ui.components.summary
import com.pause.app.ui.theme.LocalPauseSemanticColors
import com.pause.app.ui.theme.PauseDimens
import com.pause.app.util.PermissionSnapshot
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onAddApps: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingStrictAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun guardIfStrict(actionDescription: String, action: () -> Unit) {
        if (state.strictModeEnabled) {
            pendingStrictAction = actionDescription to action
        } else {
            action()
        }
    }

    // Permission state can change while Settings is open, and Today's stats
    // should roll over if the app stayed alive across midnight.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.home_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
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
                    StatusCard(
                        isEnabled = state.isEnabled,
                        activeAppCount = state.monitoredApps.count { !it.isPaused },
                        onEnabledChange = { enabled ->
                            if (enabled) {
                                viewModel.setEnabled(true)
                            } else {
                                guardIfStrict(
                                    context.getString(R.string.home_strict_action_disable_pause),
                                ) {
                                    viewModel.setEnabled(false)
                                }
                            }
                        },
                    )
                }

                if (state.profiles.isNotEmpty()) {
                    item {
                        ProfileSwitcherCard(
                            profiles = state.profiles,
                            activeProfileId = state.activeProfileId,
                            effectiveProfileName = state.effectiveProfileName,
                            isScheduledProfileActive = state.isScheduledProfileActive,
                            onSelectProfile = viewModel::selectProfile,
                        )
                    }
                }

                if (!state.permissions.allGranted) {
                    item {
                        PermissionAlertCard(
                            permissions = state.permissions,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                item {
                    StatsCard(
                        statsRange = state.statsRange,
                        rangeStats = state.rangeStats,
                        onSelectRange = viewModel::setStatsRange,
                        onOpenHistory = onOpenHistory,
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.home_monitored_title),
                        actionLabel = stringResource(R.string.home_add_apps),
                        onAction = onAddApps,
                    )
                }

                if (state.monitoredApps.isEmpty()) {
                    item { EmptyAppsCard(onAddApps = onAddApps) }
                } else {
                    items(items = state.monitoredApps, key = { it.packageName }) { app ->
                        MonitoredAppCard(
                            app = app,
                            defaultModes = state.defaultModes,
                            usageAccessGranted = state.permissions.usageAccessGranted,
                            onPausedChange = { paused ->
                                if (paused) {
                                    guardIfStrict(
                                        context.getString(
                                            R.string.home_strict_action_pause_app,
                                            app.label,
                                        ),
                                    ) {
                                        viewModel.setAppPaused(app.packageName, true)
                                    }
                                } else {
                                    viewModel.setAppPaused(app.packageName, false)
                                }
                            },
                            onModeChange = { modes ->
                                viewModel.setAppModeOverride(app.packageName, modes)
                            },
                            onSnooze = { minutes ->
                                guardIfStrict(
                                    context.getString(
                                        R.string.home_strict_action_snooze_app,
                                        app.label,
                                        minutes,
                                    ),
                                ) {
                                    viewModel.snoozeApp(app.packageName, minutes)
                                }
                            },
                            onClearSnooze = {
                                viewModel.clearAppSnooze(app.packageName)
                            },
                            onDailyLimitChange = { newLimit ->
                                val existingLimit = app.dailyLimitMinutes
                                val isWeakening = existingLimit != null &&
                                    (newLimit == null || newLimit > existingLimit)
                                if (isWeakening) {
                                    val description = if (newLimit == null) {
                                        context.getString(
                                            R.string.home_strict_action_remove_daily_limit,
                                            app.label,
                                        )
                                    } else {
                                        context.getString(
                                            R.string.home_strict_action_raise_daily_limit,
                                            app.label,
                                            newLimit,
                                        )
                                    }
                                    guardIfStrict(description) {
                                        viewModel.setDailyLimitMinutes(app.packageName, newLimit)
                                    }
                                } else {
                                    viewModel.setDailyLimitMinutes(app.packageName, newLimit)
                                }
                            },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }

                item {
                    PrivacyFooter(modifier = Modifier.padding(top = PauseDimens.space8))
                }
            }
        }
    }

    pendingStrictAction?.let { (description, action) ->
        StrictModeChallengeDialog(
            actionDescription = description,
            challenge = state.strictModeChallenge,
            delaySeconds = state.strictModeDelaySeconds,
            phrase = state.strictModePhrase,
            onDismiss = { pendingStrictAction = null },
            onConfirmed = {
                pendingStrictAction = null
                action()
            },
        )
    }
}

@Composable
private fun StatusCard(
    isEnabled: Boolean,
    activeAppCount: Int,
    onEnabledChange: (Boolean) -> Unit,
) {
    val switchDescription = stringResource(R.string.cd_pause_switch)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(PauseDimens.space24),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(PauseDimens.space48),
                shape = CircleShape,
                color = if (isEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Filled.Check else Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(PauseDimens.space16))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (isEnabled) R.string.home_status_active_title
                        else R.string.home_status_paused_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(PauseDimens.space4))
                Text(
                    text = if (isEnabled) {
                        pluralStringResource(
                            R.plurals.home_status_active_body,
                            activeAppCount,
                            activeAppCount,
                        )
                    } else {
                        stringResource(R.string.home_status_paused_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(PauseDimens.space16))
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
        }
    }
}

@Composable
private fun ProfileSwitcherCard(
    profiles: List<PauseProfile>,
    activeProfileId: String?,
    effectiveProfileName: String?,
    isScheduledProfileActive: Boolean,
    onSelectProfile: (String?) -> Unit,
) {
    val semantic = LocalPauseSemanticColors.current
    val customLabel = stringResource(R.string.home_profile_custom)
    val badgeText = when {
        isScheduledProfileActive && effectiveProfileName != null ->
            stringResource(R.string.home_profile_auto_badge, effectiveProfileName)
        effectiveProfileName != null -> effectiveProfileName
        else -> customLabel
    }
    val badgeContainer = when {
        isScheduledProfileActive -> semantic.infoContainer
        effectiveProfileName != null -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val badgeContent = when {
        isScheduledProfileActive -> semantic.onInfoContainer
        effectiveProfileName != null -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val chipOptions: List<String?> = remember(profiles, activeProfileId) {
        if (activeProfileId == null) {
            listOf<String?>(null) + profiles.map { it.id }
        } else {
            profiles.map { it.id }
        }
    }
    val namesById = remember(profiles) { profiles.associate { it.id to it.name } }

    PauseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_profile_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = CircleShape,
                color = badgeContainer,
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = PauseDimens.space16,
                        vertical = PauseDimens.space4,
                    ),
                )
            }
        }
        if (isScheduledProfileActive && effectiveProfileName != null) {
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(R.string.home_profile_auto_caption, effectiveProfileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(PauseDimens.space8))
        ChoiceChips(
            options = chipOptions,
            selected = activeProfileId,
            label = { id ->
                if (id == null) customLabel else namesById[id].orEmpty()
            },
            onSelect = { id ->
                if (id != activeProfileId) {
                    onSelectProfile(id)
                }
            },
        )
    }
}

@Composable
private fun PermissionAlertCard(
    permissions: PermissionSnapshot,
    onOpenSettings: () -> Unit,
) {
    val semantic = LocalPauseSemanticColors.current
    val shape = MaterialTheme.shapes.large

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pauseClickable(
                shape = shape,
                minHeight = 72.dp,
                onClick = onOpenSettings,
            ),
    ) {
        Row(
            modifier = Modifier.padding(PauseDimens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(PauseDimens.space48),
                shape = CircleShape,
                color = semantic.warningContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = semantic.onWarningContainer,
                    )
                }
            }
            Spacer(Modifier.width(PauseDimens.space16))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(PauseDimens.space4))
                Text(
                    text = stringResource(
                        R.string.home_permission_body,
                        permissions.grantedCount,
                        PermissionSnapshot.TOTAL_COUNT,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(PauseDimens.space8))
            Text(
                text = stringResource(R.string.home_permission_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatsCard(
    statsRange: StatsRange,
    rangeStats: RangeStats,
    onSelectRange: (StatsRange) -> Unit,
    onOpenHistory: () -> Unit,
) {
    PauseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_stats_overview_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(
                    text = stringResource(R.string.home_history_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(PauseDimens.space8))
        ChoiceChips(
            options = StatsRange.entries,
            selected = statsRange,
            label = { range -> stringResource(range.labelRes()) },
            onSelect = onSelectRange,
        )
        Spacer(Modifier.height(PauseDimens.space16))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatTile(
                value = rangeStats.attempts.toString(),
                label = stringResource(R.string.home_stat_attempts),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(PauseDimens.space48)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
            StatTile(
                value = rangeStats.walkedAway.toString(),
                label = stringResource(R.string.home_stat_walked_away),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = PauseDimens.space24),
            )
        }
        if (rangeStats.attempts > 0) {
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(
                    R.string.home_stats_walked_away_rate,
                    rangeStats.walkedAwayPercent,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(PauseDimens.space16))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(PauseDimens.space16))

        if (statsRange == StatsRange.TODAY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_most_triggered),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(PauseDimens.space4))
                    Text(
                        text = rangeStats.mostTriggeredLabel
                            ?: stringResource(R.string.home_most_triggered_none),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rangeStats.mostTriggeredCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_most_triggered_count,
                            rangeStats.mostTriggeredCount,
                            rangeStats.mostTriggeredCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.home_stats_top_apps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PauseDimens.space8))
            if (rangeStats.topApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_most_triggered_none),
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PauseDimens.space8)) {
                    rangeStats.topApps.take(3).forEach { appStat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = appStat.appLabel,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(PauseDimens.space8))
                            val pausesText = pluralStringResource(
                                R.plurals.home_stats_app_pauses,
                                appStat.attempts,
                                appStat.attempts,
                            )
                            Text(
                                text = stringResource(
                                    R.string.home_stats_app_trend,
                                    pausesText,
                                    appStat.walkedAway,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (rangeStats.attempts > 0) {
            Spacer(Modifier.height(PauseDimens.space16))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(PauseDimens.space16))
            TimeOfDayInsightsSection(rangeStats = rangeStats)
        }
    }
}

@Composable
private fun TimeOfDayInsightsSection(rangeStats: RangeStats) {
    val maxBucketAttempts = remember(rangeStats.timeOfDay) {
        (rangeStats.timeOfDay.maxOfOrNull { it.attempts } ?: 0).coerceAtLeast(1)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_stats_time_of_day),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        rangeStats.peakBucket?.let { peak ->
            Text(
                text = stringResource(
                    R.string.home_stats_peak_bucket,
                    stringResource(peak.labelRes()),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(PauseDimens.space8))
    Column(verticalArrangement = Arrangement.spacedBy(PauseDimens.space8)) {
        rangeStats.timeOfDay.forEach { entry ->
            val isPeak = entry.bucket == rangeStats.peakBucket && entry.attempts > 0
            val fraction = (entry.attempts.toFloat() / maxBucketAttempts).coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(entry.bucket.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isPeak) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isPeak) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(80.dp),
                )
                Spacer(Modifier.width(PauseDimens.space8))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (entry.attempts > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    if (isPeak) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(PauseDimens.space8))
                Text(
                    text = entry.attempts.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp),
                )
            }
        }
    }
}

private fun StatsRange.labelRes(): Int = when (this) {
    StatsRange.TODAY -> R.string.home_stats_range_today
    StatsRange.SEVEN_DAYS -> R.string.home_stats_range_7d
    StatsRange.THIRTY_DAYS -> R.string.home_stats_range_30d
}

private fun TimeOfDayBucket.labelRes(): Int = when (this) {
    TimeOfDayBucket.MORNING -> R.string.home_bucket_morning
    TimeOfDayBucket.AFTERNOON -> R.string.home_bucket_afternoon
    TimeOfDayBucket.EVENING -> R.string.home_bucket_evening
    TimeOfDayBucket.NIGHT -> R.string.home_bucket_night
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitoredAppCard(
    app: MonitoredAppUi,
    defaultModes: List<FrictionMode>,
    usageAccessGranted: Boolean,
    onPausedChange: (Boolean) -> Unit,
    onModeChange: (List<FrictionMode>?) -> Unit,
    onSnooze: (Int) -> Unit,
    onClearSnooze: () -> Unit,
    onDailyLimitChange: (Int?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var snoozeMenuOpen by remember { mutableStateOf(false) }
    var dailyLimitDialogOpen by remember { mutableStateOf(false) }
    val defaultModeLabel = defaultModes.summary()
    val overrideModes = app.modeOverride
    val switchDescription = stringResource(R.string.cd_app_pause_switch, app.label)
    val context = LocalContext.current
    val semantic = LocalPauseSemanticColors.current
    val snoozedUntil = app.snoozedUntilMillis
    val snoozeTime = remember(snoozedUntil, context) {
        snoozedUntil?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
    }
    val dailyLimit = app.dailyLimitMinutes

    // Snoozes expire without an alarm. Clear the stored deadline when the
    // countdown ends so the card returns to its normal state and the menu is
    // correct if the process was asleep.
    LaunchedEffect(snoozedUntil) {
        if (snoozedUntil != null) {
            val remaining = snoozedUntil - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            onClearSnooze()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PauseDimens.space16,
                vertical = PauseDimens.space8,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppGlyph(
                icon = app.icon,
                label = app.label,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(PauseDimens.space16))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(PauseDimens.space4))
                Box(
                    modifier = Modifier.pauseClickable(
                        shape = CircleShape,
                        minHeight = PauseDimens.touchTarget,
                        onClick = { dialogOpen = true },
                    ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = PauseDimens.space16,
                                vertical = PauseDimens.space8,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (overrideModes != null) {
                                    overrideModes.summary()
                                } else {
                                    stringResource(R.string.app_mode_default, defaultModeLabel)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PauseDimens.space4))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PauseDimens.space8),
                    verticalArrangement = Arrangement.spacedBy(PauseDimens.space4),
                ) {
                    Box {
                        TextButton(
                            onClick = { snoozeMenuOpen = true },
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text(
                                text = if (snoozeTime != null) {
                                    stringResource(R.string.home_snoozed_until, snoozeTime)
                                } else {
                                    stringResource(R.string.home_snooze)
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        DropdownMenu(
                            expanded = snoozeMenuOpen,
                            onDismissRequest = { snoozeMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_snooze_15)) },
                                onClick = {
                                    snoozeMenuOpen = false
                                    onSnooze(15)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_snooze_60)) },
                                onClick = {
                                    snoozeMenuOpen = false
                                    onSnooze(60)
                                },
                            )
                            if (snoozedUntil != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_snooze_resume)) },
                                    onClick = {
                                        snoozeMenuOpen = false
                                        onClearSnooze()
                                    },
                                )
                            }
                        }
                    }

                    if (dailyLimit != null && app.usedTodayMinutes >= dailyLimit) {
                        Box(
                            modifier = Modifier.pauseClickable(
                                shape = CircleShape,
                                minHeight = 40.dp,
                                onClick = { dailyLimitDialogOpen = true },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = semantic.warningContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = PauseDimens.space16,
                                        vertical = PauseDimens.space8,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = semantic.onWarningContainer,
                                    )
                                    Spacer(Modifier.width(PauseDimens.space4))
                                    Text(
                                        text = stringResource(
                                            R.string.home_daily_limit_reached,
                                            app.usedTodayMinutes,
                                            dailyLimit,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = semantic.onWarningContainer,
                                    )
                                }
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { dailyLimitDialogOpen = true },
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text(
                                text = if (dailyLimit != null) {
                                    stringResource(
                                        R.string.home_daily_limit_usage,
                                        app.usedTodayMinutes,
                                        dailyLimit,
                                    )
                                } else {
                                    stringResource(R.string.home_daily_limit_unset)
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(PauseDimens.space8))
            Switch(
                checked = !app.isPaused,
                onCheckedChange = { enabled -> onPausedChange(!enabled) },
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
        }
    }

    if (dialogOpen) {
        AppFrictionDialog(
            appLabel = app.label,
            currentOverride = overrideModes,
            defaultModes = defaultModes,
            onDismiss = { dialogOpen = false },
            onUseDefault = {
                dialogOpen = false
                onModeChange(null)
            },
            onSave = { modes ->
                dialogOpen = false
                onModeChange(if (modes == defaultModes) null else modes)
            },
        )
    }

    if (dailyLimitDialogOpen) {
        DailyLimitDialog(
            appLabel = app.label,
            currentLimitMinutes = dailyLimit,
            usedTodayMinutes = app.usedTodayMinutes,
            usageAccessGranted = usageAccessGranted,
            onOpenSettings = {
                dailyLimitDialogOpen = false
                onOpenSettings()
            },
            onDismiss = { dailyLimitDialogOpen = false },
            onSelect = { minutes ->
                dailyLimitDialogOpen = false
                onDailyLimitChange(minutes)
            },
        )
    }
}

@Composable
internal fun DailyLimitDialog(
    appLabel: String,
    currentLimitMinutes: Int?,
    usedTodayMinutes: Int,
    usageAccessGranted: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    val semantic = LocalPauseSemanticColors.current
    val options: List<Int?> = remember { listOf<Int?>(null) + PauseSettings.DAILY_LIMIT_OPTIONS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.daily_limit_dialog_title, appLabel),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.daily_limit_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (currentLimitMinutes != null || usedTodayMinutes > 0) {
                    Spacer(Modifier.height(PauseDimens.space8))
                    Text(
                        text = stringResource(R.string.daily_limit_used_today, usedTodayMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!usageAccessGranted) {
                    Spacer(Modifier.height(PauseDimens.space16))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = semantic.warningContainer,
                    ) {
                        Column(modifier = Modifier.padding(PauseDimens.space16)) {
                            Text(
                                text = stringResource(R.string.daily_limit_usage_access_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.onWarningContainer,
                            )
                            Spacer(Modifier.height(PauseDimens.space8))
                            TextButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                            ) {
                                Text(
                                    text = stringResource(R.string.daily_limit_open_settings),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = semantic.onWarningContainer,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(PauseDimens.space16))
                ChoiceChips(
                    options = options,
                    selected = currentLimitMinutes,
                    label = { minutes ->
                        if (minutes == null) {
                            stringResource(R.string.daily_limit_option_none)
                        } else {
                            stringResource(R.string.daily_limit_option_minutes, minutes)
                        }
                    },
                    onSelect = onSelect,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
            ) {
                Text(stringResource(R.string.daily_limit_dialog_close))
            }
        },
    )
}

@Composable
internal fun AppFrictionDialog(
    appLabel: String,
    currentOverride: List<FrictionMode>?,
    defaultModes: List<FrictionMode>,
    onDismiss: () -> Unit,
    onUseDefault: () -> Unit,
    onSave: (List<FrictionMode>) -> Unit,
) {
    var selected by remember(appLabel, currentOverride, defaultModes) {
        mutableStateOf(currentOverride ?: defaultModes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appLabel) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        if (currentOverride == null) {
                            R.string.app_friction_using_default
                        } else {
                            R.string.app_friction_custom
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PauseDimens.space8))
                Text(
                    text = stringResource(R.string.app_friction_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PauseDimens.space8))
                FrictionSequenceEditor(
                    selectedModes = selected,
                    onToggle = { mode ->
                        selected = if (mode in selected) {
                            selected - mode
                        } else {
                            (selected + mode).distinct()
                        }
                    },
                    onMove = { mode, offset ->
                        selected = selected.moveFrictionMode(mode, offset)
                    },
                    rowMinHeight = 56.dp,
                )
                Spacer(Modifier.height(PauseDimens.space8))
                Text(
                    text = stringResource(R.string.settings_default_mode_minimum),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (currentOverride != null) {
                    TextButton(
                        onClick = onUseDefault,
                        modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                    ) {
                        Text(
                            text = stringResource(R.string.app_friction_use_default),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(R.string.app_friction_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.app_friction_cancel))
            }
        },
    )
}

@Composable
private fun EmptyAppsCard(onAddApps: () -> Unit) {
    PauseCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(PauseDimens.space16))
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(PauseDimens.space24))
            Button(
                onClick = onAddApps,
                modifier = Modifier.heightIn(min = PauseDimens.touchTarget),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(
                    horizontal = PauseDimens.space24,
                    vertical = PauseDimens.space8,
                ),
            ) {
                Text(stringResource(R.string.home_empty_action))
            }
        }
    }
}
