package com.pause.app.data.local

import androidx.room.Entity

/**
 * Local snapshot of daily foreground usage per monitored app.
 *
 * Keyed by ISO-8601 local date (`YYYY-MM-DD`) and package name so daily
 * budgets naturally reset at local midnight without a background timer.
 */
@Entity(
    tableName = "daily_usage",
    primaryKeys = ["localDate", "packageName"],
)
data class DailyUsageEntity(
    val localDate: String,
    val packageName: String,
    val usedMillis: Long,
    val updatedAtMillis: Long,
)
