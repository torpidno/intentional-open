package com.pause.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pause.app.domain.model.InterventionOutcome
import kotlinx.coroutines.flow.Flow

@Dao
interface InterventionLogDao {

    @Insert
    suspend fun insert(log: InterventionLogEntity): Long

    /** Finishes a previously inserted PENDING attempt. */
    @Query(
        """
        UPDATE intervention_logs
        SET outcome = :outcome, intention = :intention, sessionLimitMinutes = :sessionLimitMinutes
        WHERE id = :id
        """,
    )
    suspend fun complete(
        id: Long,
        outcome: InterventionOutcome,
        intention: String?,
        sessionLimitMinutes: Int?,
    )

    /** Closes attempts whose pause surface disappeared with the process. */
    @Query("UPDATE intervention_logs SET outcome = :outcome WHERE outcome = 'PENDING'")
    suspend fun completeAllPending(outcome: InterventionOutcome)

    /** Closes attempts recorded before [beforeMillis] whose pause surface disappeared with the process. */
    @Query(
        "UPDATE intervention_logs SET outcome = :outcome WHERE outcome = 'PENDING' AND timestampMillis < :beforeMillis",
    )
    suspend fun completeAllPendingBefore(outcome: InterventionOutcome, beforeMillis: Long)

    @Query(
        """
        SELECT COUNT(*) FROM intervention_logs
        WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis
        """,
    )
    fun observeAttemptCount(startMillis: Long, endMillis: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM intervention_logs
        WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis
          AND outcome = 'WALKED_AWAY'
        """,
    )
    fun observeWalkedAwayCount(startMillis: Long, endMillis: Long): Flow<Int>

    @Query(
        """
        SELECT packageName, appLabel, COUNT(*) AS count FROM intervention_logs
        WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis
        GROUP BY packageName
        ORDER BY count DESC, appLabel ASC
        LIMIT 1
        """,
    )
    fun observeMostTriggered(startMillis: Long, endMillis: Long): Flow<AppTriggerCount?>

    @Query(
        """
        SELECT * FROM intervention_logs
        WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis
        ORDER BY timestampMillis ASC
        """,
    )
    fun observeInRange(startMillis: Long, endMillis: Long): Flow<List<InterventionLogEntity>>

    @Query(
        """
        SELECT * FROM intervention_logs
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<InterventionLogEntity>>

    @Query("DELETE FROM intervention_logs WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("SELECT * FROM intervention_logs ORDER BY timestampMillis ASC, id ASC")
    suspend fun getAll(): List<InterventionLogEntity>

    @Insert
    suspend fun insertAll(logs: List<InterventionLogEntity>): List<Long>

    @Query("DELETE FROM intervention_logs")
    suspend fun deleteAll()
}
