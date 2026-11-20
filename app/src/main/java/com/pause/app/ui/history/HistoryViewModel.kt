package com.pause.app.ui.history

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pause.app.data.export.HistoryCsv
import com.pause.app.data.repository.HistoryRetentionManager
import com.pause.app.data.repository.InstalledAppsRepository
import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import com.pause.app.domain.model.InterventionRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter {
    ALL,
    WALKED_AWAY,
    PROCEEDED,
}

data class HistoryEntryUi(
    val id: Long,
    val appLabel: String,
    val dateLabel: String,
    val timeLabel: String,
    val outcome: InterventionOutcome,
    val frictionModes: List<FrictionMode>,
    val intention: String?,
    val sessionLimitMinutes: Int?,
    val appIcon: ImageBitmap? = null,
)

data class HistoryUiState(
    val entries: List<HistoryEntryUi> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val totalCount: Int = 0,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val interceptionRepository: InterceptionRepository,
    private val historyRetentionManager: HistoryRetentionManager,
    private val installedAppsRepository: InstalledAppsRepository,
    clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    val uiState: StateFlow<HistoryUiState> = combine(
        interceptionRepository.observeHistory(),
        filter,
        installedAppsRepository.apps,
    ) { records, selectedFilter, installedApps ->
        val iconsByPackage = installedApps.associate { it.packageName to it.icon }
        val visible = records.filter { record ->
            when (selectedFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.WALKED_AWAY -> record.outcome == InterventionOutcome.WALKED_AWAY
                HistoryFilter.PROCEEDED -> record.outcome == InterventionOutcome.PROCEEDED
            }
        }
        HistoryUiState(
            entries = visible.map { record ->
                record.toUi(
                    dateFormatter = dateFormatter,
                    timeFormatter = timeFormatter,
                    clock = clock,
                    appIcon = iconsByPackage[record.packageName],
                )
            },
            filter = selectedFilter,
            totalCount = records.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(),
    )

    init {
        // Pruning here also covers users who never leave the app open at boot.
        viewModelScope.launch {
            runCatching { historyRetentionManager.pruneNow() }
        }
        viewModelScope.launch {
            if (installedAppsRepository.apps.value.isEmpty()) {
                runCatching { installedAppsRepository.refresh() }
            }
        }
    }

    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    suspend fun exportCsv(): String = HistoryCsv.encode(interceptionRepository.exportHistory())

    suspend fun importCsv(csv: String): Int =
        interceptionRepository.importHistory(HistoryCsv.decode(csv))

    suspend fun clearHistory() {
        interceptionRepository.clearHistory()
    }
}

private fun InterventionRecord.toUi(
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    clock: Clock,
    appIcon: ImageBitmap?,
): HistoryEntryUi {
    val timestamp = Instant.ofEpochMilli(timestampMillis)
        .atZone(clock.zone)
    return HistoryEntryUi(
        id = id,
        appLabel = appLabel,
        dateLabel = timestamp.format(dateFormatter),
        timeLabel = timestamp.format(timeFormatter),
        outcome = outcome,
        frictionModes = frictionModes,
        intention = intention,
        sessionLimitMinutes = sessionLimitMinutes,
        appIcon = appIcon,
    )
}
