package com.vazheyar.app.data

import java.text.Normalizer
import java.util.Locale

object WordNormalizer {
    private val whitespace = Regex("\\s+")
    private val edgeNoise = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")

    fun display(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace('’', '\'')
        .trim()
        .replace(whitespace, " ")

    fun normalize(raw: String): String = display(raw)
        .replace(edgeNoise, "")
        .lowercase(Locale.US)
        .trim()
}
