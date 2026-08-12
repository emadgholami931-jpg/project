package com.vazheyar.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "review_logs",
    indices = [Index(value = ["cardId"]), Index(value = ["reviewedAt"])]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val rating: Int,
    val reviewedAt: Long,
    val elapsedDays: Int,
    val scheduledDays: Int,
    val stabilityBefore: Double,
    val difficultyBefore: Double,
    val stabilityAfter: Double,
    val difficultyAfter: Double,
    val stateBefore: String,
    val stateAfter: String
)
