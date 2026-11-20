package com.pause.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pause.app.data.local.DailyUsageEntity
import com.pause.app.data.local.InterventionLogEntity
import com.pause.app.data.local.PauseDatabase
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real SQLite upgrade paths (1→3 and 2→3), not just the entity model.
 *
 * Opens a pre-populated legacy SQLite file directly through Room with the
 * production migrations so Room's schema validation verifies the exact DDL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PauseDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration 1 to 3 preserves history, adds sequence column, and creates daily_usage`() =
        runTest {
            createVersionOneDatabase()

            val database = openMigratedDatabase()
            try {
                val legacy = database.interventionLogDao().observeRecent(10).first().single()
                assertEquals("Legacy App", legacy.appLabel)
                assertEquals("Keep this note", legacy.intention)
                assertEquals(InterventionOutcome.WALKED_AWAY, legacy.outcome)
                assertEquals(FrictionMode.BREATHING, legacy.frictionMode)
                assertNull(legacy.frictionModeSequence)

                database.interventionLogDao().insert(
                    InterventionLogEntity(
                        packageName = "com.example.new",
                        appLabel = "New App",
                        timestampMillis = 2_000L,
                        outcome = InterventionOutcome.PROCEEDED,
                        frictionMode = FrictionMode.BREATHING,
                        frictionModeSequence = "BREATHING,TIME_LIMIT",
                        sessionLimitMinutes = 15,
                    ),
                )

                val rows = database.interventionLogDao().observeRecent(10).first()
                assertEquals(2, rows.size)
                assertEquals("BREATHING,TIME_LIMIT", rows.first().frictionModeSequence)

                // Verify daily_usage table created by MIGRATION_2_3 supports full read/write.
                database.dailyUsageDao().upsert(
                    DailyUsageEntity(
                        localDate = "2026-05-14",
                        packageName = "com.example.legacy",
                        usedMillis = 900_000L,
                        updatedAtMillis = 5_000L,
                    ),
                )
                assertEquals(
                    900_000L,
                    database.dailyUsageDao().getUsedMillis("2026-05-14", "com.example.legacy"),
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `migration 2 to 3 preserves v2 rows and enables daily_usage read and write`() = runTest {
        createVersionTwoDatabase()

        val database = openMigratedDatabase()
        try {
            val existing = database.interventionLogDao().observeRecent(10).first().single()
            assertEquals("com.example.v2", existing.packageName)
            assertEquals("V2 App", existing.appLabel)
            assertEquals("INTENTION,TIME_LIMIT", existing.frictionModeSequence)
            assertEquals("Check messages", existing.intention)
            assertEquals(10, existing.sessionLimitMinutes)
            assertEquals(InterventionOutcome.PROCEEDED, existing.outcome)

            val usageDao = database.dailyUsageDao()
            usageDao.upsertAll(
                listOf(
                    DailyUsageEntity(
                        localDate = "2026-05-13",
                        packageName = "com.example.v2",
                        usedMillis = 1_200_000L,
                        updatedAtMillis = 10_000L,
                    ),
                    DailyUsageEntity(
                        localDate = "2026-05-14",
                        packageName = "com.example.v2",
                        usedMillis = 300_000L,
                        updatedAtMillis = 20_000L,
                    ),
                ),
            )
            // Replace conflict strategy updates existing (localDate, packageName) row.
            usageDao.upsert(
                DailyUsageEntity(
                    localDate = "2026-05-14",
                    packageName = "com.example.v2",
                    usedMillis = 600_000L,
                    updatedAtMillis = 30_000L,
                ),
            )

            assertEquals(600_000L, usageDao.getUsedMillis("2026-05-14", "com.example.v2"))
            val todayRows = usageDao.observeForDate("2026-05-14").first()
            assertEquals(1, todayRows.size)
            assertEquals(600_000L, todayRows.single().usedMillis)

            usageDao.deleteOlderThan("2026-05-14")
            assertNull(usageDao.getUsedMillis("2026-05-13", "com.example.v2"))
            assertEquals(600_000L, usageDao.getUsedMillis("2026-05-14", "com.example.v2"))
        } finally {
            database.close()
        }
    }

    private fun openMigratedDatabase(): PauseDatabase = Room.databaseBuilder(
        context,
        PauseDatabase::class.java,
        DB_NAME,
    )
        .addMigrations(
            PauseDatabase.MIGRATION_1_2,
            PauseDatabase.MIGRATION_2_3,
        )
        .allowMainThreadQueries()
        .build()

    private fun createVersionOneDatabase() {
        val database = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `intervention_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appLabel` TEXT NOT NULL,
                    `timestampMillis` INTEGER NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `frictionMode` TEXT NOT NULL,
                    `intention` TEXT,
                    `sessionLimitMinutes` INTEGER
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_intervention_logs_timestampMillis` " +
                    "ON `intervention_logs` (`timestampMillis`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_intervention_logs_packageName` " +
                    "ON `intervention_logs` (`packageName`)",
            )
            database.execSQL(
                """
                INSERT INTO `intervention_logs`
                    (`packageName`, `appLabel`, `timestampMillis`, `outcome`, `frictionMode`, `intention`)
                VALUES
                    ('com.example.legacy', 'Legacy App', 1000, 'WALKED_AWAY', 'BREATHING', 'Keep this note')
                """.trimIndent(),
            )
            database.version = 1
        } finally {
            database.close()
        }
    }

    private fun createVersionTwoDatabase() {
        val database = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `intervention_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appLabel` TEXT NOT NULL,
                    `timestampMillis` INTEGER NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `frictionMode` TEXT NOT NULL,
                    `frictionModeSequence` TEXT,
                    `intention` TEXT,
                    `sessionLimitMinutes` INTEGER
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_intervention_logs_timestampMillis` " +
                    "ON `intervention_logs` (`timestampMillis`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_intervention_logs_packageName` " +
                    "ON `intervention_logs` (`packageName`)",
            )
            database.execSQL(
                """
                INSERT INTO `intervention_logs`
                    (`packageName`, `appLabel`, `timestampMillis`, `outcome`, `frictionMode`,
                     `frictionModeSequence`, `intention`, `sessionLimitMinutes`)
                VALUES
                    ('com.example.v2', 'V2 App', 2000, 'PROCEEDED', 'INTENTION',
                     'INTENTION,TIME_LIMIT', 'Check messages', 10)
                """.trimIndent(),
            )
            database.version = 2
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_NAME = "pause-migration-test.db"
    }
}
