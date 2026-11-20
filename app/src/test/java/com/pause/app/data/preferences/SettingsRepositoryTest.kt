package com.pause.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.PauseProfile
import com.pause.app.domain.model.StrictModeChallenge
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * File-backed Preferences DataStore tests for the ordered sequence keys. The
 * defaults remain readable from the old unordered set, but current writes use
 * the comma-separated ordered value.
 */
class SettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun TestScope.createRepository(
        clock: Clock? = null,
    ): Pair<DataStore<Preferences>, SettingsRepository> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = {
                File(temporaryFolder.root, "settings.preferences_pb")
            },
        )
        val repository = if (clock == null) {
            SettingsRepository(dataStore, backgroundScope)
        } else {
            SettingsRepository(dataStore, backgroundScope, clock)
        }
        return dataStore to repository
    }

    @Test
    fun `default sequence persists in the order it was saved`() = runTest {
        val (dataStore, repository) = createRepository()
        repository.setDefaultFrictionModes(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
        )

        val settings = repository.settings.first {
            it.defaultFrictionModes == listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING)
        }
        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.BREATHING),
            settings.defaultFrictionModes,
        )
        assertEquals(
            "TIME_LIMIT,BREATHING",
            dataStore.data.first()[stringPreferencesKey("default_friction_sequence")],
        )
    }

    @Test
    fun `moving the default mode persists the new order`() = runTest {
        val (_, repository) = createRepository()
        repository.setDefaultFrictionModes(
            listOf(
                FrictionMode.BREATHING,
                FrictionMode.INTENTION,
                FrictionMode.TIME_LIMIT,
            ),
        )
        repository.settings.first {
            it.defaultFrictionModes ==
                listOf(FrictionMode.BREATHING, FrictionMode.INTENTION, FrictionMode.TIME_LIMIT)
        }

        repository.moveDefaultFrictionMode(FrictionMode.TIME_LIMIT, -1)

        val settings = repository.settings.first {
            it.defaultFrictionModes ==
                listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION)
        }
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            settings.defaultFrictionModes,
        )
    }

    @Test
    fun `toggling a mode appends it to the sequence`() = runTest {
        val (_, repository) = createRepository()
        repository.setDefaultFrictionModes(listOf(FrictionMode.BREATHING))
        repository.settings.first { it.defaultFrictionModes == listOf(FrictionMode.BREATHING) }

        repository.toggleDefaultFrictionMode(FrictionMode.TIME_LIMIT)
        repository.settings.first {
            it.defaultFrictionModes ==
                listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT)
        }
        repository.toggleDefaultFrictionMode(FrictionMode.INTENTION)

        val settings = repository.settings.first {
            it.defaultFrictionModes ==
                listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION)
        }
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            settings.defaultFrictionModes,
        )
    }

    @Test
    fun `per-app override persists in the configured order`() = runTest {
        val (dataStore, repository) = createRepository()
        repository.setAppModeOverrides(
            "com.example",
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
        )

        val settings = repository.settings.first {
            it.appModeOverrides["com.example"] ==
                listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION)
        }
        assertEquals(
            listOf(FrictionMode.TIME_LIMIT, FrictionMode.INTENTION),
            settings.appModeOverrides["com.example"],
        )
        assertEquals(
            "com.example=TIME_LIMIT,INTENTION",
            dataStore.data.first()[stringPreferencesKey("app_mode_overrides")],
        )
    }

    @Test
    fun `schedule window, days, enablement and retention persist`() = runTest {
        val (_, repository) = createRepository()
        repository.setScheduleEnabled(true)
        repository.setScheduleWindow(startMinutes = 22 * 60, endMinutes = 6 * 60)
        repository.toggleScheduleDay(DayOfWeek.MONDAY)
        repository.setHistoryRetentionDays(30)

        val settings = repository.settings.first {
            it.scheduleEnabled &&
                it.historyRetentionDays == 30 &&
                DayOfWeek.MONDAY !in it.scheduleDays
        }
        assertEquals(true, settings.scheduleEnabled)
        assertEquals(22 * 60, settings.scheduleStartMinutes)
        assertEquals(6 * 60, settings.scheduleEndMinutes)
        assertEquals(30, settings.historyRetentionDays)
        assertEquals(DayOfWeek.entries.toSet() - DayOfWeek.MONDAY, settings.scheduleDays)
    }

    @Test
    fun `retention is read directly from disk for maintenance`() = runTest {
        val (_, repository) = createRepository()
        assertEquals(0, repository.currentHistoryRetentionDays())

        repository.setHistoryRetentionDays(30)

        assertEquals(30, repository.currentHistoryRetentionDays())
    }

    @Test
    fun `snooze deadline persists and can be cleared`() = runTest {
        val (_, repository) = createRepository()
        val until = System.currentTimeMillis() + 60_000L
        repository.setAppSnoozedUntil("com.example", until)

        repository.settings.first { it.snoozedUntilMillis["com.example"] == until }
        repository.clearAppSnooze("com.example")

        val cleared = repository.settings.first { it.snoozedUntilMillis.isEmpty() }
        assertEquals(emptyMap<String, Long>(), cleared.snoozedUntilMillis)
    }

    @Test
    fun `an old unordered default set falls back to canonical order`() = runTest {
        val (dataStore, repository) = createRepository()
        dataStore.edit { preferences ->
            preferences[stringSetPreferencesKey("default_friction_modes")] =
                setOf("TIME_LIMIT", "BREATHING")
        }

        val settings = repository.settings.first {
            it.defaultFrictionModes ==
                listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT)
        }
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            settings.defaultFrictionModes,
        )
    }

    @Test
    fun `snoozeApp computes deadline from injected clock`() = runTest {
        val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)
        val (dataStore, repository) = createRepository(clock = fixedClock)

        repository.snoozeApp("com.example", minutes = 15)

        val expectedDeadline = 1_000_000L + 15 * 60_000L
        val settings = repository.settings.first {
            it.snoozedUntilMillis["com.example"] == expectedDeadline
        }
        assertEquals(mapOf("com.example" to expectedDeadline), settings.snoozedUntilMillis)
        assertEquals(
            "com.example=$expectedDeadline",
            dataStore.data.first()[stringPreferencesKey("snoozed_until")],
        )
    }

    @Test
    fun `setAppSnoozedUntil prunes expired snoozes including exact deadline`() = runTest {
        val clock = MutableClock(1_000_000L)
        val (_, repository) = createRepository(clock = clock)
        repository.setAppSnoozedUntil("com.expired", 1_060_000L)
        repository.setAppSnoozedUntil("com.boundary", 1_120_000L)
        repository.setAppSnoozedUntil("com.active", 1_300_000L)
        repository.settings.first { it.snoozedUntilMillis.size == 3 }

        clock.advanceBy(120_000L) // now == 1_120_000L
        repository.setAppSnoozedUntil("com.fresh", 1_500_000L)

        val pruned = repository.settings.first { "com.fresh" in it.snoozedUntilMillis }
        assertEquals(
            mapOf(
                "com.active" to 1_300_000L,
                "com.fresh" to 1_500_000L,
            ),
            pruned.snoozedUntilMillis,
        )

        repository.setAppSnoozedUntil("com.active", 1_120_000L)
        val removedExpiredUpdate = repository.settings.first {
            "com.active" !in it.snoozedUntilMillis
        }
        assertEquals(mapOf("com.fresh" to 1_500_000L), removedExpiredUpdate.snoozedUntilMillis)
    }

    @Test
    fun `clearAppSnooze prunes expired snoozes even when target was not snoozed`() = runTest {
        val clock = MutableClock(1_000_000L)
        val (_, repository) = createRepository(clock = clock)
        repository.setAppSnoozedUntil("com.expired", 1_050_000L)
        repository.setAppSnoozedUntil("com.active", 1_200_000L)
        repository.settings.first { it.snoozedUntilMillis.size == 2 }

        clock.advanceBy(100_000L) // now == 1_100_000L
        repository.clearAppSnooze("com.other")

        val pruned = repository.settings.first { "com.expired" !in it.snoozedUntilMillis }
        assertEquals(mapOf("com.active" to 1_200_000L), pruned.snoozedUntilMillis)

        repository.clearAppSnooze("com.active")
        val cleared = repository.settings.first { it.snoozedUntilMillis.isEmpty() }
        assertEquals(emptyMap<String, Long>(), cleared.snoozedUntilMillis)
    }

    @Test
    fun `unmonitoring an app with setMonitored clears its snooze and prunes expired snoozes`() =
        runTest {
            val clock = MutableClock(1_000_000L)
            val (_, repository) = createRepository(clock = clock)
            repository.setMonitored("com.target", monitored = true)
            repository.setMonitored("com.other", monitored = true)
            repository.setAppSnoozedUntil("com.target", 1_500_000L)
            repository.setAppSnoozedUntil("com.expired", 1_050_000L)
            repository.setAppSnoozedUntil("com.other", 1_600_000L)
            repository.settings.first {
                "com.target" in it.monitoredPackages && it.snoozedUntilMillis.size == 3
            }

            clock.advanceBy(100_000L) // now == 1_100_000L
            repository.setMonitored("com.target", monitored = false)

            val unmonitored = repository.settings.first { "com.target" !in it.monitoredPackages }
            assertEquals(mapOf("com.other" to 1_600_000L), unmonitored.snoozedUntilMillis)

            repository.setMonitored("com.target", monitored = true)
            val reAdded = repository.settings.first { "com.target" in it.monitoredPackages }
            assertFalse(reAdded.isSnoozed("com.target", nowMillis = 1_100_000L))
            assertEquals(mapOf("com.other" to 1_600_000L), reAdded.snoozedUntilMillis)
        }

    @Test
    fun `toggling an app off with toggleMonitored clears its snooze and prunes expired snoozes`() =
        runTest {
            val clock = MutableClock(1_000_000L)
            val (_, repository) = createRepository(clock = clock)
            assertTrue(repository.toggleMonitored("com.target"))
            repository.setAppSnoozedUntil("com.target", 1_500_000L)
            repository.setAppSnoozedUntil("com.expired", 1_050_000L)
            repository.setAppSnoozedUntil("com.keep", 1_600_000L)
            repository.settings.first {
                "com.target" in it.monitoredPackages && it.snoozedUntilMillis.size == 3
            }

            clock.advanceBy(100_000L) // now == 1_100_000L
            assertFalse(repository.toggleMonitored("com.target"))

            val toggledOff = repository.settings.first { "com.target" !in it.monitoredPackages }
            assertEquals(mapOf("com.keep" to 1_600_000L), toggledOff.snoozedUntilMillis)
        }

    @Test
    fun `daily limits persist, update, and clear cleanly`() = runTest {
        val (dataStore, repository) = createRepository()
        repository.setDailyLimitMinutes("com.instagram.android", 30)
        repository.setDailyLimitMinutes("com.zhiliaoapp.musically", 60)
        repository.setDailyLimitMinutes("   ", 15)

        val saved = repository.settings.first { it.dailyLimitsMinutes.size == 2 }
        assertEquals(
            mapOf("com.instagram.android" to 30, "com.zhiliaoapp.musically" to 60),
            saved.dailyLimitsMinutes,
        )
        assertEquals(
            "com.instagram.android=30|com.zhiliaoapp.musically=60",
            dataStore.data.first()[stringPreferencesKey("daily_limits_minutes")],
        )

        repository.setDailyLimitMinutes("com.instagram.android", 0)
        val afterZero = repository.settings.first { "com.instagram.android" !in it.dailyLimitsMinutes }
        assertEquals(mapOf("com.zhiliaoapp.musically" to 60), afterZero.dailyLimitsMinutes)

        repository.setDailyLimitMinutes("com.zhiliaoapp.musically", null)
        val afterClear = repository.settings.first { it.dailyLimitsMinutes.isEmpty() }
        assertEquals(emptyMap<String, Int>(), afterClear.dailyLimitsMinutes)
    }

    @Test
    fun `profiles save, activate, switch atomically, and preserve edits across switches`() = runTest {
        val (_, repository) = createRepository()
        val work = PauseProfile.workPreset(
            id = "work",
            monitoredPackages = setOf("com.instagram.android"),
            name = "Work \"Deep\" | Focus",
        )
        val evening = PauseProfile.eveningPreset(
            id = "evening",
            monitoredPackages = setOf("com.zhiliaoapp.musically"),
        )

        // Blank id or name is ignored.
        repository.saveProfile(PauseProfile(id = " ", name = "Invalid"), activate = true)
        repository.saveProfile(PauseProfile(id = "no-name", name = "  "), activate = true)

        repository.saveProfile(work, activate = true)
        repository.saveProfile(evening, activate = false)

        val activeWork = repository.settings.first {
            it.activeProfileId == "work" && it.profiles.size == 2
        }
        assertEquals(setOf("com.instagram.android"), activeWork.monitoredPackages)
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.INTENTION),
            activeWork.defaultFrictionModes,
        )
        assertTrue(activeWork.scheduleEnabled)
        assertEquals(9 * 60, activeWork.scheduleStartMinutes)
        assertEquals(17 * 60, activeWork.scheduleEndMinutes)

        // Mutate top-level bundle settings while "work" is active -> synced into "work" atomically.
        repository.setMonitored("com.reddit.frontpage", monitored = true)
        repository.setAppPaused("com.instagram.android", paused = true)
        repository.setAppModeOverrides("com.reddit.frontpage", listOf(FrictionMode.TIME_LIMIT))
        repository.moveDefaultFrictionMode(FrictionMode.INTENTION, -1)
        repository.setScheduleWindow(startMinutes = 8 * 60, endMinutes = 16 * 60)
        repository.toggleScheduleDay(DayOfWeek.FRIDAY)

        val synced = repository.settings.first {
            it.activeProfile?.monitoredPackages ==
                setOf("com.instagram.android", "com.reddit.frontpage") &&
                it.activeProfile?.pausedPackages == setOf("com.instagram.android") &&
                it.activeProfile?.defaultFrictionModes ==
                listOf(FrictionMode.INTENTION, FrictionMode.BREATHING) &&
                it.activeProfile?.scheduleStartMinutes == 8 * 60 &&
                DayOfWeek.FRIDAY !in it.activeProfile?.scheduleDays.orEmpty()
        }
        assertEquals(
            mapOf("com.reddit.frontpage" to listOf(FrictionMode.TIME_LIMIT)),
            synced.activeProfile?.appModeOverrides,
        )

        // Switch to "evening" -> top-level switches to evening, while "work" keeps its edits.
        repository.selectProfile("evening")
        val onEvening = repository.settings.first { it.activeProfileId == "evening" }
        assertEquals(setOf("com.zhiliaoapp.musically"), onEvening.monitoredPackages)
        assertEquals(emptySet<String>(), onEvening.pausedPackages)
        assertEquals(
            listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT),
            onEvening.defaultFrictionModes,
        )
        assertEquals(20 * 60, onEvening.scheduleStartMinutes)

        // Switch back to "work" -> all prior edits return intact.
        repository.selectProfile("work")
        val backOnWork = repository.settings.first { it.activeProfileId == "work" }
        assertEquals(
            setOf("com.instagram.android", "com.reddit.frontpage"),
            backOnWork.monitoredPackages,
        )
        assertEquals(setOf("com.instagram.android"), backOnWork.pausedPackages)
        assertEquals(
            listOf(FrictionMode.INTENTION, FrictionMode.BREATHING),
            backOnWork.defaultFrictionModes,
        )
        assertEquals(8 * 60, backOnWork.scheduleStartMinutes)
        assertEquals(16 * 60, backOnWork.scheduleEndMinutes)
        assertFalse(DayOfWeek.FRIDAY in backOnWork.scheduleDays)

        // Unlinking active profile keeps current top-level settings intact.
        repository.selectProfile(null)
        val unlinked = repository.settings.first { it.activeProfileId == null }
        assertEquals(
            setOf("com.instagram.android", "com.reddit.frontpage"),
            unlinked.monitoredPackages,
        )
    }

    @Test
    fun `updating active profile syncs top-level and deleting active profile preserves monitoring`() =
        runTest {
            val (_, repository) = createRepository()
            val weekend = PauseProfile.weekendPreset(monitoredPackages = setOf("com.app.a"))
            repository.saveProfile(weekend, activate = true)
            repository.settings.first { it.activeProfileId == "weekend" }

            // Saving an updated version of the active profile syncs top-level immediately.
            repository.saveProfile(
                weekend.copy(
                    monitoredPackages = setOf("com.app.b"),
                    defaultFrictionModes = listOf(FrictionMode.INTENTION),
                    scheduleEnabled = false,
                ),
                activate = false,
            )
            val updated = repository.settings.first {
                it.monitoredPackages == setOf("com.app.b") && !it.scheduleEnabled
            }
            assertEquals("weekend", updated.activeProfileId)
            assertEquals(listOf(FrictionMode.INTENTION), updated.defaultFrictionModes)

            // Deleting the active profile clears activeProfileId without wiping top-level apps.
            repository.deleteProfile("weekend")
            val afterDelete = repository.settings.first { it.profiles.isEmpty() }
            assertNull(afterDelete.activeProfileId)
            assertEquals(setOf("com.app.b"), afterDelete.monitoredPackages)

            repository.setAutoSwitchProfiles(true)
            val autoOn = repository.settings.first { it.autoSwitchProfiles }
            assertTrue(autoOn.autoSwitchProfiles)
        }

    @Test
    fun `active profile syncs toggleMonitored, toggleDefaultFrictionMode, and setScheduleEnabled`() =
        runTest {
            val (_, repository) = createRepository()
            repository.saveProfile(
                PauseProfile(
                    id = "focus",
                    name = "Focus",
                    monitoredPackages = emptySet(),
                    defaultFrictionModes = listOf(FrictionMode.BREATHING),
                    scheduleEnabled = false,
                ),
                activate = true,
            )
            repository.settings.first { it.activeProfileId == "focus" }

            assertTrue(repository.toggleMonitored("com.example"))
            repository.toggleDefaultFrictionMode(FrictionMode.TIME_LIMIT)
            repository.setScheduleEnabled(true)

            val synced = repository.settings.first {
                val p = it.activeProfile
                p != null &&
                    "com.example" in p.monitoredPackages &&
                    p.defaultFrictionModes == listOf(FrictionMode.BREATHING, FrictionMode.TIME_LIMIT) &&
                    p.scheduleEnabled
            }
            assertEquals(setOf("com.example"), synced.activeProfile?.monitoredPackages)
        }

    @Test
    fun `strict mode preferences persist and enforce bounds`() = runTest {
        val (_, repository) = createRepository()
        repository.setStrictModeEnabled(true)
        repository.setStrictModeChallenge(StrictModeChallenge.PHRASE)
        repository.setStrictModeDelaySeconds(45)
        repository.setStrictModeDelaySeconds(4) // out of bounds -> ignored
        repository.setStrictModeDelaySeconds(301) // out of bounds -> ignored

        val longPhrase = "  " + "a".repeat(PauseSettings.MAX_STRICT_MODE_PHRASE_LENGTH + 20) + "  "
        repository.setStrictModePhrase(longPhrase)

        val saved = repository.settings.first {
            it.strictModeEnabled &&
                it.strictModeChallenge == StrictModeChallenge.PHRASE &&
                it.strictModeDelaySeconds == 45 &&
                it.strictModePhrase.length == PauseSettings.MAX_STRICT_MODE_PHRASE_LENGTH
        }
        assertTrue(saved.strictModeEnabled)
        assertEquals(StrictModeChallenge.PHRASE, saved.strictModeChallenge)
        assertEquals(45, saved.strictModeDelaySeconds)
        assertEquals("a".repeat(PauseSettings.MAX_STRICT_MODE_PHRASE_LENGTH), saved.strictModePhrase)
    }

    private class MutableClock(
        private var currentMillis: Long,
        private val zoneId: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        fun advanceBy(deltaMillis: Long) {
            currentMillis += deltaMillis
        }

        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentMillis, zone)
        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
    }
}
