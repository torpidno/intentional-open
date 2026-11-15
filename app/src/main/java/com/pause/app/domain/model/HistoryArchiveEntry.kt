package com.pause.app.domain.model

/** One history row in the local CSV archive; IDs are assigned on import. */
data class HistoryArchiveEntry(
    val timestampMillis: Long,
    val packageName: String,
    val appLabel: String,
    val outcome: InterventionOutcome,
    val frictionModes: List<FrictionMode>,
    val intention: String?,
    val sessionLimitMinutes: Int?,
)
