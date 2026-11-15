package com.pause.app.domain.model

/** One completed or still-pending pause, as shown in local history. */
data class InterventionRecord(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val timestampMillis: Long,
    val outcome: InterventionOutcome,
    val frictionModes: List<FrictionMode>,
    val intention: String?,
    val sessionLimitMinutes: Int?,
)
