package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.moveFrictionMode
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseSettingsTest {

    @Test
    fun `default modes keep the configured order and remove duplicates`() {
        val settings = PauseSettings(
            defaultFrictionModes = listOf(
                FrictionMode.TIME_LIMIT,
                FrictionMode.BREATHING,
                FrictionMode.TIME_LIMIT,
            ),
        )

        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            settings.frictionModesFor("com.instagram.android"),
        )
    }

    @Test
    fun `an app override replaces the whole global sequence`() {
        val settings = PauseSettings(
            defaultFrictionModes = listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            appModeOverrides = mapOf("com.example" to listOf(FrictionMode.INTENTION)),
        )

        assertEquals(
            listOf(FrictionMode.INTENTION),
            settings.frictionModesFor("com.example"),
        )
        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            settings.copy(
                appModeOverrides = mapOf(
                    "com.example" to listOf(
                        FrictionMode.TIME_LIMIT,
                        FrictionMode.INTENTION,
                    ),
                ),
            ).frictionModesFor("com.example"),
        )
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            settings.frictionModesFor("com.other"),
        )
    }

    @Test
    fun `legacy single-mode overrides decode as a one-item sequence`() {
        assertEquals(
            mapOf("com.example" to listOf(FrictionMode.INTENTION)),
            decodeOverrides("com.example=INTENTION"),
        )
    }

    @Test
    fun `multi-mode overrides round-trip in their configured order`() {
        val overrides = mapOf(
            "com.example" to listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            "com.other" to listOf(FrictionMode.INTENTION),
        )

        assertEquals(overrides, decodeOverrides(encodeOverrides(overrides)))
        assertEquals(
            "com.example=TIME_LIMIT,BREATHING|com.other=INTENTION",
            encodeOverrides(overrides),
        )
    }

    @Test
    fun `legacy encoded overrides keep their stored order`() {
        assertEquals(
            mapOf(
                "com.example" to listOf(
                    FrictionMode.TIME_LIMIT,
                    FrictionMode.BREATHING,
                ),
            ),
            decodeOverrides("com.example=TIME_LIMIT,BREATHING"),
        )
    }

    @Test
    fun `moving a mode clamps at both ends`() {
        val modes = listOf(
            FrictionMode.BREATHING,
            FrictionMode.INTENTION,
            FrictionMode.TIME_LIMIT,
        )

        assertEquals(
            listOf(FrictionMode.INTENTION, FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            modes.moveFrictionMode(FrictionMode.BREATHING, 1),
        )
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            modes.moveFrictionMode(FrictionMode.TIME_LIMIT, -1),
        )
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.BREATHING, -1))
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.TIME_LIMIT, 1))
        assertEquals(modes, modes.moveFrictionMode(FrictionMode.INTENTION, 0))
    }

    @Test
    fun `an empty sequence still falls back to breathing`() {
        val settings = PauseSettings(defaultFrictionModes = emptyList())

        assertEquals(
            listOf(FrictionMode.BREATHING),
            settings.frictionModesFor("com.instagram.android"),
        )
    }

    @Test
    fun `a disabled schedule is active at every time`() {
        val settings = PauseSettings(
            scheduleEnabled = false,
            scheduleDays = emptySet(),
            scheduleStartMinutes = 9 * 60,
            scheduleEndMinutes = 17 * 60,
        )

        assertTrue(settings.isScheduleActive(mondayAt(hour = 3, minute = 0)))
    }

    @Test
    fun `active hours respect the selected days and boundaries`() {
        val settings = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.MONDAY),
            scheduleStartMinutes = 9 * 60,
            scheduleEndMinutes = 17 * 60,
        )

        assertTrue(settings.isScheduleActive(mondayAt(hour = 9, minute = 0)))
        assertTrue(settings.isScheduleActive(mondayAt(hour = 16, minute = 59)))
        assertFalse(settings.isScheduleActive(mondayAt(hour = 17, minute = 0)))
        assertFalse(
            settings.isScheduleActive(
                ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, ZoneOffset.UTC),
            ),
        )
    }

    @Test
    fun `an overnight window wraps past midnight`() {
        val settings = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = DayOfWeek.entries.toSet(),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )

        assertTrue(settings.isScheduleActive(mondayAt(hour = 22, minute = 0)))
        assertTrue(settings.isScheduleActive(mondayAt(hour = 23, minute = 30)))
        assertTrue(
            settings.isScheduleActive(
                ZonedDateTime.of(2026, 11, 17, 5, 59, 0, 0, ZoneOffset.UTC),
            ),
        )
        assertFalse(
            settings.isScheduleActive(
                ZonedDateTime.of(2026, 11, 17, 6, 0, 0, 0, ZoneOffset.UTC),
            ),
        )
        assertFalse(settings.isScheduleActive(mondayAt(hour = 12, minute = 0)))
    }

    @Test
    fun `an overnight window on a subset of days spills into the next morning only`() {
        val settings = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.MONDAY),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )

        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 21, minute = 59)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 22, minute = 0)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 23, minute = 59)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 0, minute = 0)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 5, minute = 59)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 6, minute = 0)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 22, minute = 0)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 2, minute = 0)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 23, minute = 0)))
    }

    @Test
    fun `an overnight window on Sunday wraps into Monday morning`() {
        val settings = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.SUNDAY),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )

        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 21, minute = 59)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 22, minute = 0)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 23, minute = 59)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 0, minute = 0)))
        assertTrue(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 5, minute = 59)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 6, minute = 0)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 22, minute = 0)))
        assertFalse(settings.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 2, minute = 0)))
    }

    @Test
    fun `equal schedule endpoints and empty schedule days respect selected days`() {
        val mondayOnly = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.MONDAY),
            scheduleStartMinutes = 0,
            scheduleEndMinutes = 0,
        )
        assertTrue(mondayOnly.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 0, minute = 0)))
        assertFalse(mondayOnly.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 0, minute = 0)))

        val noDaysOvernight = PauseSettings(
            scheduleEnabled = true,
            scheduleDays = emptySet(),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )
        assertFalse(noDaysOvernight.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 23, minute = 0)))
        assertFalse(noDaysOvernight.isScheduleActive(dayAt(DayOfWeek.TUESDAY, hour = 2, minute = 0)))
    }

    @Test
    fun `equal schedule endpoints mean all-day`() {
        val settings = PauseSettings(
            scheduleEnabled = true,
            scheduleStartMinutes = 0,
            scheduleEndMinutes = 0,
        )

        assertTrue(settings.isScheduleActive(mondayAt(hour = 0, minute = 0)))
        assertTrue(settings.isScheduleActive(mondayAt(hour = 23, minute = 59)))
    }

    @Test
    fun `a snooze is active only before its deadline`() {
        val settings = PauseSettings(
            snoozedUntilMillis = mapOf("com.example" to 2_000L),
        )

        assertTrue(settings.isSnoozed("com.example", nowMillis = 1_999L))
        assertFalse(settings.isSnoozed("com.example", nowMillis = 2_000L))
        assertFalse(settings.isSnoozed("com.other", nowMillis = 1_000L))
    }

    private fun mondayAt(hour: Int, minute: Int): ZonedDateTime =
        dayAt(DayOfWeek.MONDAY, hour, minute)

    private fun dayAt(day: DayOfWeek, hour: Int, minute: Int): ZonedDateTime {
        // 2026-11-16 is a Monday (DayOfWeek.MONDAY.value == 1).
        val dayOfMonth = 16 + (day.value - DayOfWeek.MONDAY.value)
        return ZonedDateTime.of(2026, 11, dayOfMonth, hour, minute, 0, 0, ZoneOffset.UTC)
    }
}
