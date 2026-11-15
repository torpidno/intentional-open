package com.pause.app.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.app.R
import com.pause.app.data.export.HistoryCsv
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.ui.components.AppGlyph
import com.pause.app.ui.components.ChoiceChips
import com.pause.app.ui.components.PauseCard
import com.pause.app.ui.components.PrivacyFooter
import com.pause.app.ui.components.summary
import com.pause.app.ui.theme.LocalPauseSemanticColors
import com.pause.app.ui.theme.PauseDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(HistoryCsv.MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = runCatching { viewModel.exportCsv() }.getOrElse {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.history_export_failed),
                    )
                    return@launch
                }
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("No output stream")
                    }.isSuccess
                }
                snackbarHostState.showSnackbar(
                    context.getString(
                        if (written) R.string.history_export_success
                        else R.string.history_export_failed,
                    ),
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.reader(Charsets.UTF_8).readText()
                        }
                    }.getOrNull()
                }
                if (csv == null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.history_import_failed),
                    )
                    return@launch
                }
                val imported = runCatching {
                    withContext(Dispatchers.IO) { viewModel.importCsv(csv) }
                }.getOrElse {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.history_import_failed),
                    )
                    return@launch
                }
                snackbarHostState.showSnackbar(
                    if (imported == 0) {
                        context.getString(R.string.history_import_empty)
                    } else {
                        context.getString(R.string.history_import_success, imported)
                    },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    TextButton(onClick = { exportLauncher.launch(HistoryCsv.FILE_NAME) }) {
                        Text(stringResource(R.string.history_export_action))
                    }
                    TextButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("text/csv", "text/comma-separated-values", "text/plain"),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.history_import_action))
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
                    ChoiceChips(
                        options = HistoryFilter.entries,
                        selected = state.filter,
                        label = { filter -> stringResource(filter.labelRes()) },
                        onSelect = viewModel::setFilter,
                    )
                }

                if (state.entries.isEmpty()) {
                    item {
                        HistoryEmptyCard(
                            filtered = state.totalCount > 0,
                        )
                    }
                } else {
                    items(items = state.entries, key = { it.id }) { entry ->
                        HistoryCard(entry = entry)
                    }
                }

                if (state.totalCount > 0) {
                    item {
                        TextButton(
                            onClick = { showClearConfirmation = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PauseDimens.touchTarget),
                        ) {
                            Text(
                                text = stringResource(R.string.history_clear_action),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    PrivacyFooter(modifier = Modifier.padding(top = PauseDimens.space8))
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { viewModel.clearHistory() }
                            }.onSuccess {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.history_clear_success),
                                )
                            }.onFailure {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.history_clear_failed),
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.history_clear_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.history_clear_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntryUi) {
    PauseCard(contentPadding = PaddingValues(PauseDimens.space16)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(
                icon = null,
                label = entry.appLabel,
                modifier = Modifier.size(36.dp),
                cornerRadius = 10.dp,
            )
            Spacer(Modifier.width(PauseDimens.space16))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.history_date_time,
                        entry.dateLabel,
                        entry.timeLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(PauseDimens.space8))
            OutcomePill(outcome = entry.outcome)
        }

        Spacer(Modifier.height(PauseDimens.space16))
        Text(
            text = stringResource(
                R.string.history_friction_label,
                entry.frictionModes.summary(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        entry.intention?.takeIf { it.isNotBlank() }?.let { intention ->
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(R.string.history_intention_label, intention),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        entry.sessionLimitMinutes?.let { minutes ->
            Spacer(Modifier.height(PauseDimens.space4))
            Text(
                text = stringResource(R.string.history_limit_label, minutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OutcomePill(outcome: InterventionOutcome) {
    val semantic = LocalPauseSemanticColors.current
    val container = when (outcome) {
        InterventionOutcome.WALKED_AWAY -> semantic.successContainer
        InterventionOutcome.PROCEEDED -> MaterialTheme.colorScheme.secondaryContainer
        InterventionOutcome.PENDING -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (outcome) {
        InterventionOutcome.WALKED_AWAY -> semantic.onSuccessContainer
        InterventionOutcome.PROCEEDED -> MaterialTheme.colorScheme.onSecondaryContainer
        InterventionOutcome.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(shape = CircleShape, color = container) {
        Text(
            text = stringResource(outcome.labelRes()),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(
                horizontal = PauseDimens.space8,
                vertical = PauseDimens.space4,
            ),
        )
    }
}

@Composable
private fun HistoryEmptyCard(filtered: Boolean) {
    PauseCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (filtered) R.string.history_filter_empty_title
                    else R.string.history_empty_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(PauseDimens.space8))
            Text(
                text = stringResource(
                    if (filtered) R.string.history_filter_empty_body
                    else R.string.history_empty_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun HistoryFilter.labelRes(): Int = when (this) {
    HistoryFilter.ALL -> R.string.history_filter_all
    HistoryFilter.WALKED_AWAY -> R.string.history_filter_walked_away
    HistoryFilter.PROCEEDED -> R.string.history_filter_continued
}

private fun InterventionOutcome.labelRes(): Int = when (this) {
    InterventionOutcome.WALKED_AWAY -> R.string.history_outcome_walked_away
    InterventionOutcome.PROCEEDED -> R.string.history_outcome_continued
    InterventionOutcome.PENDING -> R.string.history_outcome_unfinished
}

