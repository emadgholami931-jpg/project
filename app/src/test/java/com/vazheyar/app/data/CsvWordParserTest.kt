package com.vazheyar.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class CsvWordParserTest {
    @Test
    fun parsesQuotedCommasQuotesAndNewlines() {
        val csv = """word,ipa,meaningsFa,exampleEn
skill,/skɪl/,مهارت,"She has skill, patience, and focus."
remarkable,/rɪˈmɑrkəbəl/,قابل توجه,"He said ""remarkable"" twice."
focus,/ˈfoʊkəs/,تمرکز,"Keep your
focus here."
""".trimIndent()

        val rows = CsvWordParser.parse(StringReader(csv))
        assertEquals(3, rows.size)
        assertEquals("She has skill, patience, and focus.", rows[0].exampleEn)
        assertEquals("He said \"remarkable\" twice.", rows[1].exampleEn)
        assertEquals("Keep your\nfocus here.", rows[2].exampleEn)
    }

    @Test
    fun leavesDuplicatesForImportLayerToCount() {
        val rows = CsvWordParser.parse(StringReader("word\nSkill\nskill\n"))
        assertEquals(2, rows.size)
    }
}
