package com.pause.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Exercises the real SQLite upgrade path, not just the entity model.
 *
 * Room has no exported v1 schema in this project, so the test creates the v1
 * table exactly as Room 1 generated it, then opens the production database
 * with the 1→2 migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PauseDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        createVersionOneDatabase()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `migration 1 to 2 preserves history and adds the sequence column`() = runTest {
        val database = Room.databaseBuilder(
            context,
            PauseDatabase::class.java,
            DB_NAME,
        )
            .addMigrations(PauseDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

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
        } finally {
            database.close()
        }
    }

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

    private companion object {
        const val DB_NAME = "pause-migration-test.db"
    }
}
