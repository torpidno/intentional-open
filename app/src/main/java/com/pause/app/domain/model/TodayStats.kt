package com.pause.app.domain.model

/**
 * Aggregated counters for the current calendar day, computed by Room.
 */
data class TodayStats(
    val attempts: Int = 0,
    val walkedAway: Int = 0,
    val mostTriggeredPackage: String? = null,
    val mostTriggeredLabel: String? = null,
    val mostTriggeredCount: Int = 0,
)
