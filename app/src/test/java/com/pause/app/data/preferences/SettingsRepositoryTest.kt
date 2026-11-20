package com.pause.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.pause.app.domain.model.FrictionMode
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
