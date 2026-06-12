package com.gromozeka.application.service.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryEvidenceQuoteMatcherTest {

    @Test
    fun matchesExactQuote() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = "Gromozeka is implemented in Kotlin.",
            evidenceQuote = "implemented in Kotlin",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.EXACT, match.kind)
        assertTrue(match.matched)
    }

    @Test
    fun matchesQuoteAfterMarkdownAndWhitespaceNormalization() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = """
                ## Runtime notes

                - The **ClaimExtractor v3** emits atomic claims
                  from source-backed evidence.
            """.trimIndent(),
            evidenceQuote = "The ClaimExtractor v3 emits atomic claims from source-backed evidence.",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.FORMATTING_NORMALIZED, match.kind)
        assertTrue(match.matched)
    }

    @Test
    fun matchesLongQuoteAfterPunctuationTokenization() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = "The queue state is stored in Postgres JSONB; workers only receive task identifiers.",
            evidenceQuote = "The queue state is stored in Postgres JSONB workers only receive task identifiers",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.TOKEN_NORMALIZED, match.kind)
        assertTrue(match.matched)
    }

    @Test
    fun matchesIdentifierSeparatorsWithoutFlatteningTerms() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = "The worker_descriptor_provider must be configured explicitly before runtime start.",
            evidenceQuote = "The worker descriptor provider must be configured explicitly before runtime start",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.TOKEN_NORMALIZED, match.kind)
        assertTrue(match.matched)
    }

    @Test
    fun doesNotMatchShortLooseQuoteByTokens() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = "Build 123 failed, build 456 passed.",
            evidenceQuote = "456 failed",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.NO_MATCH, match.kind)
        assertFalse(match.matched)
    }

    @Test
    fun doesNotMatchUnrelatedQuote() {
        val match = MemoryEvidenceQuoteMatcher.match(
            sourceText = "The user prefers Kotlin for backend work.",
            evidenceQuote = "The user prefers Rust for embedded work.",
        )

        assertEquals(MemoryEvidenceQuoteMatch.Kind.NO_MATCH, match.kind)
        assertFalse(match.matched)
    }
}
