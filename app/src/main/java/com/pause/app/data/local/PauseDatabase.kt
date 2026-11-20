package com.pause.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [InterventionLogEntity::class, DailyUsageEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PauseDatabase : RoomDatabase() {
    abstract fun interventionLogDao(): InterventionLogDao
    abstract fun dailyUsageDao(): DailyUsageDao

    companion object {
        /** Adds the full friction sequence without discarding existing history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE intervention_logs ADD COLUMN frictionModeSequence TEXT",
                )
            }
        }

        /** Adds local daily usage snapshots without discarding existing history. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_usage` (
                        `localDate` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `usedMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`localDate`, `packageName`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
