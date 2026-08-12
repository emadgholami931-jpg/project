package com.vazheyar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(logs: List<ReviewLogEntity>)

    @Query("SELECT * FROM review_logs ORDER BY reviewedAt ASC")
    suspend fun allSnapshot(): List<ReviewLogEntity>

    @Query("DELETE FROM review_logs WHERE cardId = :cardId")
    suspend fun deleteForCard(cardId: Long)

    @Query("DELETE FROM review_logs")
    suspend fun deleteAll()
}
