package com.vazheyar.app.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vazheyar.app.data.AppDatabase
import com.vazheyar.app.data.EnrichmentStatus
import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.data.WordNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class EnrichmentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(applicationContext).flashcards()
        val pending = dao.pending(limit = 20)
        if (pending.isEmpty()) return@withContext Result.success()
        if (!AiEnrichmentClient.hasAvailableProvider(applicationContext)) {
            return@withContext Result.success(
                workDataOf("message" to "No usable AI provider is configured.")
            )
        }

        val attemptTime = System.currentTimeMillis()
        pending.forEach { card ->
            dao.update(
                card.copy(
                    aiAttemptCount = card.aiAttemptCount + 1,
                    lastAiAttemptAt = attemptTime,
                    updatedAt = attemptTime
                )
            )
        }

        setProgress(workDataOf("batchSize" to pending.size, "phase" to "requesting"))

        try {
            val enriched = AiEnrichmentClient.enrich(applicationContext, pending.map { it.word })
            val byWord = enriched.associateBy { WordNormalizer.normalize(it.word) }

            pending.forEachIndexed { index, original ->
                val card = dao.byId(original.id) ?: return@forEachIndexed
                val item = byWord[card.normalizedWord]
                if (item != null) {
                    dao.update(
                        card.copy(
                            ipa = item.ipa,
                            translationFa = item.meaningsFa.joinToString("\n") { "• $it" },
                            exampleEn = item.exampleEn,
                            exampleFa = "",
                            enrichmentStatus = EnrichmentStatus.READY.name,
                            enrichmentError = null,
                            enrichmentProvider = item.provider,
                            enrichmentModel = item.model,
                            nextReviewAt = if (card.reviewCount == 0) System.currentTimeMillis() else card.nextReviewAt,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    dao.update(
                        card.copy(
                            enrichmentStatus = EnrichmentStatus.FAILED.name,
                            enrichmentError = "No AI result was returned for this word.",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                setProgress(workDataOf("batchSize" to pending.size, "completed" to index + 1, "phase" to "saving"))
            }

            if (dao.pending(limit = 1).isNotEmpty()) {
                EnrichmentScheduler.enqueue(applicationContext)
            }
            Result.success()
        } catch (e: AiEnrichmentException) {
            handleFailure(pending, e.retryable, e.message ?: "AI enrichment failed.")
        } catch (e: SocketTimeoutException) {
            handleFailure(pending, retryable = true, message = "The AI request timed out.")
        } catch (e: IOException) {
            handleFailure(pending, retryable = true, message = e.message ?: "Network error while contacting the AI provider.")
        } catch (t: Throwable) {
            handleFailure(pending, retryable = false, message = t.message ?: "Unexpected enrichment error.")
        }
    }

    private suspend fun handleFailure(
        cards: List<FlashcardEntity>,
        retryable: Boolean,
        message: String
    ): Result {
        return if (retryable && runAttemptCount < 4) {
            Result.retry()
        } else {
            val dao = AppDatabase.get(applicationContext).flashcards()
            cards.forEach { original ->
                val card = dao.byId(original.id) ?: return@forEach
                dao.update(
                    card.copy(
                        enrichmentStatus = EnrichmentStatus.FAILED.name,
                        enrichmentError = message.take(700),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.failure(Data.Builder().putString("message", message.take(700)).build())
        }
    }
}

object EnrichmentScheduler {
    private const val UNIQUE_WORK = "flashcard-enrichment"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<EnrichmentWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
