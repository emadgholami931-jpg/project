package com.vazheyar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "flashcards",
    indices = [
        Index(value = ["normalizedWord"], unique = true),
        Index(value = ["nextReviewAt"]),
        Index(value = ["enrichmentStatus"])
    ]
)
data class FlashcardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val normalizedWord: String,
    val ipa: String = "",
    val translationFa: String = "",
    val exampleEn: String = "",
    val exampleFa: String = "",
    val enrichmentStatus: String = EnrichmentStatus.PENDING.name,
    val enrichmentError: String? = null,

    // Legacy scheduling fields are intentionally retained so version-1 databases
    // can be migrated without data loss. FSRS fields below are the source of truth.
    val repetition: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.35,
    val lapseCount: Int = 0,
    val nextReviewAt: Long = System.currentTimeMillis(),
    val lastReviewedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),

    // FSRS-6 state.
    @ColumnInfo(defaultValue = "'LEARNING'") val fsrsState: String = FsrsCardState.LEARNING.name,
    @ColumnInfo(defaultValue = "0") val fsrsStep: Int = 0,
    @ColumnInfo(defaultValue = "0.0") val fsrsDifficulty: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val fsrsStability: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val scheduledDays: Int = 0,
    @ColumnInfo(defaultValue = "0") val reviewCount: Int = 0,

    // AI diagnostics.
    val enrichmentProvider: String? = null,
    val enrichmentModel: String? = null,
    @ColumnInfo(defaultValue = "0") val aiAttemptCount: Int = 0,
    val lastAiAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
)

enum class EnrichmentStatus { PENDING, READY, FAILED }
enum class FsrsCardState { LEARNING, REVIEW, RELEARNING }
