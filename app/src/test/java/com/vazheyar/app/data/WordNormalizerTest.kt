package com.vazheyar.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WordNormalizerTest {
    @Test
    fun normalizesCaseWhitespaceAndEdgePunctuation() {
        assertEquals("hello world", WordNormalizer.normalize("  Hello   World!  "))
    }

    @Test
    fun keepsInternalApostrophe() {
        assertEquals("don't", WordNormalizer.normalize("DON’T"))
    }
}
