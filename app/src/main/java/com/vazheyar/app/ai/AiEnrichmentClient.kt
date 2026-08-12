package com.vazheyar.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

enum class AiProviderMode {
    AUTO,
    GEMINI,
    GROQ
}

internal enum class AiProvider(val displayName: String) {
    GEMINI("Gemini"),
    GROQ("Groq")
}

internal data class EnrichedCard(
    val word: String,
    val ipa: String,
    val meaningsFa: List<String>,
    val exampleEn: String,
    val provider: String,
    val model: String
)

internal class AiProviderApiException(
    val provider: AiProvider,
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    message: String
) : Exception(message) {
    val retryable: Boolean
        get() = statusCode == 408 ||
            statusCode == 409 ||
            statusCode == 429 ||
            statusCode >= 500
}

internal class AiEnrichmentException(
    val retryable: Boolean,
    message: String
) : Exception(message)

internal object AiProviderSettings {
    private const val PREFS = "ai_provider_settings"
    private const val MODE = "mode"

    private fun cooldownKey(provider: AiProvider) = "cooldown_${provider.name}"

    fun load(context: Context): AiProviderMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MODE, AiProviderMode.AUTO.name)

        return runCatching {
            AiProviderMode.valueOf(raw ?: AiProviderMode.AUTO.name)
        }.getOrDefault(AiProviderMode.AUTO)
    }

    fun save(context: Context, mode: AiProviderMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MODE, mode.name)
            .apply()
    }

    fun markCooldown(context: Context, provider: AiProvider, seconds: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(cooldownKey(provider), System.currentTimeMillis() + seconds.coerceIn(5, 3600) * 1000L)
            .apply()
    }

    fun isCoolingDown(context: Context, provider: AiProvider): Boolean {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(cooldownKey(provider), 0L)
        return until > System.currentTimeMillis()
    }
}

internal object AiEnrichmentClient {

    private const val MAX_WORDS_PER_REQUEST = 20

    fun hasAvailableProvider(context: Context): Boolean =
        resolveProviders(context).isNotEmpty()

    fun enrich(
        context: Context,
        words: List<String>
    ): List<EnrichedCard> {
        if (words.isEmpty()) return emptyList()

        val cleanWords = words
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_WORDS_PER_REQUEST)

        if (cleanWords.isEmpty()) return emptyList()

        val mode = AiProviderSettings.load(context)
        val providers = resolveProviders(context)

        if (providers.isEmpty()) {
            val message = when (mode) {
                AiProviderMode.AUTO ->
                    "No AI API key is configured. Add a Gemini or Groq API key in Settings."
                AiProviderMode.GEMINI ->
                    "Gemini is selected, but no Gemini API key is configured."
                AiProviderMode.GROQ ->
                    "Groq is selected, but no Groq API key is configured."
            }
            throw AiEnrichmentException(retryable = false, message = message)
        }

        val failures = mutableListOf<Pair<AiProvider, Throwable>>()

        for (provider in providers) {
            try {
                return when (provider) {
                    AiProvider.GEMINI -> GeminiEnrichmentClient.enrich(context, cleanWords)
                    AiProvider.GROQ -> GroqEnrichmentClient.enrich(context, cleanWords)
                }
            } catch (t: Throwable) {
                failures += provider to t
                if (t is AiProviderApiException && (t.statusCode == 429 || t.statusCode >= 500)) {
                    AiProviderSettings.markCooldown(
                        context,
                        provider,
                        t.retryAfterSeconds ?: if (t.statusCode == 429) 60L else 30L
                    )
                }

                if (mode != AiProviderMode.AUTO) {
                    break
                }
            }
        }

        val retryable = failures.any { (_, error) -> isRetryable(error) }
        val message = failures.joinToString(separator = "\n") { (provider, error) ->
            "${provider.displayName}: ${error.message?.take(700) ?: "request failed"}"
        }.ifBlank { "AI enrichment failed." }

        throw AiEnrichmentException(
            retryable = retryable,
            message = message
        )
    }

    private fun resolveProviders(context: Context): List<AiProvider> {
        val hasGemini = GeminiApiKeyStore.hasKey(context)
        val hasGroq = GroqApiKeyStore.hasKey(context)

        return when (AiProviderSettings.load(context)) {
            AiProviderMode.AUTO -> buildList {
                if (hasGemini) add(AiProvider.GEMINI)
                if (hasGroq) add(AiProvider.GROQ)
            }.sortedBy { AiProviderSettings.isCoolingDown(context, it) }

            AiProviderMode.GEMINI -> if (hasGemini) listOf(AiProvider.GEMINI) else emptyList()
            AiProviderMode.GROQ -> if (hasGroq) listOf(AiProvider.GROQ) else emptyList()
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is AiProviderApiException -> error.retryable
        is SocketTimeoutException -> true
        is IOException -> true
        else -> false
    }
}

