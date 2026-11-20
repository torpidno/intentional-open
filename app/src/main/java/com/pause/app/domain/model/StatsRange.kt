package com.pause.app.domain.model

import java.time.Instant
import java.time.ZoneId
import java.util.EnumMap

/** Selectable rolling window for dashboard trends and insights. */
enum class StatsRange(val days: Int) {
    TODAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
}

/** Local-time dayparts used to highlight when triggers cluster. */
enum class TimeOfDayBucket {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    companion object {
        /** Maps a 24-hour local clock hour (`0..23`) into its daypart bucket. */
        fun fromHour(hour: Int): TimeOfDayBucket = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** Per-app attempt and walk-away totals for a stats window. */
data class AppTrendStat(
    val packageName: String,
    val appLabel: String,
    val attempts: Int,
    val walkedAway: Int,
)

/** Attempt and walk-away totals within one local time-of-day bucket. */
data class TimeOfDayCount(
    val bucket: TimeOfDayBucket,
    val attempts: Int,
    val walkedAway: Int,
)

/** Aggregated dashboard statistics for a [StatsRange] window. */
data class RangeStats(
    val range: StatsRange = StatsRange.TODAY,
    val attempts: Int = 0,
    val walkedAway: Int = 0,
    val topApps: List<AppTrendStat> = emptyList(),
    val timeOfDay: List<TimeOfDayCount> = TimeOfDayBucket.entries.map { TimeOfDayCount(it, 0, 0) },
    val peakBucket: TimeOfDayBucket? = null,
) {
    val mostTriggeredPackage: String? get() = topApps.firstOrNull()?.packageName
    val mostTriggeredLabel: String? get() = topApps.firstOrNull()?.appLabel
    val mostTriggeredCount: Int get() = topApps.firstOrNull()?.attempts ?: 0
    val walkedAwayPercent: Int get() = if (attempts == 0) 0 else (walkedAway * 100) / attempts
}

private const val MAX_TOP_APPS = 5

/**
 * Pure aggregation for [RangeStats]: groups top apps (up to 5, sorted by
 * attempts descending then app label ascending) and buckets attempts by local
 * time of day in [zone].
 */
fun aggregateRangeStats(
    records: List<InterventionRecord>,
    range: StatsRange,
    zone: ZoneId,
): RangeStats {
    if (records.isEmpty()) {
        return RangeStats(range = range)
    }

    var totalWalkedAway = 0
    val bucketAttempts = EnumMap<TimeOfDayBucket, Int>(TimeOfDayBucket::class.java)
    val bucketWalkedAway = EnumMap<TimeOfDayBucket, Int>(TimeOfDayBucket::class.java)

    for (record in records) {
        val isWalkedAway = record.outcome == InterventionOutcome.WALKED_AWAY
        if (isWalkedAway) {
            totalWalkedAway++
        }
        val hour = Instant.ofEpochMilli(record.timestampMillis).atZone(zone).hour
        val bucket = TimeOfDayBucket.fromHour(hour)
        bucketAttempts[bucket] = (bucketAttempts[bucket] ?: 0) + 1
        if (isWalkedAway) {
            bucketWalkedAway[bucket] = (bucketWalkedAway[bucket] ?: 0) + 1
        }
    }

    val topApps = records
        .groupBy { it.packageName }
        .map { (packageName, appRecords) ->
            val latestLabel = appRecords.maxBy { it.timestampMillis }.appLabel
            AppTrendStat(
                packageName = packageName,
                appLabel = latestLabel,
                attempts = appRecords.size,
                walkedAway = appRecords.count { it.outcome == InterventionOutcome.WALKED_AWAY },
            )
        }
        .sortedWith(
            compareByDescending<AppTrendStat> { it.attempts }
                .thenBy { it.appLabel }
                .thenBy { it.packageName },
        )
        .take(MAX_TOP_APPS)

    val timeOfDay = TimeOfDayBucket.entries.map { bucket ->
        TimeOfDayCount(
            bucket = bucket,
            attempts = bucketAttempts[bucket] ?: 0,
            walkedAway = bucketWalkedAway[bucket] ?: 0,
        )
    }

    val peakBucket = timeOfDay
        .maxByOrNull { it.attempts }
        ?.takeIf { it.attempts > 0 }
        ?.bucket

    return RangeStats(
        range = range,
        attempts = records.size,
        walkedAway = totalWalkedAway,
        topApps = topApps,
        timeOfDay = timeOfDay,
        peakBucket = peakBucket,
    )
}
