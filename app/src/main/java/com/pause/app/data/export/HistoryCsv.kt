package com.pause.app.data.export

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.HistoryArchiveEntry
import com.pause.app.domain.model.InterventionOutcome
import java.util.Locale

/**
 * Local-only CSV format for history.
 *
 * The sequence is stored in one field with `|` separators so a comma remains a
 * normal CSV delimiter. Intentions and labels are quoted when needed, including
 * embedded newlines.
 */
object HistoryCsv {

    const val MIME_TYPE = "text/csv"
    const val FILE_NAME = "pause-history.csv"

    private val HEADER = listOf(
        "timestamp_millis",
        "package_name",
        "app_label",
        "outcome",
        "friction_modes",
        "intention",
        "session_limit_minutes",
    )

    fun encode(entries: List<HistoryArchiveEntry>): String = buildString {
        append(HEADER.joinToString(","))
        append('\n')
        entries.forEach { entry ->
            append(
                listOf(
                    entry.timestampMillis.toString(),
                    entry.packageName,
                    entry.appLabel,
                    entry.outcome.name,
                    entry.frictionModes.joinToString("|") { it.name },
                    entry.intention.orEmpty(),
                    entry.sessionLimitMinutes?.toString().orEmpty(),
                ).joinToString(",") { escape(it) },
            )
            append('\n')
        }
    }

    /**
     * Parses the archive and skips individual malformed rows. A malformed header
     * is rejected because silently importing the wrong columns would be worse
     * than telling the user the file is not a Pause archive.
     */
    fun decode(text: String): List<HistoryArchiveEntry> {
        val rows = parseRows(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "The archive is empty" }
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        require(header == HEADER) { "Not a Pause history archive" }

        return rows.drop(1).mapNotNull { row ->
            if (row.all { it.isBlank() }) return@mapNotNull null
            val values = HEADER.indices.associateWith { index -> row.getOrNull(index).orEmpty() }

            val timestamp = values.getValue(0).toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return@mapNotNull null
            val packageName = values.getValue(1).trim()
            if (packageName.isEmpty()) return@mapNotNull null
            val outcome = runCatching {
                InterventionOutcome.valueOf(values.getValue(3).trim())
            }.getOrNull() ?: return@mapNotNull null

            val modes = values.getValue(4)
                .split('|')
                .mapNotNull { runCatching { FrictionMode.valueOf(it.trim()) }.getOrNull() }
                .distinct()
                .ifEmpty { listOf(FrictionMode.BREATHING) }
            val appLabel = values.getValue(2).ifBlank { packageName }
            val intention = values.getValue(5).ifBlank { null }
            val limit = values.getValue(6).toIntOrNull()?.takeIf { it > 0 }

            HistoryArchiveEntry(
                timestampMillis = timestamp,
                packageName = packageName,
                appLabel = appLabel,
                outcome = outcome,
                frictionModes = modes,
                intention = intention,
                sessionLimitMinutes = limit,
            )
        }
    }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            row += field.toString()
            field.clear()
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotEmpty() }) rows += row.toList()
            row.clear()
        }

        while (index < text.length) {
            val char = text[index]
            if (inQuotes) {
                when {
                    char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    char == '"' -> inQuotes = false
                    else -> field.append(char)
                }
            } else {
                when (char) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\n' -> endRow()
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        endRow()
                    }
                    else -> field.append(char)
                }
            }
            index++
        }

        require(!inQuotes) { "Unterminated quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