private object FlashcardPayload {

    fun responseSchema(): JSONObject {
        val cardSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("ipa", JSONObject().put("type", "string"))
                    .put(
                        "meaningsFa",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                    )
                    .put("exampleEn", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray(listOf("ipa", "meaningsFa", "exampleEn")))
            .put("additionalProperties", false)

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "cards",
                    JSONObject()
                        .put("type", "array")
                        .put("items", cardSchema)
                )
            )
            .put("required", JSONArray(listOf("cards")))
            .put("additionalProperties", false)
    }

    fun systemInstruction(): String = """
You create accurate English-to-Persian vocabulary flashcards for language learners.
Follow the requested JSON schema exactly. Keep answers concise and practical.
Do not add explanations, markdown, notes, or fields that are not requested.
    """.trimIndent()

    fun input(words: List<String>): String = """
Create exactly one flashcard record for each English word below, in the exact same order.

For each word:
- ipa: Give standard General American IPA between /slashes/.
- meaningsFa: Give 1 to 4 genuinely common Persian meanings. Prefer everyday meanings, avoid rare senses, avoid duplicates, and do not invent extra meanings just to reach a number.
- exampleEn: Give one short, natural English example sentence that clearly demonstrates the word. Prefer a practical B1-B2 level sentence and keep it concise.
- Do not translate the example sentence into Persian.

Input words:
${JSONArray(words)}
    """.trimIndent()

    fun parse(
        jsonText: String,
        expectedWords: List<String>,
        providerName: String
    ): List<EnrichedCard> {
        val parsed = JSONObject(jsonText)
        val cards = parsed.optJSONArray("cards")
            ?: error("$providerName response did not contain cards.")

        if (cards.length() != expectedWords.size) {
            error(
                "$providerName returned ${cards.length()} cards for ${expectedWords.size} words."
            )
        }

        return buildList {
            for (i in 0 until cards.length()) {
                val item = cards.getJSONObject(i)
                val meaningsJson = item.optJSONArray("meaningsFa")
                    ?: error("$providerName returned a card without meanings.")

                val meanings = buildList {
                    for (j in 0 until meaningsJson.length()) {
                        val meaning = meaningsJson.optString(j).trim()
                        if (meaning.isNotBlank() && meaning !in this) add(meaning)
                    }
                }.take(4)

                if (meanings.isEmpty()) {
                    error("$providerName returned an empty meanings list.")
                }

                val ipa = item.optString("ipa").trim()
                val exampleEn = item.optString("exampleEn").trim()

                if (ipa.isBlank() || exampleEn.isBlank()) {
                    error("$providerName returned an incomplete flashcard.")
                }

                add(
                    EnrichedCard(
                        word = expectedWords[i],
                        ipa = ipa,
                        meaningsFa = meanings,
                        exampleEn = exampleEn,
                        provider = providerName,
                        model = when (providerName) {
                            "Gemini" -> "gemini-3.6-flash"
                            "Groq" -> "openai/gpt-oss-20b"
                            else -> "unknown"
                        }
                    )
                )
            }
        }
    }
}

private object GeminiEnrichmentClient {

    private const val MODEL = "gemini-3.6-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 45_000

