package com.pause.app.domain.model

/**
 * Snapshot of a monitored app's foreground usage today compared against its
 * configured daily limit.
 */
data class DailyBudgetStatus(
    val packageName: String,
    val usedMillis: Long,
    val limitMinutes: Int,
) {
    val limitMillis: Long get() = limitMinutes * 60_000L
    val remainingMillis: Long get() = (limitMillis - usedMillis).coerceAtLeast(0L)
    val usedMinutes: Int get() = (usedMillis / 60_000L).toInt()
    val isExceeded: Boolean get() = limitMinutes > 0 && usedMillis >= limitMillis
}
