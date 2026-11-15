package com.pause.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [InterventionLogEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PauseDatabase : RoomDatabase() {
    abstract fun interventionLogDao(): InterventionLogDao

    companion object {
        /** Adds the full friction sequence without discarding existing history. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE intervention_logs ADD COLUMN frictionModeSequence TEXT",
                )
            }
        }
    }
}
