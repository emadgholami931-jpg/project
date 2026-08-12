package com.vazheyar.app.review

import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.data.FsrsCardState
import com.vazheyar.app.data.ReviewLogEntity
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

enum class ReviewRating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4)
}

data class FsrsReviewResult(
    val card: FlashcardEntity,
    val log: ReviewLogEntity,
    val intervalLabel: String
)

/**
 * FSRS-6 scheduler using the public default 21-parameter model.
 * Desired retention is 90%, with 1m/10m learning steps and a 10m relearning step.
 */
object FsrsScheduler {
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L
    private const val DESIRED_RETENTION = 0.90
    private const val MAX_INTERVAL_DAYS = 36_500
    private const val MIN_STABILITY = 0.001

    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
        0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
        1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    )

    private val decay = -w[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0
    private val learningSteps = longArrayOf(1 * MINUTE_MS, 10 * MINUTE_MS)
    private val relearningSteps = longArrayOf(10 * MINUTE_MS)

    fun review(
        card: FlashcardEntity,
        rating: ReviewRating,
        now: Long = System.currentTimeMillis()
    ): FsrsReviewResult = schedule(card, rating, now, enableFuzzing = true)

    fun preview(
        card: FlashcardEntity,
        rating: ReviewRating,
        now: Long = System.currentTimeMillis()
    ): String = schedule(card, rating, now, enableFuzzing = false).intervalLabel

    private fun schedule(
        source: FlashcardEntity,
        rating: ReviewRating,
        now: Long,
        enableFuzzing: Boolean
    ): FsrsReviewResult {
        val stateBefore = runCatching { FsrsCardState.valueOf(source.fsrsState) }
            .getOrDefault(FsrsCardState.LEARNING)
        val elapsedDays = source.lastReviewedAt
            ?.let { max(0L, now - it) / DAY_MS }
            ?.toInt()
            ?: 0

        var stability = source.fsrsStability
        var difficulty = source.fsrsDifficulty
        var state = stateBefore
        var step = source.fsrsStep.coerceAtLeast(0)

        val hasMemoryState = stability > 0.0 && difficulty > 0.0
        if (!hasMemoryState) {
            stability = initialStability(rating)
            difficulty = initialDifficulty(rating, clamp = true)
        } else if (elapsedDays < 1) {
            stability = shortTermStability(stability, rating)
            difficulty = nextDifficulty(difficulty, rating)
        } else {
            val retrievability = retrievability(elapsedDays.toDouble(), stability)
            stability = nextStability(difficulty, stability, retrievability, rating)
            difficulty = nextDifficulty(difficulty, rating)
        }

        var delayMs: Long
        var scheduledDays = 0

        when (stateBefore) {
            FsrsCardState.LEARNING -> {
                when (rating) {
                    ReviewRating.AGAIN -> {
                        state = FsrsCardState.LEARNING
                        step = 0
                        delayMs = learningSteps[0]
                    }
                    ReviewRating.HARD -> {
                        state = FsrsCardState.LEARNING
                        step = min(step, learningSteps.lastIndex)
                        delayMs = when {
                            step == 0 && learningSteps.size == 1 -> (learningSteps[0] * 1.5).toLong()
                            step == 0 && learningSteps.size >= 2 -> (learningSteps[0] + learningSteps[1]) / 2
                            else -> learningSteps[step]
                        }
                    }
                    ReviewRating.GOOD -> {
                        if (step + 1 >= learningSteps.size) {
                            state = FsrsCardState.REVIEW
                            step = 0
                            scheduledDays = nextInterval(stability, enableFuzzing)
                            delayMs = scheduledDays * DAY_MS
                        } else {
                            state = FsrsCardState.LEARNING
                            step += 1
                            delayMs = learningSteps[step]
                        }
                    }
                    ReviewRating.EASY -> {
                        state = FsrsCardState.REVIEW
                        step = 0
                        scheduledDays = nextInterval(stability, enableFuzzing)
                        delayMs = scheduledDays * DAY_MS
                    }
                }
            }

            FsrsCardState.REVIEW -> {
                if (rating == ReviewRating.AGAIN) {
                    state = FsrsCardState.RELEARNING
                    step = 0
                    delayMs = relearningSteps[0]
                } else {
                    state = FsrsCardState.REVIEW
                    step = 0
                    scheduledDays = nextInterval(stability, enableFuzzing)
                    delayMs = scheduledDays * DAY_MS
                }
            }

            FsrsCardState.RELEARNING -> {
                when (rating) {
                    ReviewRating.AGAIN -> {
                        state = FsrsCardState.RELEARNING
                        step = 0
                        delayMs = relearningSteps[0]
                    }
                    ReviewRating.HARD -> {
                        state = FsrsCardState.RELEARNING
                        step = min(step, relearningSteps.lastIndex)
                        delayMs = if (relearningSteps.size == 1) {
                            (relearningSteps[0] * 1.5).toLong()
                        } else {
                            relearningSteps[step]
                        }
                    }
                    ReviewRating.GOOD -> {
                        if (step + 1 >= relearningSteps.size) {
                            state = FsrsCardState.REVIEW
                            step = 0
                            scheduledDays = nextInterval(stability, enableFuzzing)
                            delayMs = scheduledDays * DAY_MS
                        } else {
                            step += 1
                            delayMs = relearningSteps[step]
                        }
                    }
                    ReviewRating.EASY -> {
                        state = FsrsCardState.REVIEW
                        step = 0
                        scheduledDays = nextInterval(stability, enableFuzzing)
                        delayMs = scheduledDays * DAY_MS
                    }
                }
            }
        }

        val nextReviewAt = now + delayMs
        val updated = source.copy(
            repetition = if (rating == ReviewRating.AGAIN) 0 else source.repetition + 1,
            intervalDays = scheduledDays,
            lapseCount = source.lapseCount + if (rating == ReviewRating.AGAIN) 1 else 0,
            nextReviewAt = nextReviewAt,
            lastReviewedAt = now,
            fsrsState = state.name,
            fsrsStep = step,
            fsrsDifficulty = difficulty,
            fsrsStability = stability,
            scheduledDays = scheduledDays,
            reviewCount = source.reviewCount + 1,
            updatedAt = now
        )

        val log = ReviewLogEntity(
            cardId = source.id,
            rating = rating.value,
            reviewedAt = now,
            elapsedDays = elapsedDays,
            scheduledDays = scheduledDays,
            stabilityBefore = source.fsrsStability,
            difficultyBefore = source.fsrsDifficulty,
            stabilityAfter = stability,
            difficultyAfter = difficulty,
            stateBefore = stateBefore.name,
            stateAfter = state.name
        )

        return FsrsReviewResult(updated, log, formatInterval(delayMs))
    }

    private fun initialStability(rating: ReviewRating): Double =
        max(w[rating.value - 1], MIN_STABILITY)

    private fun initialDifficulty(rating: ReviewRating, clamp: Boolean): Double {
        val raw = w[4] - exp(w[5] * (rating.value - 1)) + 1.0
        return if (clamp) raw.coerceIn(1.0, 10.0) else raw
    }

    private fun nextDifficulty(current: Double, rating: ReviewRating): Double {
        val delta = -w[6] * (rating.value - 3)
        val damped = (10.0 - current) * delta / 9.0
        val target = initialDifficulty(ReviewRating.EASY, clamp = false)
        return (w[7] * target + (1.0 - w[7]) * (current + damped)).coerceIn(1.0, 10.0)
    }

    private fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1.0 + factor * elapsedDays / max(stability, MIN_STABILITY)).pow(decay)

    private fun shortTermStability(stability: Double, rating: ReviewRating): Double {
        var increase = exp(w[17] * (rating.value - 3 + w[18])) * stability.pow(-w[19])
        if (rating != ReviewRating.AGAIN) increase = max(increase, 1.0)
        return max(stability * increase, MIN_STABILITY)
    }

    private fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: ReviewRating
    ): Double = if (rating == ReviewRating.AGAIN) {
        val longTerm = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp((1.0 - retrievability) * w[14])
        val shortTermCeiling = stability / exp(w[17] * w[18])
        max(min(longTerm, shortTermCeiling), MIN_STABILITY)
    } else {
        val hardPenalty = if (rating == ReviewRating.HARD) w[15] else 1.0
        val easyBonus = if (rating == ReviewRating.EASY) w[16] else 1.0
        max(
            stability * (
                1.0 + exp(w[8]) *
                    (11.0 - difficulty) *
                    stability.pow(-w[9]) *
                    (exp((1.0 - retrievability) * w[10]) - 1.0) *
                    hardPenalty * easyBonus
                ),
            MIN_STABILITY
        )
    }

    private fun nextInterval(stability: Double, fuzz: Boolean): Int {
        val raw = (stability / factor) * (DESIRED_RETENTION.pow(1.0 / decay) - 1.0)
        var days = raw.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
        if (fuzz && days >= 3) days = fuzz(days)
        return days.coerceIn(1, MAX_INTERVAL_DAYS)
    }

    private fun fuzz(days: Int): Int {
        var delta = 1.0
        fun add(start: Double, end: Double, multiplier: Double) {
            delta += multiplier * max(min(days.toDouble(), end) - start, 0.0)
        }
        add(2.5, 7.0, 0.15)
        add(7.0, 20.0, 0.10)
        add(20.0, Double.POSITIVE_INFINITY, 0.05)

        val minDays = max(2, (days - delta).roundToInt())
        val maxDays = min(MAX_INTERVAL_DAYS, (days + delta).roundToInt())
        return Random.nextInt(minDays, maxDays + 1)
    }

    private fun formatInterval(ms: Long): String = when {
        ms < 60 * MINUTE_MS -> {
            val minutes = max(1, (ms / MINUTE_MS).toInt())
            "${minutes}m"
        }
        ms < DAY_MS -> "${max(1, (ms / (60 * MINUTE_MS)).toInt())}h"
        ms < 30 * DAY_MS -> "${ms / DAY_MS}d"
        ms < 365 * DAY_MS -> "${(ms / DAY_MS) / 30}mo"
        else -> "${(ms / DAY_MS) / 365}y"
    }
}