    fun enrich(
        context: Context,
        words: List<String>
    ): List<EnrichedCard> {
        val apiKey = GeminiApiKeyStore.load(context)
            ?: error("Google Gemini API key is not configured.")

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            connection.outputStream.use { output ->
                output.write(buildRequest(words).toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = readResponse(connection, responseCode)

            if (responseCode !in 200..299) {
                throw providerError(
                    provider = AiProvider.GEMINI,
                    responseCode = responseCode,
                    responseText = responseText,
                    retryAfterHeader = connection.getHeaderField("Retry-After")
                )
            }

            return parseResponse(responseText, words)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequest(words: List<String>): JSONObject {
        val responseFormat = JSONObject()
            .put("type", "text")
            .put("mime_type", "application/json")
            .put("schema", FlashcardPayload.responseSchema())

        return JSONObject()
            .put("model", MODEL)
            .put("store", false)
            .put("system_instruction", FlashcardPayload.systemInstruction())
            .put("input", FlashcardPayload.input(words))
            .put(
                "generation_config",
                JSONObject().put("thinking_level", "low")
            )
            .put("response_format", responseFormat)
    }

    private fun parseResponse(
        responseText: String,
        expectedWords: List<String>
    ): List<EnrichedCard> {
        val root = JSONObject(responseText)
        val steps = root.optJSONArray("steps")
            ?: error("Gemini returned no response steps.")

        var outputText: String? = null

        for (i in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue

            val content = step.optJSONArray("content") ?: continue
            val text = buildString {
                for (j in 0 until content.length()) {
                    val block = content.optJSONObject(j) ?: continue
                    if (block.optString("type") == "text") {
                        append(block.optString("text"))
                    }
                }
            }.trim()

            if (text.isNotBlank()) {
                outputText = text
                break
            }
        }

        val jsonText = outputText
            ?: error("Gemini returned no usable text output.")

        return FlashcardPayload.parse(
            jsonText = jsonText,
            expectedWords = expectedWords,
            providerName = "Gemini"
        )
    }
}

private object GroqEnrichmentClient {

    // Production model with strict JSON Schema support on GroqCloud.
    private const val MODEL = "openai/gpt-oss-20b"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 45_000

    fun enrich(
        context: Context,
        words: List<String>
    ): List<EnrichedCard> {
        val apiKey = GroqApiKeyStore.load(context)
            ?: error("Groq API key is not configured.")

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { output ->
                output.write(buildRequest(words).toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = readResponse(connection, responseCode)

            if (responseCode !in 200..299) {
                throw providerError(
                    provider = AiProvider.GROQ,
                    responseCode = responseCode,
                    responseText = responseText,
                    retryAfterHeader = connection.getHeaderField("Retry-After")
                )
            }

            return parseResponse(responseText, words)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequest(words: List<String>): JSONObject {
        val responseFormat = JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "flashcards")
                    .put("strict", true)
                    .put("schema", FlashcardPayload.responseSchema())
            )

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", FlashcardPayload.systemInstruction())
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", FlashcardPayload.input(words))
            )

        return JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("reasoning_effort", "low")
            .put("response_format", responseFormat)
    }

    private fun parseResponse(
        responseText: String,
        expectedWords: List<String>
    ): List<EnrichedCard> {
        val root = JSONObject(responseText)
        val choices = root.optJSONArray("choices")
            ?: error("Groq returned no choices.")

        if (choices.length() == 0) {
            error("Groq returned an empty response.")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: error("Groq response did not contain a message.")

        val jsonText = message.optString("content").trim()
        if (jsonText.isBlank()) {
            val refusal = message.optString("refusal").trim()
            if (refusal.isNotBlank()) {
                error("Groq refused the request: ${refusal.take(500)}")
            }
            error("Groq returned no usable text output.")
        }

        return FlashcardPayload.parse(
            jsonText = jsonText,
            expectedWords = expectedWords,
            providerName = "Groq"
        )
    }
}

private fun readResponse(
    connection: HttpURLConnection,
    responseCode: Int
): String {
    val stream = if (responseCode in 200..299) {
        connection.inputStream
    } else {
        connection.errorStream
    }

    return stream?.let {
        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }.orEmpty()
}

private fun providerError(
    provider: AiProvider,
    responseCode: Int,
    responseText: String,
    retryAfterHeader: String?
): AiProviderApiException {
    val apiMessage = runCatching {
        JSONObject(responseText)
            .optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    val friendlyMessage = when (provider) {
        AiProvider.GEMINI -> when (responseCode) {
            400 -> "Gemini rejected the request as invalid."
            401, 403 -> "The Gemini API key is invalid or does not have access."
            404 -> "The selected Gemini model or API endpoint is unavailable."
            408 -> "The Gemini request timed out."
            429 -> "Gemini rate limit or quota was reached."
            in 500..599 -> "Gemini is temporarily unavailable."
            else -> "Gemini request failed with HTTP $responseCode."
        }

        AiProvider.GROQ -> when (responseCode) {
            400 -> "Groq rejected the request as invalid."
            401 -> "The Groq API key is invalid."
            403 -> "The Groq API key does not have access to this model."
            404 -> "The selected Groq model or API endpoint is unavailable."
            408 -> "The Groq request timed out."
            413 -> "The Groq request was too large."
            422 -> "Groq could not process the request."
            429 -> "Groq rate limit or quota was reached."
            in 500..599 -> "Groq is temporarily unavailable."
            else -> "Groq request failed with HTTP $responseCode."
        }
    }

    val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()

    return AiProviderApiException(
        provider = provider,
        statusCode = responseCode,
        retryAfterSeconds = retryAfterSeconds,
        message = buildString {
            append(friendlyMessage)
            if (!apiMessage.isNullOrBlank()) {
                append(" ")
                append(apiMessage.take(500))
            }
        }
    )
}
