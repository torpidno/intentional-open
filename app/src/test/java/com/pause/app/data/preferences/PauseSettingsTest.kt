package com.pause.app.data.preferences

import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StrictModePolicy
import com.pause.app.domain.model.decodeProfiles
import com.pause.app.domain.model.encodeProfiles
import com.pause.app.domain.model.moveFrictionMode
import com.pause.app.util.PermissionSnapshot
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `dailyLimitMinutesFor returns positive limits only`() {
        val settings = PauseSettings(
            dailyLimitsMinutes = mapOf(
                "com.positive" to 30,
                "com.zero" to 0,
                "com.negative" to -15,
            ),
        )

        assertEquals(30, settings.dailyLimitMinutesFor("com.positive"))
        assertNull(settings.dailyLimitMinutesFor("com.zero"))
        assertNull(settings.dailyLimitMinutesFor("com.negative"))
        assertNull(settings.dailyLimitMinutesFor("com.missing"))
    }

    @Test
    fun `daily limits encode and decode deterministically while skipping invalid entries`() {
        val limits = mapOf(
            "com.zeta" to 60,
            "com.alpha" to 15,
            "com.zero" to 0,
            "" to 30,
        )

        val encoded = encodeDailyLimits(limits)
        assertEquals("com.alpha=15|com.zeta=60", encoded)
        assertEquals(mapOf("com.alpha" to 15, "com.zeta" to 60), decodeDailyLimits(encoded))
        assertEquals(
            mapOf("com.ok" to 45),
            decodeDailyLimits("bad|=10|com.neg=-5|com.nan=abc|com.ok=45|com.zero=0"),
        )
    }

    @Test
    fun `profile presets expose expected schedules and friction sequences`() {
        val work = PauseProfile.workPreset(monitoredPackages = setOf("com.social"))
        assertEquals("work", work.id)
        assertTrue(work.scheduleEnabled)
        assertEquals(
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            work.scheduleDays,
        )
        assertEquals(9 * 60, work.scheduleStartMinutes)
        assertEquals(17 * 60, work.scheduleEndMinutes)
        assertEquals(listOf(FrictionMode.BREATHING, FrictionMode.INTENTION), work.defaultFrictionModes)
        assertEquals(setOf("com.social"), work.effectiveMonitoredPackages)

        val evening = PauseProfile.eveningPreset()
        assertEquals("evening", evening.id)
        assertTrue(evening.scheduleEnabled)
        assertEquals(DayOfWeek.entries.toSet(), evening.scheduleDays)
        assertEquals(20 * 60, evening.scheduleStartMinutes)
        assertEquals(23 * 60 + 30, evening.scheduleEndMinutes)
        assertEquals(listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT), evening.defaultFrictionModes)

        val weekend = PauseProfile.weekendPreset()
        assertEquals("weekend", weekend.id)
        assertTrue(weekend.scheduleEnabled)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), weekend.scheduleDays)
        assertEquals(9 * 60, weekend.scheduleStartMinutes)
        assertEquals(22 * 60, weekend.scheduleEndMinutes)
        assertEquals(listOf(FrictionMode.BREATHING), weekend.defaultFrictionModes)
    }

    @Test
    fun `profile isScheduleActive respects scheduleEnabled, weekdays, and overnight spill`() {
        val disabled = PauseProfile(
            id = "manual",
            name = "Manual",
            scheduleEnabled = false,
            scheduleDays = DayOfWeek.entries.toSet(),
            scheduleStartMinutes = 0,
            scheduleEndMinutes = 0,
        )
        assertFalse(disabled.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 10, minute = 0)))

        val overnightSunday = PauseProfile(
            id = "night",
            name = "Night",
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.SUNDAY),
            scheduleStartMinutes = 22 * 60,
            scheduleEndMinutes = 6 * 60,
        )
        assertFalse(overnightSunday.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 21, minute = 59)))
        assertTrue(overnightSunday.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 22, minute = 0)))
        assertTrue(overnightSunday.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 0, minute = 0)))
        assertTrue(overnightSunday.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 5, minute = 59)))
        assertFalse(overnightSunday.isScheduleActive(dayAt(DayOfWeek.MONDAY, hour = 6, minute = 0)))
        assertFalse(overnightSunday.isScheduleActive(dayAt(DayOfWeek.SUNDAY, hour = 2, minute = 0)))
    }

    @Test
    fun `profile effectiveMonitoredPackages excludes paused packages`() {
        val profile = PauseProfile(
            id = "focus",
            name = "Focus",
            monitoredPackages = setOf("com.a", "com.b"),
            pausedPackages = setOf("com.b"),
        )
        assertEquals(setOf("com.a"), profile.effectiveMonitoredPackages)
    }

    @Test
    fun `profile serialization round-trips special characters and handles corrupt input safely`() {
        val special = PauseProfile(
            id = "work-special/1",
            name = "Deep \"Focus\" \\ Pipes | = , \n\t ☕",
            monitoredPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
            pausedPackages = setOf("com.zhiliaoapp.musically"),
            defaultFrictionModes = listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            appModeOverrides = mapOf(
                "com.instagram.android" to listOf(FrictionMode.INTENTION, FrictionMode.BREATHING),
            ),
            scheduleEnabled = true,
            scheduleDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            scheduleStartMinutes = 22 * 60 + 15,
            scheduleEndMinutes = 5 * 60 + 45,
        )
        val minimal = PauseProfile(id = "simple", name = "Simple")

        val encoded = encodeProfiles(listOf(special, minimal))
        assertEquals(listOf(special, minimal), decodeProfiles(encoded))
        assertEquals(listOf(special, minimal), PauseProfile.decodeProfiles(PauseProfile.encodeProfiles(listOf(special, minimal))))
        assertEquals(emptyList<PauseProfile>(), decodeProfiles(encodeProfiles(emptyList())))

        // Defensive parsing on corrupt / adversarial payloads must never throw.
        assertEquals(emptyList<PauseProfile>(), decodeProfiles(""))
        assertEquals(emptyList<PauseProfile>(), decodeProfiles("not json at all"))
        assertEquals(emptyList<PauseProfile>(), decodeProfiles("{\"id\":\"not-an-array\"}"))
        assertEquals(emptyList<PauseProfile>(), decodeProfiles("[{\"id\":\"unterminated"))
        assertEquals(emptyList<PauseProfile>(), decodeProfiles("[".repeat(100) + "]".repeat(100)))

        val partiallyValid = """
            [
              "not-an-object",
              {"id": "", "name": "Missing id"},
              {"id": "missing-name", "name": "   "},
              {
                "id": "valid",
                "name": "Recovered",
                "monitoredPackages": ["com.ok", "", 42],
                "defaultFrictionModes": ["UNKNOWN", "TIME_LIMIT", "TIME_LIMIT"],
                "appModeOverrides": {"com.ok": ["INVALID"], "com.good": ["INTENTION"]},
                "scheduleEnabled": true,
                "scheduleDays": ["BAD_DAY"],
                "scheduleStartMinutes": 99999,
                "scheduleEndMinutes": -10
              },
              {"id": "valid", "name": "Duplicate ignored"}
            ]
        """.trimIndent()

        val decoded = decodeProfiles(partiallyValid)
        assertEquals(1, decoded.size)
        val recovered = decoded.single()
        assertEquals("valid", recovered.id)
        assertEquals("Recovered", recovered.name)
        assertEquals(setOf("com.ok"), recovered.monitoredPackages)
        assertEquals(listOf(FrictionMode.TIME_LIMIT), recovered.defaultFrictionModes)
        assertEquals(mapOf("com.good" to listOf(FrictionMode.INTENTION)), recovered.appModeOverrides)
        assertTrue(recovered.scheduleEnabled)
        assertEquals(DayOfWeek.entries.toSet(), recovered.scheduleDays)
        assertEquals(PauseSettings.DEFAULT_SCHEDULE_START_MINUTES, recovered.scheduleStartMinutes)
        assertEquals(PauseSettings.DEFAULT_SCHEDULE_END_MINUTES, recovered.scheduleEndMinutes)
    }

    @Test
    fun `effectiveAt overlays matching scheduled profile when autoSwitchProfiles is enabled`() {
        val work = PauseProfile.workPreset(monitoredPackages = setOf("com.work.distraction"))
            .copy(
                pausedPackages = setOf("com.work.paused"),
                appModeOverrides = mapOf("com.work.distraction" to listOf(FrictionMode.TIME_LIMIT)),
            )
        val manual = PauseProfile(
            id = "manual",
            name = "Manual",
            monitoredPackages = setOf("com.manual"),
            scheduleEnabled = false,
        )
        val base = PauseSettings(
            monitoredPackages = setOf("com.base"),
            defaultFrictionModes = listOf(FrictionMode.BREATHING),
            scheduleEnabled = false,
            profiles = listOf(manual, work),
            activeProfileId = "manual",
            autoSwitchProfiles = true,
        )

        assertEquals(manual, base.activeProfile)

        val mondayNoon = dayAt(DayOfWeek.MONDAY, hour = 12, minute = 0)
        assertEquals(work, base.scheduledProfileAt(mondayNoon))

        val effective = base.effectiveAt(mondayNoon)
        assertEquals("work", effective.activeProfileId)
        assertEquals(setOf("com.work.distraction"), effective.monitoredPackages)
        assertEquals(setOf("com.work.paused"), effective.pausedPackages)
        assertEquals(listOf(FrictionMode.BREATHING, FrictionMode.INTENTION), effective.defaultFrictionModes)
        assertEquals(
            mapOf("com.work.distraction" to listOf(FrictionMode.TIME_LIMIT)),
            effective.appModeOverrides,
        )
        assertTrue(effective.scheduleEnabled)
        assertEquals(work.scheduleDays, effective.scheduleDays)
        assertEquals(work.scheduleStartMinutes, effective.scheduleStartMinutes)
        assertEquals(work.scheduleEndMinutes, effective.scheduleEndMinutes)

        // Outside work window -> falls back to base settings.
        val mondayEvening = dayAt(DayOfWeek.MONDAY, hour = 19, minute = 0)
        assertNull(base.scheduledProfileAt(mondayEvening))
        assertEquals(base, base.effectiveAt(mondayEvening))

        // Auto-switch off -> scheduledProfileAt is null even inside the window.
        val autoSwitchOff = base.copy(autoSwitchProfiles = false)
        assertNull(autoSwitchOff.scheduledProfileAt(mondayNoon))
        assertEquals(autoSwitchOff, autoSwitchOff.effectiveAt(mondayNoon))
    }

    @Test
    fun `allAccessibleMonitoredPackages includes scheduled profiles only when enabled and auto-switching`() {
        val scheduled = PauseProfile(
            id = "sched",
            name = "Scheduled",
            monitoredPackages = setOf("com.sched", "com.sched.paused"),
            pausedPackages = setOf("com.sched.paused"),
            scheduleEnabled = true,
        )
        val unscheduled = PauseProfile(
            id = "manual",
            name = "Manual",
            monitoredPackages = setOf("com.manual.only"),
            scheduleEnabled = false,
        )
        val base = PauseSettings(
            isEnabled = true,
            monitoredPackages = setOf("com.base", "com.base.paused"),
            pausedPackages = setOf("com.base.paused"),
            profiles = listOf(scheduled, unscheduled),
            autoSwitchProfiles = true,
        )

        assertEquals(setOf("com.base", "com.sched"), base.allAccessibleMonitoredPackages)
        assertEquals(
            setOf("com.base"),
            base.copy(autoSwitchProfiles = false).allAccessibleMonitoredPackages,
        )
        assertEquals(
            emptySet<String>(),
            base.copy(isEnabled = false).allAccessibleMonitoredPackages,
        )
    }

    @Test
    fun `StrictModePolicy matchesPhrase normalizes whitespace and case and rejects blank target`() {
        val expected = "I choose to change this"
        assertTrue(StrictModePolicy.matchesPhrase("I choose to change this", expected))
        assertTrue(StrictModePolicy.matchesPhrase("  i   CHOOSE\tto\nchange   THIS  ", expected))
        assertFalse(StrictModePolicy.matchesPhrase("I choose to change", expected))
        assertFalse(StrictModePolicy.matchesPhrase("", expected))
        assertFalse(StrictModePolicy.matchesPhrase("", ""))
        assertFalse(StrictModePolicy.matchesPhrase("   ", "   "))

        assertEquals(
            PauseSettings.DEFAULT_STRICT_MODE_PHRASE,
            PauseSettings(strictModePhrase = "   ").effectiveStrictModePhrase,
        )
        assertEquals(
            "Stay focused",
            PauseSettings(strictModePhrase = "  Stay focused  ").effectiveStrictModePhrase,
        )
    }

    @Test
    fun `optional usageAccessGranted does not change core PermissionSnapshot counts`() {
        val coreOnly = PermissionSnapshot(
            accessibilityGranted = true,
            overlayGranted = true,
            batteryOptimizationIgnored = true,
            notificationsGranted = true,
            usageAccessGranted = false,
        )
        assertEquals(4, PermissionSnapshot.TOTAL_COUNT)
        assertEquals(4, coreOnly.grantedCount)
        assertTrue(coreOnly.allGranted)

        val usageOnly = PermissionSnapshot(usageAccessGranted = true)
        assertEquals(0, usageOnly.grantedCount)
        assertFalse(usageOnly.allGranted)
    }

    private fun mondayAt(hour: Int, minute: Int): ZonedDateTime =
        dayAt(DayOfWeek.MONDAY, hour, minute)

    private fun dayAt(day: DayOfWeek, hour: Int, minute: Int): ZonedDateTime {
        // 2026-11-16 is a Monday (DayOfWeek.MONDAY.value == 1).
        val dayOfMonth = 16 + (day.value - DayOfWeek.MONDAY.value)
        return ZonedDateTime.of(2026, 11, dayOfMonth, hour, minute, 0, 0, ZoneOffset.UTC)
    }
}
