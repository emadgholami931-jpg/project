package com.vazheyar.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.vazheyar.app.ai.AiEnrichmentClient
import com.vazheyar.app.ai.AiProviderMode
import com.vazheyar.app.ai.AiProviderSettings
import com.vazheyar.app.ai.EnrichmentScheduler
import com.vazheyar.app.ai.GeminiApiKeyStore
import com.vazheyar.app.ai.GroqApiKeyStore
import com.vazheyar.app.backup.BackupManager
import com.vazheyar.app.data.AppDatabase
import com.vazheyar.app.data.CsvWordParser
import com.vazheyar.app.data.EnrichmentStatus
import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.data.ImportedWord
import com.vazheyar.app.data.WordNormalizer
import com.vazheyar.app.review.FsrsScheduler
import com.vazheyar.app.review.ReviewRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

data class ImportProgress(
    val active: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val readyFromCsv: Int = 0,
    val aiTotal: Int = 0,
    val aiCompleted: Int = 0,
    val aiFailed: Int = 0,
    val aiGeminiCompleted: Int = 0,
    val aiGroqCompleted: Int = 0
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database: AppDatabase = (application as VazheYarApp).database
    private val dao = database.flashcards()
    private val reviewLogs = database.reviewLogs()

    val allCards = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totalCount = dao.totalCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val learnedCount = dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val pendingCount = dao.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val failedCount = dao.failedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _dueCards = MutableStateFlow<List<FlashcardEntity>>(emptyList())
    val dueCards: StateFlow<List<FlashcardEntity>> = _dueCards

    private val _dueCount = MutableStateFlow(0)
    val dueCount: StateFlow<Int> = _dueCount

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress

    private val _geminiApiKeyConfigured = MutableStateFlow(GeminiApiKeyStore.hasKey(application))
    val geminiApiKeyConfigured: StateFlow<Boolean> = _geminiApiKeyConfigured

    private val _groqApiKeyConfigured = MutableStateFlow(GroqApiKeyStore.hasKey(application))
    val groqApiKeyConfigured: StateFlow<Boolean> = _groqApiKeyConfigured

    private val _providerMode = MutableStateFlow(AiProviderSettings.load(application))
    val providerMode: StateFlow<AiProviderMode> = _providerMode

    private val _aiReady = MutableStateFlow(AiEnrichmentClient.hasAvailableProvider(application))
    val aiReady: StateFlow<Boolean> = _aiReady

    // IDs from the most recent CSV import that still needed AI. This lets the UI
    // show enrichment progress without adding import-specific columns to the database.
    private var lastImportAiIds: Set<Long> = emptySet()

    init {
        viewModelScope.launch {
            dao.all().collect { cards ->
                val now = System.currentTimeMillis()
                val due = cards.asSequence()
                    .filter { it.enrichmentStatus == EnrichmentStatus.READY.name && it.nextReviewAt <= now }
                    .sortedWith(
                        compareBy<FlashcardEntity> { it.nextReviewAt }
                            .thenByDescending { it.lapseCount }
                            .thenBy { it.createdAt }
                    )
                    .toList()
                _dueCount.value = due.size
                _dueCards.value = due.take(50)

                if (lastImportAiIds.isNotEmpty()) {
                    val tracked = cards.filter { it.id in lastImportAiIds }
                    val failedAi = tracked.count { it.enrichmentStatus == EnrichmentStatus.FAILED.name }
                    val readyAi = tracked.count { it.enrichmentStatus == EnrichmentStatus.READY.name }
                    val geminiAi = tracked.count { it.enrichmentStatus == EnrichmentStatus.READY.name && it.enrichmentProvider == "Gemini" }
                    val groqAi = tracked.count { it.enrichmentStatus == EnrichmentStatus.READY.name && it.enrichmentProvider == "Groq" }
                    val current = _importProgress.value
                    _importProgress.value = current.copy(
                        aiTotal = lastImportAiIds.size,
                        aiCompleted = readyAi + failedAi,
                        aiFailed = failedAi,
                        aiGeminiCompleted = geminiAi,
                        aiGroqCompleted = groqAi
                    )
                }
            }
        }

        // A card can become due while the app stays open without any database write.
        // Refresh once per minute so the Home due count does not become stale.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                refreshDue()
            }
        }
    }

    fun clearMessage() { _message.value = null }

    fun refreshDue() {
        viewModelScope.launch {
            val due = dao.due(System.currentTimeMillis(), limit = 10_000)
            _dueCount.value = due.size
            _dueCards.value = due.take(50)
        }
    }

    fun setAiProviderMode(mode: AiProviderMode) {
        AiProviderSettings.save(getApplication(), mode)
        refreshAiState()
        _message.value = when (mode) {
            AiProviderMode.AUTO -> "AI mode set to Auto. Gemini is tried first, with Groq as fallback."
            AiProviderMode.GEMINI -> "AI mode set to Gemini only."
            AiProviderMode.GROQ -> "AI mode set to Groq only."
        }
        retryFailedIfReady()
    }

    fun saveGeminiApiKey(raw: String) = saveApiKey(
        raw = raw,
        label = "Gemini",
        save = { GeminiApiKeyStore.save(getApplication(), it) }
    )

    fun saveGroqApiKey(raw: String) = saveApiKey(
        raw = raw,
        label = "Groq",
        save = { GroqApiKeyStore.save(getApplication(), it) }
    )

    private fun saveApiKey(raw: String, label: String, save: (String) -> Unit) {
        val key = raw.trim()
        if (key.isBlank()) {
            _message.value = "$label API key cannot be empty."
            return
        }
        runCatching { save(key) }
            .onSuccess {
                refreshAiState()
                _message.value = "$label API key was saved securely on this device."
                retryFailedIfReady()
            }
            .onFailure { _message.value = "Could not save the $label API key: ${it.message ?: "Unknown error"}" }
    }

    fun clearGeminiApiKey() {
        GeminiApiKeyStore.clear(getApplication())
        refreshAiState()
        _message.value = "Gemini API key was removed from this device."
    }

    fun clearGroqApiKey() {
        GroqApiKeyStore.clear(getApplication())
        refreshAiState()
        _message.value = "Groq API key was removed from this device."
    }

    fun addWord(raw: String) {
        val word = WordNormalizer.display(raw)
        val normalized = WordNormalizer.normalize(word)
        if (normalized.isBlank()) {
            _message.value = "Enter a valid English word."
            return
        }

        viewModelScope.launch {
            if (dao.byNormalizedWord(normalized) != null) {
                _message.value = "This word is already in your library."
                return@launch
            }
            val inserted = dao.insert(newCard(ImportedWord(word)))
            if (inserted <= 0) {
                _message.value = "This word is already in your library."
            } else if (AiEnrichmentClient.hasAvailableProvider(getApplication())) {
                _message.value = "\"$word\" was added. AI is creating the flashcard."
                EnrichmentScheduler.enqueue(getApplication())
            } else {
                _message.value = "\"$word\" was added. Configure Gemini or Groq in Settings to complete it."
            }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(active = true)
            val rows = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        InputStreamReader(input, Charsets.UTF_8).use { CsvWordParser.parse(it) }
                    }.orEmpty()
                }
            }.getOrElse {
                _importProgress.value = ImportProgress()
                _message.value = "Could not read the CSV file: ${it.message ?: "Unknown error"}"
                return@launch
            }

            if (rows.isEmpty()) {
                _importProgress.value = ImportProgress()
                _message.value = "No valid words were found in the CSV file."
                return@launch
            }

            val existing = dao.allNormalizedWords().toMutableSet()
            val fresh = mutableListOf<ImportedWord>()
            var duplicates = 0
            rows.forEach { row ->
                val normalized = WordNormalizer.normalize(row.word)
                if (normalized.isBlank() || !existing.add(normalized)) duplicates++ else fresh += row
            }

            var processedNew = 0
            var added = 0
            var readyFromCsv = 0
            val aiIds = linkedSetOf<Long>()
            fresh.chunked(100).forEach { chunk ->
                val cards = chunk.map(::newCard)
                val ids = dao.insertAll(cards)
                ids.forEachIndexed { index, id ->
                    if (id > 0) {
                        added++
                        if (cards[index].enrichmentStatus == EnrichmentStatus.READY.name) {
                            readyFromCsv++
                        } else {
                            aiIds += id
                        }
                    } else {
                        duplicates++
                    }
                }
                processedNew += chunk.size
                _importProgress.value = ImportProgress(
                    active = true,
                    total = rows.size,
                    processed = (processedNew + duplicates).coerceAtMost(rows.size),
                    added = added,
                    duplicates = duplicates,
                    readyFromCsv = readyFromCsv,
                    aiTotal = aiIds.size
                )
            }

            lastImportAiIds = aiIds
            _importProgress.value = ImportProgress(
                active = false,
                total = rows.size,
                processed = rows.size,
                added = added,
                duplicates = duplicates,
                readyFromCsv = readyFromCsv,
                aiTotal = aiIds.size
            )

            val pendingImported = added - readyFromCsv
            if (pendingImported > 0 && AiEnrichmentClient.hasAvailableProvider(getApplication())) {
                EnrichmentScheduler.enqueue(getApplication())
            }
            _message.value = buildString {
                append("Imported $added new word")
                if (added != 1) append("s")
                append(". Skipped $duplicates duplicate")
                if (duplicates != 1) append("s")
                append(".")
                if (pendingImported > 0 && !AiEnrichmentClient.hasAvailableProvider(getApplication())) {
                    append(" $pendingImported card(s) are waiting for an AI provider.")
                }
            }
        }
    }

    fun editCard(
        card: FlashcardEntity,
        wordRaw: String,
        ipa: String,
        meaningsFa: String,
        exampleEn: String
    ) {
        viewModelScope.launch {
            val word = WordNormalizer.display(wordRaw)
            val normalized = WordNormalizer.normalize(word)
            if (normalized.isBlank()) {
                _message.value = "Word cannot be empty."
                return@launch
            }
            val duplicate = dao.byNormalizedWord(normalized)
            if (duplicate != null && duplicate.id != card.id) {
                _message.value = "Another card already uses this word."
                return@launch
            }

            val complete = ipa.trim().isNotBlank() && meaningsFa.trim().isNotBlank() && exampleEn.trim().isNotBlank()
            dao.update(
                card.copy(
                    word = word,
                    normalizedWord = normalized,
                    ipa = ipa.trim(),
                    translationFa = meaningsFa.trim(),
                    exampleEn = exampleEn.trim(),
                    exampleFa = "",
                    enrichmentStatus = if (complete) EnrichmentStatus.READY.name else card.enrichmentStatus,
                    enrichmentError = if (complete) null else card.enrichmentError,
                    enrichmentProvider = if (complete) "Manual" else card.enrichmentProvider,
                    enrichmentModel = if (complete) null else card.enrichmentModel,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _message.value = "Flashcard updated."
            refreshDue()
        }
    }

    fun review(card: FlashcardEntity, rating: ReviewRating) {
        viewModelScope.launch {
            val result = FsrsScheduler.review(card, rating, System.currentTimeMillis())
            database.withTransaction {
                dao.update(result.card)
                reviewLogs.insert(result.log)
            }
            refreshDue()
        }
    }

    fun retryFailed(card: FlashcardEntity) {
        viewModelScope.launch {
            dao.update(card.copy(enrichmentStatus = EnrichmentStatus.PENDING.name, enrichmentError = null, aiAttemptCount = 0))
            if (AiEnrichmentClient.hasAvailableProvider(getApplication())) {
                EnrichmentScheduler.enqueue(getApplication())
            } else {
                _message.value = "Configure a usable AI provider in Settings first."
            }
        }
    }

    fun retryAllFailed() {
        if (!AiEnrichmentClient.hasAvailableProvider(getApplication())) {
            _message.value = "Configure a usable AI provider first."
            return
        }
        viewModelScope.launch {
            val count = dao.retryAllFailed()
            if (count > 0) EnrichmentScheduler.enqueue(getApplication())
            _message.value = if (count > 0) "$count failed card(s) queued for retry." else "There are no failed cards to retry."
        }
    }

    fun delete(card: FlashcardEntity) {
        viewModelScope.launch {
            database.withTransaction {
                reviewLogs.deleteForCard(card.id)
                dao.deleteById(card.id)
            }
            refreshDue()
        }
    }

    fun resetReviewProgress() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val count = database.withTransaction {
                reviewLogs.deleteAll()
                dao.resetReviewProgress(now)
            }
            refreshDue()
            _message.value = "Review progress reset for $count ready card(s)."
        }
    }

    fun deleteAllCards() {
        viewModelScope.launch {
            database.withTransaction {
                reviewLogs.deleteAll()
                dao.deleteAll()
            }
            refreshDue()
            _message.value = "All flashcards and review history were deleted."
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupManager.exportBackup(getApplication(), database, uri) } }
                .onSuccess { _message.value = "Backup exported successfully. API keys were not included." }
                .onFailure { _message.value = "Backup failed: ${it.message ?: "Unknown error"}" }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupManager.exportCsv(getApplication(), database, uri) } }
                .onSuccess { _message.value = "CSV export completed."
                }
                .onFailure { _message.value = "CSV export failed: ${it.message ?: "Unknown error"}" }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupManager.restoreBackup(getApplication(), database, uri) } }
                .onSuccess { result ->
                    refreshDue()
                    _message.value = buildString {
                        append("Restored ${result.cards} cards and ${result.reviewLogs} review logs.")
                        if (result.skippedDuplicates > 0) append(" Skipped ${result.skippedDuplicates} duplicate card(s) in the backup.")
                    }
                }
                .onFailure { _message.value = "Restore failed: ${it.message ?: "Unknown error"}" }
        }
    }

    private fun refreshAiState() {
        val app = getApplication<Application>()
        _geminiApiKeyConfigured.value = GeminiApiKeyStore.hasKey(app)
        _groqApiKeyConfigured.value = GroqApiKeyStore.hasKey(app)
        _providerMode.value = AiProviderSettings.load(app)
        _aiReady.value = AiEnrichmentClient.hasAvailableProvider(app)
    }

    private fun retryFailedIfReady() {
        if (!AiEnrichmentClient.hasAvailableProvider(getApplication())) return
        viewModelScope.launch {
            dao.retryAllFailed()
            // Also wake existing PENDING cards that may have been imported before a key existed.
            EnrichmentScheduler.enqueue(getApplication())
        }
    }

    private fun newCard(row: ImportedWord): FlashcardEntity {
        val now = System.currentTimeMillis()
        val complete = row.ipa.isNotBlank() && row.meaningsFa.isNotBlank() && row.exampleEn.isNotBlank()
        return FlashcardEntity(
            word = WordNormalizer.display(row.word),
            normalizedWord = WordNormalizer.normalize(row.word),
            ipa = row.ipa.trim(),
            translationFa = row.meaningsFa.trim(),
            exampleEn = row.exampleEn.trim(),
            enrichmentStatus = if (complete) EnrichmentStatus.READY.name else EnrichmentStatus.PENDING.name,
            enrichmentProvider = if (complete) "CSV" else null,
            nextReviewAt = now,
            createdAt = now,
            updatedAt = now
        )
    }
}
