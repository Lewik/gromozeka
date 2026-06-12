package com.gromozeka.application.service.memory

internal object RuntimeMemorySourceExcerpt {
    fun queryFocused(text: String, query: String, maxChars: Int = 4_000): String {
        val source = text.trim()
        if (source.length <= maxChars) return source

        val queryNeedles = query.queryNeedles(source)
        if (queryNeedles.terms.isEmpty() && queryNeedles.phrases.isEmpty()) {
            return source.truncateForExcerpt(maxChars)
        }

        val candidates = buildCandidates(source, queryNeedles)
            .sortedWith(
                compareByDescending<ExcerptWindow> { it.weightedScore }
                    .thenByDescending { it.distinctPhraseCount }
                    .thenByDescending { it.totalPhraseHits }
                    .thenByDescending { it.distinctTermCount }
                    .thenByDescending { it.totalTermHits }
                    .thenBy { it.start }
            )

        if (candidates.isEmpty()) return source.truncateForExcerpt(maxChars)

        val selected = mutableListOf<ExcerptWindow>()
        var remaining = maxChars - MATCHING_EXCERPTS_HEADER.length - SELECTED_WINDOW_SEPARATOR.length
        for (candidate in candidates) {
            val overlappingIndex = selected.indexOfFirst { it.overlaps(candidate, mergeGap = WINDOW_MERGE_GAP) }
            if (overlappingIndex >= 0) {
                val previous = selected[overlappingIndex]
                val mergedCandidate = previous.merge(candidate, source, queryNeedles)
                val extraLength = mergedCandidate.renderedLength(source.length) - previous.renderedLength(source.length)
                if (extraLength <= 0 || extraLength <= remaining) {
                    selected[overlappingIndex] = mergedCandidate
                    remaining -= extraLength.coerceAtLeast(0)
                }
                continue
            }
            val snippetLength = candidate.renderedLength(source.length)
            if (snippetLength > remaining && selected.isNotEmpty()) continue
            selected += candidate
            remaining -= snippetLength + SELECTED_WINDOW_SEPARATOR.length
            if (remaining <= MIN_USEFUL_WINDOW_CHARS) break
        }

        val merged = selected
            .sortedBy { it.start }
            .fold(mutableListOf<ExcerptWindow>()) { acc, window ->
                val previous = acc.lastOrNull()
                if (previous != null && previous.overlaps(window, mergeGap = WINDOW_MERGE_GAP)) {
                    acc[acc.lastIndex] = previous.merge(window, source, queryNeedles)
                } else {
                    acc += window
                }
                acc
            }

        if (merged.isEmpty()) return source.truncateForExcerpt(maxChars)

        return buildString {
            append(MATCHING_EXCERPTS_HEADER)
            merged.forEach { window ->
                append('\n')
                append(window.render(source))
            }
        }.truncateForExcerpt(maxChars)
    }

    private fun buildCandidates(text: String, queryNeedles: QueryNeedles): List<ExcerptWindow> {
        val starts = (queryNeedles.phrases + queryNeedles.terms)
            .flatMap { needle -> text.occurrencesOf(needle.text).take(MAX_OCCURRENCES_PER_NEEDLE) }
            .distinct()

        return starts.map { index ->
            val prefixStart = (index - WINDOW_PREFIX_CHARS).coerceAtLeast(0)
            val suffixEnd = (index + WINDOW_SUFFIX_CHARS).coerceAtMost(text.length)
            val lineStart = text.lineStartBefore(index)
            val lineEnd = text.lineEndAfter(index)
            val start = if (index - lineStart <= MAX_LINE_EXTENSION_CHARS) lineStart else prefixStart
            val end = if (lineEnd - index <= MAX_LINE_EXTENSION_CHARS) lineEnd else suffixEnd
            ExcerptWindow.score(start, end, text, queryNeedles)
        }
    }

    private data class QueryNeedles(
        val terms: List<QueryNeedle>,
        val phrases: List<QueryNeedle>,
    )

    private data class QueryNeedle(
        val text: String,
        val weight: Int,
    )

