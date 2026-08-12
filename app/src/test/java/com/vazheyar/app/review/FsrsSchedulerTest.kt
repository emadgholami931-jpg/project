package com.vazheyar.app.review

import com.vazheyar.app.data.EnrichmentStatus
import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.data.FsrsCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {
    private val now = 1_700_000_000_000L

    private fun newCard() = FlashcardEntity(
        id = 1,
        word = "skill",
        normalizedWord = "skill",
        enrichmentStatus = EnrichmentStatus.READY.name,
        nextReviewAt = now
    )

    @Test
    fun goodMovesThroughLearningSteps() {
        val first = FsrsScheduler.review(newCard(), ReviewRating.GOOD, now).card
        assertEquals(FsrsCardState.LEARNING.name, first.fsrsState)
        assertEquals(1, first.fsrsStep)
        assertTrue(first.nextReviewAt > now)

        val secondNow = first.nextReviewAt
        val second = FsrsScheduler.review(first, ReviewRating.GOOD, secondNow).card
        assertEquals(FsrsCardState.REVIEW.name, second.fsrsState)
        assertTrue(second.scheduledDays >= 1)
    }

    @Test
    fun easyGraduatesImmediately() {
        val result = FsrsScheduler.review(newCard(), ReviewRating.EASY, now).card
        assertEquals(FsrsCardState.REVIEW.name, result.fsrsState)
        assertTrue(result.scheduledDays >= 1)
    }

    @Test
    fun againFromReviewEntersRelearning() {
        val reviewCard = newCard().copy(
            fsrsState = FsrsCardState.REVIEW.name,
            fsrsDifficulty = 5.0,
            fsrsStability = 10.0,
            lastReviewedAt = now - 10L * 86_400_000L,
            scheduledDays = 10
        )
        val result = FsrsScheduler.review(reviewCard, ReviewRating.AGAIN, now).card
        assertEquals(FsrsCardState.RELEARNING.name, result.fsrsState)
        assertEquals(0, result.scheduledDays)
        assertTrue(result.lapseCount > reviewCard.lapseCount)
    }
}
