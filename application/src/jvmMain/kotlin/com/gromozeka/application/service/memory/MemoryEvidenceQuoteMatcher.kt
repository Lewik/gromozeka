package com.gromozeka.application.service.memory

import java.text.Normalizer
import java.util.Locale

internal object MemoryEvidenceQuoteMatcher {
    private val markdownLinePrefixRegex = Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s?|[-+*]\s+|\d+[.)]\s+)""")
    private val markdownMarkerRegex = Regex("""[`*~]+""")
    private val whitespaceRegex = Regex("""\s+""")
    private val tokenRegex = Regex("""[\p{L}\p{N}]+""")

    fun matches(sourceText: String, evidenceQuote: String): Boolean =
        match(sourceText, evidenceQuote).matched

    fun match(sourceText: String, evidenceQuote: String): MemoryEvidenceQuoteMatch {
        val quote = evidenceQuote.trim()
        if (quote.isBlank()) return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.BLANK_QUOTE)

        if (sourceText.contains(quote, ignoreCase = true)) {
            return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.EXACT)
        }

        val normalizedQuote = quote.normalizedEvidenceText()
        if (normalizedQuote.isBlank()) return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.BLANK_QUOTE)

        val normalizedSource = sourceText.normalizedEvidenceText()
        if (normalizedSource.contains(normalizedQuote, ignoreCase = true)) {
            return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.FORMATTING_NORMALIZED)
        }

        val quoteTokens = quote.evidenceTokens()
        if (quoteTokens.size >= 4) {
            val tokenQuote = quoteTokens.joinToString(" ")
            if (tokenQuote.length >= 24 && sourceText.evidenceTokens().joinToString(" ").contains(tokenQuote)) {
                return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.TOKEN_NORMALIZED)
            }
        }

        return MemoryEvidenceQuoteMatch(MemoryEvidenceQuoteMatch.Kind.NO_MATCH)
    }

    private fun String.normalizedEvidenceText(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace(markdownLinePrefixRegex, " ")
            .replace(markdownMarkerRegex, "")
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace(whitespaceRegex, " ")
            .trim()

    private fun String.evidenceTokens(): List<String> =
        tokenRegex.findAll(normalizedEvidenceText())
            .map { it.value.lowercase(Locale.ROOT) }
            .toList()
}

internal data class MemoryEvidenceQuoteMatch(
    val kind: Kind,
) {
    val matched: Boolean
        get() = kind != Kind.BLANK_QUOTE && kind != Kind.NO_MATCH

    enum class Kind {
        EXACT,
        FORMATTING_NORMALIZED,
        TOKEN_NORMALIZED,
        BLANK_QUOTE,
        NO_MATCH,
    }
}
