package com.pause.app.data.repository

import com.pause.app.data.local.DailyUsageDao
import com.pause.app.data.local.DailyUsageEntity
import com.pause.app.di.IoDispatcher
import com.pause.app.domain.model.DailyBudgetStatus
import com.pause.app.service.ForegroundUsageReader
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Tracks per-app daily usage against configured budgets.
 *
 * Combines an in-memory per-day cache for synchronous checks on the
 * accessibility hot path with local Room snapshots in `daily_usage` so usage
 * remains observable in the UI and survives process restarts.
 */
@Singleton
class DailyBudgetRepository @Inject constructor(
    private val dao: DailyUsageDao,
    private val usageReader: ForegroundUsageReader,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Maps `packageName -> (localDateIso, usedMillis)` for fast hot-path reads. */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Synchronous budget check safe for the accessibility event hot path.
     *
     * Queries [ForegroundUsageReader] for today's usage; when Usage Access is
     * unavailable (`null`), falls back to the cached value for the current
     * local day (or `0L` if none exists or the date rolled over at midnight).
     */
    fun checkBudgetSync(packageName: String, limitMinutes: Int): DailyBudgetStatus {
        return readBudgetInternal(packageName, limitMinutes).status
    }

    /**
     * Refreshes usage for [packages] today, persists snapshots to Room, updates
     * the in-memory cache, and prunes rows older than 30 days.
     */
    suspend fun syncUsageSnapshot(packages: Set<String>): Map<String, Long> =
        withContext(ioDispatcher) {
            val zone = clock.zone
            val today = LocalDate.now(clock)
            val todayKey = today.toString()
            val startOfDayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val nowMillis = clock.millis()

            val fresh = if (packages.isEmpty()) {
                emptyMap()
            } else {
                usageReader.readForegroundMillis(
                    packages = packages,
                    startOfDayMillis = startOfDayMillis,
                    nowMillis = nowMillis,
                )
            }

            dao.deleteOlderThan(today.minusDays(RETENTION_DAYS).toString())

            if (fresh != null) {
                val snapshot = packages.associateWith { pkg ->
                    (fresh[pkg] ?: 0L).coerceAtLeast(0L)
                }
                if (snapshot.isNotEmpty()) {
                    val entries = snapshot.map { (pkg, used) ->
                        cache[pkg] = todayKey to used
                        DailyUsageEntity(
                            localDate = todayKey,
                            packageName = pkg,
                            usedMillis = used,
                            updatedAtMillis = nowMillis,
                        )
                    }
                    dao.upsertAll(entries)
                }
                snapshot
            } else {
                buildMap {
                    for (pkg in packages) {
                        val cached = cache[pkg]?.takeIf { it.first == todayKey }?.second
                        val persisted = cached ?: dao.getUsedMillis(todayKey, pkg)
                        if (persisted != null) {
                            cache[pkg] = todayKey to persisted
                            put(pkg, persisted)
                        }
                    }
                }
            }
        }

    /**
     * Checks today's budget on [ioDispatcher] and persists fresh snapshots to
     * Room `daily_usage`.
     */
    suspend fun checkAndPersistBudget(
        packageName: String,
        limitMinutes: Int,
    ): DailyBudgetStatus = withContext(ioDispatcher) {
        val result = readBudgetInternal(packageName, limitMinutes)
        if (result.fromReader) {
            dao.upsert(
                DailyUsageEntity(
                    localDate = result.localDate,
                    packageName = packageName,
                    usedMillis = result.status.usedMillis,
                    updatedAtMillis = clock.millis(),
                ),
            )
            result.status
        } else {
            val cached = cache[packageName]?.takeIf { it.first == result.localDate }?.second
            val persisted = cached ?: dao.getUsedMillis(result.localDate, packageName)
            if (persisted != null) {
                cache[packageName] = result.localDate to persisted
                result.status.copy(usedMillis = persisted)
            } else {
                result.status
            }
        }
    }

    /** Observes persisted usage snapshots for [date] (`packageName -> usedMillis`). */
    fun observeTodayUsage(date: LocalDate = LocalDate.now(clock)): Flow<Map<String, Long>> =
        dao.observeForDate(date.toString()).map { entries ->
            entries.associate { it.packageName to it.usedMillis }
        }

    private fun readBudgetInternal(
        packageName: String,
        limitMinutes: Int,
    ): BudgetReadResult {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val todayKey = today.toString()
        val startOfDayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMillis = clock.millis()

        val fresh = usageReader.readForegroundMillis(
            packages = setOf(packageName),
            startOfDayMillis = startOfDayMillis,
            nowMillis = nowMillis,
        )
        return if (fresh != null) {
            val used = (fresh[packageName] ?: 0L).coerceAtLeast(0L)
            cache[packageName] = todayKey to used
            BudgetReadResult(
                status = DailyBudgetStatus(
                    packageName = packageName,
                    usedMillis = used,
                    limitMinutes = limitMinutes,
                ),
                fromReader = true,
                localDate = todayKey,
            )
        } else {
            val cached = cache[packageName]?.takeIf { it.first == todayKey }?.second ?: 0L
            BudgetReadResult(
                status = DailyBudgetStatus(
                    packageName = packageName,
                    usedMillis = cached,
                    limitMinutes = limitMinutes,
                ),
                fromReader = false,
                localDate = todayKey,
            )
        }
    }

    private data class BudgetReadResult(
        val status: DailyBudgetStatus,
        val fromReader: Boolean,
        val localDate: String,
    )

    private companion object {
        const val RETENTION_DAYS = 30L
    }
}
