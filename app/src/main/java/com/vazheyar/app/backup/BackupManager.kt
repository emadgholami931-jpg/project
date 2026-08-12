package com.vazheyar.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.vazheyar.app.BuildConfig
import com.vazheyar.app.data.AppDatabase
import com.vazheyar.app.data.EnrichmentStatus
import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.data.FsrsCardState
import com.vazheyar.app.data.ReviewLogEntity
import com.vazheyar.app.data.WordNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

private const val BACKUP_FORMAT_VERSION = 2

data class RestoreResult(
    val cards: Int,
    val reviewLogs: Int,
    val skippedDuplicates: Int
)

object BackupManager {
    suspend fun exportBackup(context: Context, database: AppDatabase, uri: Uri) {
        val cards = database.flashcards().allSnapshot()
        val logs = database.reviewLogs().allSnapshot()

        val root = JSONObject()
            .put("format", "flashcard-backup")
            .put("formatVersion", BACKUP_FORMAT_VERSION)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", System.currentTimeMillis())
            .put("apiKeysIncluded", false)
            .put("cards", JSONArray().also { array -> cards.forEach { array.put(cardToJson(it)) } })
            .put("reviewLogs", JSONArray().also { array -> logs.forEach { array.put(logToJson(it)) } })

        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(root.toString(2))
            }
        } ?: error("Could not open the selected backup file for writing.")
    }

    suspend fun exportCsv(context: Context, database: AppDatabase, uri: Uri) {
        val cards = database.flashcards().allSnapshot()
        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.appendLine("word,ipa,meaningsFa,exampleEn,status,provider,model")
                cards.forEach { card ->
                    val row = listOf(
                        card.word,
                        card.ipa,
                        card.translationFa,
                        card.exampleEn,
                        card.enrichmentStatus,
                        card.enrichmentProvider.orEmpty(),
                        card.enrichmentModel.orEmpty()
                    ).joinToString(",", transform = ::csvCell)
                    writer.appendLine(row)
                }
            }
        } ?: error("Could not open the selected CSV file for writing.")
    }

    suspend fun restoreBackup(
        context: Context,
        database: AppDatabase,
        uri: Uri
    ): RestoreResult {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Could not read the selected backup file.")
        val root = JSONObject(text)
        require(root.optString("format") == "flashcard-backup") { "This is not a flashcard backup file." }
        val version = root.optInt("formatVersion", 1)
        require(version in 1..BACKUP_FORMAT_VERSION) { "Backup format version $version is newer than this app supports." }

        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        val parsedCards = buildList {
            for (i in 0 until cardsJson.length()) add(jsonToCard(cardsJson.getJSONObject(i)))
        }
        var nextCardId = (parsedCards.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        val usedCardIds = hashSetOf<Long>()
        val oldToNewCardId = hashMapOf<Long, Long>()
        val unique = linkedMapOf<String, FlashcardEntity>()
        var duplicates = 0

        parsedCards.forEach { parsed ->
            val normalized = WordNormalizer.normalize(parsed.word)
            if (normalized.isBlank()) return@forEach

            val existing = unique[normalized]
            if (existing != null) {
                duplicates++
                if (parsed.id > 0) oldToNewCardId[parsed.id] = existing.id
                return@forEach
            }

            val restoredId = if (parsed.id > 0 && usedCardIds.add(parsed.id)) {
                parsed.id
            } else {
                while (!usedCardIds.add(nextCardId)) nextCardId++
                nextCardId++
                nextCardId - 1L
            }
            if (parsed.id > 0) oldToNewCardId[parsed.id] = restoredId
            unique[normalized] = parsed.copy(id = restoredId, normalizedWord = normalized)
        }

        val cards = unique.values.toList()
        val validIds = cards.mapTo(hashSetOf()) { it.id }

        val logsJson = root.optJSONArray("reviewLogs") ?: JSONArray()
        val parsedLogs = buildList {
            for (i in 0 until logsJson.length()) add(jsonToLog(logsJson.getJSONObject(i)))
        }
        var nextLogId = (parsedLogs.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        val usedLogIds = hashSetOf<Long>()
        val logs = buildList {
            parsedLogs.forEach { rawLog ->
                val mappedCardId = oldToNewCardId[rawLog.cardId] ?: rawLog.cardId
                if (mappedCardId !in validIds) return@forEach
                val restoredLogId = if (rawLog.id > 0 && usedLogIds.add(rawLog.id)) {
                    rawLog.id
                } else {
                    while (!usedLogIds.add(nextLogId)) nextLogId++
                    nextLogId++
                    nextLogId - 1L
                }
                add(rawLog.copy(id = restoredLogId, cardId = mappedCardId))
            }
        }

        database.withTransaction {
            database.reviewLogs().deleteAll()
            database.flashcards().deleteAll()
            if (cards.isNotEmpty()) database.flashcards().restoreAll(cards)
            if (logs.isNotEmpty()) database.reviewLogs().restoreAll(logs)
        }
        return RestoreResult(cards.size, logs.size, duplicates)
    }

    private fun cardToJson(c: FlashcardEntity) = JSONObject()
        .put("id", c.id)
        .put("word", c.word)
        .put("normalizedWord", c.normalizedWord)
        .put("ipa", c.ipa)
        .put("translationFa", c.translationFa)
        .put("exampleEn", c.exampleEn)
        .put("exampleFa", c.exampleFa)
        .put("enrichmentStatus", c.enrichmentStatus)
        .putNullable("enrichmentError", c.enrichmentError)
        .put("repetition", c.repetition)
        .put("intervalDays", c.intervalDays)
        .put("easeFactor", c.easeFactor)
        .put("lapseCount", c.lapseCount)
        .put("nextReviewAt", c.nextReviewAt)
        .putNullable("lastReviewedAt", c.lastReviewedAt)
        .put("createdAt", c.createdAt)
        .put("fsrsState", c.fsrsState)
        .put("fsrsStep", c.fsrsStep)
        .put("fsrsDifficulty", c.fsrsDifficulty)
        .put("fsrsStability", c.fsrsStability)
        .put("scheduledDays", c.scheduledDays)
        .put("reviewCount", c.reviewCount)
        .putNullable("enrichmentProvider", c.enrichmentProvider)
        .putNullable("enrichmentModel", c.enrichmentModel)
        .put("aiAttemptCount", c.aiAttemptCount)
        .putNullable("lastAiAttemptAt", c.lastAiAttemptAt)
        .put("updatedAt", c.updatedAt)

    private fun jsonToCard(o: JSONObject): FlashcardEntity {
        val now = System.currentTimeMillis()
        return FlashcardEntity(
            id = o.optLong("id", 0L),
            word = o.optString("word").trim(),
            normalizedWord = o.optString("normalizedWord", WordNormalizer.normalize(o.optString("word"))),
            ipa = o.optString("ipa"),
            translationFa = o.optString("translationFa"),
            exampleEn = o.optString("exampleEn"),
            exampleFa = o.optString("exampleFa"),
            enrichmentStatus = o.optString("enrichmentStatus", EnrichmentStatus.PENDING.name),
            enrichmentError = o.optNullableString("enrichmentError"),
            repetition = o.optInt("repetition", 0),
            intervalDays = o.optInt("intervalDays", 0),
            easeFactor = o.optDouble("easeFactor", 2.35),
            lapseCount = o.optInt("lapseCount", 0),
            nextReviewAt = o.optLong("nextReviewAt", now),
            lastReviewedAt = o.optNullableLong("lastReviewedAt"),
            createdAt = o.optLong("createdAt", now),
            fsrsState = o.optString("fsrsState", FsrsCardState.LEARNING.name),
            fsrsStep = o.optInt("fsrsStep", 0),
            fsrsDifficulty = o.optDouble("fsrsDifficulty", 0.0),
            fsrsStability = o.optDouble("fsrsStability", 0.0),
            scheduledDays = o.optInt("scheduledDays", o.optInt("intervalDays", 0)),
            reviewCount = o.optInt("reviewCount", o.optInt("repetition", 0)),
            enrichmentProvider = o.optNullableString("enrichmentProvider"),
            enrichmentModel = o.optNullableString("enrichmentModel"),
            aiAttemptCount = o.optInt("aiAttemptCount", 0),
            lastAiAttemptAt = o.optNullableLong("lastAiAttemptAt"),
            updatedAt = o.optLong("updatedAt", o.optLong("createdAt", now))
        )
    }

    private fun logToJson(log: ReviewLogEntity) = JSONObject()
        .put("id", log.id)
        .put("cardId", log.cardId)
        .put("rating", log.rating)
        .put("reviewedAt", log.reviewedAt)
        .put("elapsedDays", log.elapsedDays)
        .put("scheduledDays", log.scheduledDays)
        .put("stabilityBefore", log.stabilityBefore)
        .put("difficultyBefore", log.difficultyBefore)
        .put("stabilityAfter", log.stabilityAfter)
        .put("difficultyAfter", log.difficultyAfter)
        .put("stateBefore", log.stateBefore)
        .put("stateAfter", log.stateAfter)

    private fun jsonToLog(o: JSONObject) = ReviewLogEntity(
        id = o.optLong("id", 0L),
        cardId = o.getLong("cardId"),
        rating = o.optInt("rating", 3).coerceIn(1, 4),
        reviewedAt = o.optLong("reviewedAt", System.currentTimeMillis()),
        elapsedDays = o.optInt("elapsedDays", 0),
        scheduledDays = o.optInt("scheduledDays", 0),
        stabilityBefore = o.optDouble("stabilityBefore", 0.0),
        difficultyBefore = o.optDouble("difficultyBefore", 0.0),
        stabilityAfter = o.optDouble("stabilityAfter", 0.0),
        difficultyAfter = o.optDouble("difficultyAfter", 0.0),
        stateBefore = o.optString("stateBefore", FsrsCardState.LEARNING.name),
        stateAfter = o.optString("stateAfter", FsrsCardState.LEARNING.name)
    )

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
