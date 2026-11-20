package com.pause.app.service

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ForegroundUsageReaderTest {

    @Test
    fun `computeForegroundMillis sums multiple sessions across packages`() {
        val start = 0L
        val now = 60_000L
        val events = listOf(
            UsageEventSample(INSTAGRAM, 5_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 15_000L, UsageTransition.PAUSED),
            UsageEventSample(TIKTOK, 20_000L, UsageTransition.RESUMED),
            UsageEventSample(TIKTOK, 32_000L, UsageTransition.PAUSED),
            UsageEventSample(INSTAGRAM, 40_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 50_000L, UsageTransition.PAUSED),
        )

        val result = computeForegroundMillis(events, start, now)

        assertEquals(20_000L, result[INSTAGRAM])
        assertEquals(12_000L, result[TIKTOK])
    }

    @Test
    fun `computeForegroundMillis counts a still-open app up to nowMillis`() {
        val start = 1_000L
        val now = 25_000L
        val events = listOf(
            UsageEventSample(INSTAGRAM, 10_000L, UsageTransition.RESUMED),
        )

        val result = computeForegroundMillis(events, start, now)

        assertEquals(15_000L, result[INSTAGRAM])
    }

    @Test
    fun `computeForegroundMillis attributes initial pause after midnight to startOfDayMillis`() {
        val start = 100_000L
        val now = 200_000L
        val events = listOf(
            // App was already in foreground across midnight and paused 30s into today.
            UsageEventSample(INSTAGRAM, 130_000L, UsageTransition.PAUSED),
            // Later opened again for 10s.
            UsageEventSample(INSTAGRAM, 150_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 160_000L, UsageTransition.PAUSED),
        )

        val result = computeForegroundMillis(events, start, now)

        assertEquals(40_000L, result[INSTAGRAM])
    }

    @Test
    fun `computeForegroundMillis guards duplicate pause events from double-counting midnight`() {
        val start = 100_000L
        val now = 200_000L
        val events = listOf(
            // ACTIVITY_PAUSED followed shortly by ACTIVITY_STOPPED after midnight.
            UsageEventSample(INSTAGRAM, 110_000L, UsageTransition.PAUSED),
            UsageEventSample(INSTAGRAM, 111_000L, UsageTransition.PAUSED),
            // Normal session followed by both PAUSED and STOPPED.
            UsageEventSample(INSTAGRAM, 150_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 165_000L, UsageTransition.PAUSED),
            UsageEventSample(INSTAGRAM, 166_000L, UsageTransition.PAUSED),
        )

        val result = computeForegroundMillis(events, start, now)

        // 10_000 (from midnight) + 15_000 (normal session), NOT 10_000 + 11_000 + 15_000 + 66_000.
        assertEquals(25_000L, result[INSTAGRAM])
    }

    @Test
    fun `computeForegroundMillis keeps earliest openSince across consecutive resumed events`() {
        val start = 0L
        val now = 50_000L
        val events = listOf(
            UsageEventSample(INSTAGRAM, 10_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 20_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 35_000L, UsageTransition.PAUSED),
        )

        val result = computeForegroundMillis(events, start, now)

        assertEquals(25_000L, result[INSTAGRAM])
    }

    @Test
    fun `computeForegroundMillis filters target packages and window bounds and sorts timestamps`() {
        val start = 10_000L
        val now = 50_000L
        val events = listOf(
            // Out of window: before startOfDayMillis and after nowMillis.
            UsageEventSample(INSTAGRAM, 5_000L, UsageTransition.RESUMED),
            UsageEventSample(INSTAGRAM, 60_000L, UsageTransition.PAUSED),
            // Unsorted in-window events.
            UsageEventSample(INSTAGRAM, 30_000L, UsageTransition.PAUSED),
            UsageEventSample(INSTAGRAM, 20_000L, UsageTransition.RESUMED),
            // Non-target package.
            UsageEventSample(TIKTOK, 15_000L, UsageTransition.RESUMED),
            UsageEventSample(TIKTOK, 45_000L, UsageTransition.PAUSED),
        )

        val result = computeForegroundMillis(
            events = events,
            startOfDayMillis = start,
            nowMillis = now,
            targetPackages = setOf(INSTAGRAM),
        )

        assertEquals(mapOf(INSTAGRAM to 10_000L), result)
        assertTrue(computeForegroundMillis(events, start, now, emptySet()).isEmpty())
        assertTrue(computeForegroundMillis(events, now, start, setOf(INSTAGRAM)).isEmpty())
    }

    @Test
    fun `AndroidUsageStatsReader returns null when Usage Access is not granted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_IGNORED,
        )
        val reader = AndroidUsageStatsReader(context)

        val result = reader.readForegroundMillis(setOf(INSTAGRAM), 0L, 60_000L)

        assertNull(result)
    }

    @Test
    fun `AndroidUsageStatsReader queries UsageStatsManager when Usage Access is granted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadowUsage = shadowOf(usageStatsManager)
        fun addUsageEvent(pkg: String, timeMillis: Long, type: Int) {
            shadowUsage.addEvent(
                ShadowUsageStatsManager.EventBuilder.buildEvent()
                    .setPackage(pkg)
                    .setTimeStamp(timeMillis)
                    .setEventType(type)
                    .build(),
            )
        }
        addUsageEvent(INSTAGRAM, 10_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        addUsageEvent(INSTAGRAM, 25_000L, UsageEvents.Event.ACTIVITY_PAUSED)
        addUsageEvent(INSTAGRAM, 25_500L, UsageEvents.Event.ACTIVITY_STOPPED)
        addUsageEvent(TIKTOK, 15_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        addUsageEvent(TIKTOK, 20_000L, UsageEvents.Event.ACTIVITY_PAUSED)

        val reader = AndroidUsageStatsReader(context)
        val result = reader.readForegroundMillis(setOf(INSTAGRAM), 0L, 60_000L)

        assertNotNull(result)
        assertEquals(mapOf(INSTAGRAM to 15_000L), result)

        // Granted permission with no events for another package yields emptyMap(), not null.
        val zeroResult = reader.readForegroundMillis(setOf("com.example.unused"), 0L, 60_000L)
        assertEquals(emptyMap<String, Long>(), zeroResult)
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val TIKTOK = "com.zhiliaoapp.musically"
    }
}
