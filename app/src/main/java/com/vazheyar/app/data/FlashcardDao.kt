package com.vazheyar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashcardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: FlashcardEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<FlashcardEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(cards: List<FlashcardEntity>)

    @Update
    suspend fun update(card: FlashcardEntity)

    @Query("SELECT * FROM flashcards WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): FlashcardEntity?

    @Query("SELECT * FROM flashcards WHERE normalizedWord = :normalized LIMIT 1")
    suspend fun byNormalizedWord(normalized: String): FlashcardEntity?

    @Query("SELECT normalizedWord FROM flashcards")
    suspend fun allNormalizedWords(): List<String>

    @Query("SELECT * FROM flashcards WHERE enrichmentStatus = 'PENDING' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards WHERE enrichmentStatus = 'READY' AND nextReviewAt <= :now ORDER BY nextReviewAt ASC, lapseCount DESC, createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 50): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards ORDER BY createdAt DESC")
    fun all(): Flow<List<FlashcardEntity>>

    @Query("SELECT * FROM flashcards ORDER BY createdAt ASC")
    suspend fun allSnapshot(): List<FlashcardEntity>

    @Query("SELECT COUNT(*) FROM flashcards")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM flashcards WHERE reviewCount > 0")
    fun learnedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM flashcards WHERE enrichmentStatus = 'PENDING'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM flashcards WHERE enrichmentStatus = 'FAILED'")
    fun failedCount(): Flow<Int>

    @Query("UPDATE flashcards SET enrichmentStatus = 'PENDING', enrichmentError = NULL, aiAttemptCount = 0 WHERE enrichmentStatus = 'FAILED'")
    suspend fun retryAllFailed(): Int

    @Query("DELETE FROM flashcards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM flashcards")
    suspend fun deleteAll()

    @Query("""
        UPDATE flashcards SET
            repetition = 0,
            intervalDays = 0,
            easeFactor = 2.35,
            lapseCount = 0,
            nextReviewAt = :now,
            lastReviewedAt = NULL,
            fsrsState = 'LEARNING',
            fsrsStep = 0,
            fsrsDifficulty = 0.0,
            fsrsStability = 0.0,
            scheduledDays = 0,
            reviewCount = 0,
            updatedAt = :now
        WHERE enrichmentStatus = 'READY'
    """)
    suspend fun resetReviewProgress(now: Long): Int
}
