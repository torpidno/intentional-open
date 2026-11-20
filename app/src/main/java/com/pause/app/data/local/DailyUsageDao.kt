package com.pause.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DailyUsageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DailyUsageEntity>)

    @Query("SELECT usedMillis FROM daily_usage WHERE localDate = :localDate AND packageName = :packageName")
    suspend fun getUsedMillis(localDate: String, packageName: String): Long?

    @Query("SELECT * FROM daily_usage WHERE localDate = :localDate")
    fun observeForDate(localDate: String): Flow<List<DailyUsageEntity>>

    @Query("DELETE FROM daily_usage WHERE localDate < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)

    @Query("DELETE FROM daily_usage")
    suspend fun deleteAll()
}
