package com.pause.app.data.repository

import com.pause.app.data.preferences.SettingsRepository
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes history older than the user's retention window. Called at app start,
 * when the History screen opens, and immediately after the setting changes.
 * A zero-day policy means "keep forever" and performs no delete.
 */
@Singleton
class HistoryRetentionManager @Inject constructor(
    private val repository: InterceptionRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) {

    suspend fun pruneNow() {
        pruneNow(settingsRepository.currentHistoryRetentionDays())
    }

    suspend fun pruneNow(days: Int) {
        val cutoff = cutoffFor(clock.millis(), days) ?: return
        repository.deleteHistoryBefore(cutoff)
    }

    companion object {
        fun cutoffFor(nowMillis: Long, days: Int): Long? {
            if (days <= 0) return null
            return nowMillis - TimeUnit.DAYS.toMillis(days.toLong())
        }
    }
}
