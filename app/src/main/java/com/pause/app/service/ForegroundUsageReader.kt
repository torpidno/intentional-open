package com.pause.app.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Pure foreground/background transition extracted from Android UsageEvents. */
enum class UsageTransition {
    RESUMED,
    PAUSED,
}

/** Pure, platform-independent usage event sample for foreground calculation. */
data class UsageEventSample(
    val packageName: String,
    val timestampMillis: Long,
    val transition: UsageTransition,
)

/**
 * Computes per-package foreground duration in milliseconds within
 * `[startOfDayMillis, nowMillis]`.
 *
 * Handles multiple sessions, apps still open at [nowMillis], apps that were
 * already open across local midnight (first event of the day is [UsageTransition.PAUSED]),
 * and duplicate pause/stop events without double-counting from midnight.
 */
fun computeForegroundMillis(
    events: List<UsageEventSample>,
    startOfDayMillis: Long,
    nowMillis: Long,
    targetPackages: Set<String>? = null,
): Map<String, Long> {
    if (nowMillis <= startOfDayMillis) return emptyMap()
    if (targetPackages != null && targetPackages.isEmpty()) return emptyMap()

    val filtered = events
        .asSequence()
        .filter { event ->
            event.packageName.isNotBlank() &&
                event.timestampMillis in startOfDayMillis..nowMillis &&
                (targetPackages == null || event.packageName in targetPackages)
        }
        .sortedBy { it.timestampMillis }
        .toList()

    val openSince = mutableMapOf<String, Long>()
    val seenPackages = mutableSetOf<String>()
    val totals = mutableMapOf<String, Long>()

    for (event in filtered) {
        val pkg = event.packageName
        val isFirstEventForPackage = seenPackages.add(pkg)
        when (event.transition) {
            UsageTransition.RESUMED -> {
                if (openSince[pkg] == null) {
                    openSince[pkg] = event.timestampMillis
                }
            }
            UsageTransition.PAUSED -> {
                val started = openSince.remove(pkg)
                if (started != null) {
                    val delta = (event.timestampMillis - started).coerceAtLeast(0L)
                    totals[pkg] = (totals[pkg] ?: 0L) + delta
                } else if (isFirstEventForPackage) {
                    // The app was already in the foreground across midnight.
                    val delta = (event.timestampMillis - startOfDayMillis).coerceAtLeast(0L)
                    totals[pkg] = (totals[pkg] ?: 0L) + delta
                }
            }
        }
    }

    for ((pkg, started) in openSince) {
        val delta = (nowMillis - started).coerceAtLeast(0L)
        totals[pkg] = (totals[pkg] ?: 0L) + delta
    }

    return totals
}

/**
 * Reads per-package foreground usage from the OS.
 *
 * Returns `null` when Usage Access is not granted or the system query fails,
 * and an empty map when granted with zero usage in the window.
 */
interface ForegroundUsageReader {
    fun readForegroundMillis(
        packages: Set<String>,
        startOfDayMillis: Long,
        nowMillis: Long,
    ): Map<String, Long>?
}

/**
 * Android [UsageStatsManager] implementation of [ForegroundUsageReader].
 *
 * Only inspects package names, timestamps, and activity foreground/background
 * event types — never reads screen content.
 */
@Singleton
class AndroidUsageStatsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ForegroundUsageReader {

    override fun readForegroundMillis(
        packages: Set<String>,
        startOfDayMillis: Long,
        nowMillis: Long,
    ): Map<String, Long>? {
        if (!hasUsageAccess()) return null
        if (packages.isEmpty() || nowMillis <= startOfDayMillis) return emptyMap()

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

        return runCatching {
            val usageEvents = usageStatsManager.queryEvents(startOfDayMillis, nowMillis)
                ?: return null
            val samples = mutableListOf<UsageEventSample>()
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                if (!usageEvents.getNextEvent(event)) break
                val pkg = event.packageName ?: continue
                if (pkg !in packages) continue
                val transition = mapEventType(event.eventType) ?: continue
                samples += UsageEventSample(
                    packageName = pkg,
                    timestampMillis = event.timeStamp,
                    transition = transition,
                )
            }
            computeForegroundMillis(
                events = samples,
                startOfDayMillis = startOfDayMillis,
                nowMillis = nowMillis,
                targetPackages = packages,
            )
        }.getOrNull()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrNull() ?: return false
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun mapEventType(eventType: Int): UsageTransition? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransition.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageTransition.PAUSED
                else -> null
            }
        } else {
            when (eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageTransition.RESUMED
                UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageTransition.PAUSED
                else -> null
            }
        }
}
