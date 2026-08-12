package com.vazheyar.app.data

import java.io.Reader

data class ImportedWord(
    val word: String,
    val ipa: String = "",
    val meaningsFa: String = "",
    val exampleEn: String = ""
)

/**
 * Small RFC-4180-style CSV reader for the import formats supported by the app.
 * It handles UTF-8 BOM, quoted commas, escaped quotes, CRLF and quoted newlines.
 * Duplicate detection intentionally happens in MainViewModel so skipped rows can
 * be counted accurately in import progress.
 */
object CsvWordParser {
    fun parse(reader: Reader): List<ImportedWord> {
        val records = parseRecords(reader.readText())
            .filterNot { row -> row.all { it.isBlank() } }

        if (records.isEmpty()) return emptyList()

        val header = records.first().mapIndexed { index, value ->
            value.removePrefix(if (index == 0) "\uFEFF" else "").trim().lowercase()
        }
        val knownHeaders = setOf(
            "word", "english", "ipa", "meanings", "meaningsfa", "translationfa", "example", "exampleen"
        )
        val hasHeader = header.any { it in knownHeaders }
        val dataRows = if (hasHeader) records.drop(1) else records

        val wordIndex = if (hasHeader) {
            header.indexOfFirst { it == "word" || it == "english" }.takeIf { it >= 0 } ?: 0
        } else 0
        val ipaIndex = if (hasHeader) header.indexOf("ipa") else -1
        val meaningsIndex = if (hasHeader) {
            header.indexOfFirst { it == "meanings" || it == "meaningsfa" || it == "translationfa" }
        } else -1
        val exampleIndex = if (hasHeader) {
            header.indexOfFirst { it == "example" || it == "exampleen" }
        } else -1

        return buildList {
            dataRows.forEach { row ->
                val rawWord = row.getOrNull(wordIndex).orEmpty().removePrefix("\uFEFF")
                val displayWord = WordNormalizer.display(rawWord)
                if (WordNormalizer.normalize(displayWord).isBlank()) return@forEach

                add(
                    ImportedWord(
                        word = displayWord,
                        ipa = row.getOrNull(ipaIndex).orEmpty().trim(),
                        meaningsFa = row.getOrNull(meaningsIndex).orEmpty().trim(),
                        exampleEn = row.getOrNull(exampleIndex).orEmpty().trim()
                    )
                )
            }
        }
    }

    private fun parseRecords(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0

        fun finishCell() {
            row += cell.toString()
            cell.clear()
        }

        fun finishRow() {
            finishCell()
            rows += row.toList()
            row.clear()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> finishCell()
                c == '\n' && !quoted -> finishRow()
                c == '\r' && !quoted -> {
                    // Treat CRLF as one row separator, while also accepting lone CR.
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    finishRow()
                }
                else -> cell.append(c)
            }
            i++
        }

        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }
}