    private data class ExcerptWindow(
        val start: Int,
        val end: Int,
        val distinctTermCount: Int,
        val totalTermHits: Int,
        val distinctPhraseCount: Int,
        val totalPhraseHits: Int,
    ) {
        val weightedScore: Int =
            distinctPhraseCount * PHRASE_HIT_WEIGHT +
                distinctTermCount * DISTINCT_TERM_WEIGHT +
                totalPhraseHits +
                totalTermHits

        fun overlaps(other: ExcerptWindow, mergeGap: Int): Boolean =
            other.start <= end + mergeGap && start <= other.end + mergeGap

        fun merge(other: ExcerptWindow, text: String, queryNeedles: QueryNeedles): ExcerptWindow =
            score(minOf(start, other.start), maxOf(end, other.end), text, queryNeedles)

        fun renderedLength(textLength: Int): Int =
            end - start + (if (start > 0) 3 else 0) + (if (end < textLength) 3 else 0)

        fun render(text: String): String {
            val prefix = if (start > 0) "..." else ""
            val suffix = if (end < text.length) "..." else ""
            return "$prefix${text.substring(start, end).trim()}$suffix"
        }

        companion object {
            fun score(start: Int, end: Int, text: String, queryNeedles: QueryNeedles): ExcerptWindow {
                val window = text.substring(start, end)
                val termHits = queryNeedles.terms.map { term -> NeedleHit(term, window.countOccurrencesOf(term.text)) }
                val phraseHits = queryNeedles.phrases.map { phrase -> NeedleHit(phrase, window.countOccurrencesOf(phrase.text)) }
                return ExcerptWindow(
                    start = start,
                    end = end,
                    distinctTermCount = termHits.count { it.count > 0 },
                    totalTermHits = termHits.scoreHits(),
                    distinctPhraseCount = phraseHits.count { it.count > 0 },
                    totalPhraseHits = phraseHits.scoreHits(),
                )
            }
        }
    }

    private data class NeedleHit(
        val needle: QueryNeedle,
        val count: Int,
    )

    private fun List<NeedleHit>.scoreHits(): Int =
        sumOf { it.needle.weight * it.count.coerceAtMost(MAX_HITS_PER_NEEDLE_FOR_SCORE) }

    private fun String.queryNeedles(source: String): QueryNeedles {
        val tokens = searchTokens()
        val terms = tokens
            .map { it.trim() }
            .filter { it.isUsefulTerm() }
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_TERMS)
            .map { it.toQueryNeedle(source) }
        val phrases = tokens
            .windowed(size = 2, step = 1, partialWindows = false)
            .map { it.joinToString(" ") }
            .filter { it.length >= MIN_PHRASE_CHARS }
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_PHRASES)
            .map { it.toQueryNeedle(source) }
        return QueryNeedles(terms = terms, phrases = phrases)
    }

    private fun String.toQueryNeedle(source: String): QueryNeedle =
        QueryNeedle(text = this, weight = source.countOccurrencesOf(this).rarityWeight())

    private fun Int.rarityWeight(): Int =
        when {
            this <= 1 -> 8
            this <= 3 -> 5
            this <= 10 -> 3
            else -> 1
        }

    private fun String.searchTokens(): List<String> =
        split(Regex("[^\\p{L}\\p{N}_-]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun String.isUsefulTerm(): Boolean {
        val normalized = lowercase()
        return length >= 4 || (length >= 3 && normalized !in SHORT_STOPWORDS)
    }

    private fun String.occurrencesOf(term: String): Sequence<Int> =
        sequence {
            var index = indexOf(term, ignoreCase = true)
            while (index >= 0) {
                yield(index)
                index = indexOf(term, startIndex = index + term.length.coerceAtLeast(1), ignoreCase = true)
            }
        }

    private fun String.countOccurrencesOf(term: String): Int {
        var count = 0
        var index = indexOf(term, ignoreCase = true)
        while (index >= 0) {
            count += 1
            index = indexOf(term, startIndex = index + term.length.coerceAtLeast(1), ignoreCase = true)
        }
        return count
    }

    private fun String.lineStartBefore(index: Int): Int {
        val lineBreak = lastIndexOf('\n', startIndex = index.coerceIn(0, lastIndex))
        return if (lineBreak < 0) 0 else lineBreak + 1
    }

    private fun String.lineEndAfter(index: Int): Int {
        val lineBreak = indexOf('\n', startIndex = index.coerceIn(0, length))
        return if (lineBreak < 0) length else lineBreak
    }

    private fun String.truncateForExcerpt(maxChars: Int): String {
        val trimmed = trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars) + "\n[truncated ${trimmed.length - maxChars} chars]"
    }

    private const val MAX_QUERY_TERMS = 16
    private const val MAX_QUERY_PHRASES = 16
    private const val MAX_OCCURRENCES_PER_NEEDLE = 64
    private const val MIN_PHRASE_CHARS = 8
    private const val PHRASE_HIT_WEIGHT = 24
    private const val DISTINCT_TERM_WEIGHT = 4
    private const val MAX_HITS_PER_NEEDLE_FOR_SCORE = 2
    private const val WINDOW_PREFIX_CHARS = 600
    private const val WINDOW_SUFFIX_CHARS = 1_100
    private const val MAX_LINE_EXTENSION_CHARS = 1_200
    private const val WINDOW_MERGE_GAP = 120
    private const val MIN_USEFUL_WINDOW_CHARS = 300
    private const val MATCHING_EXCERPTS_HEADER = "...[matching excerpts]..."
    private const val SELECTED_WINDOW_SEPARATOR = "\n"
    private val SHORT_STOPWORDS = setOf(
        "and",
        "are",
        "but",
        "can",
        "did",
        "for",
        "had",
        "has",
        "how",
        "not",
        "the",
        "was",
        "you",
    )
}
