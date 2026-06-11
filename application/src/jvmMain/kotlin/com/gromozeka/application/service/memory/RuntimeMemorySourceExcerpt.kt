package com.gromozeka.application.service.memory

internal object RuntimeMemorySourceExcerpt {
    fun queryFocused(text: String, query: String, maxChars: Int = 4_000): String {
        val source = text.trim()
        if (source.length <= maxChars) return source

        val terms = query.searchTerms()
        if (terms.isEmpty()) return source.truncateForExcerpt(maxChars)

        val candidates = buildCandidates(source, terms)
            .sortedWith(
                compareByDescending<ExcerptWindow> { it.distinctTermCount }
                    .thenByDescending { it.totalTermHits }
                    .thenBy { it.start }
            )

        if (candidates.isEmpty()) return source.truncateForExcerpt(maxChars)

        val selected = mutableListOf<ExcerptWindow>()
        var remaining = maxChars - MATCHING_EXCERPTS_HEADER.length - SELECTED_WINDOW_SEPARATOR.length
        for (candidate in candidates) {
            if (selected.any { it.overlaps(candidate, mergeGap = WINDOW_MERGE_GAP) }) continue
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
                    acc[acc.lastIndex] = previous.merge(window, source, terms)
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

    private fun buildCandidates(text: String, terms: List<String>): List<ExcerptWindow> {
        val starts = terms
            .flatMap { term -> text.occurrencesOf(term).take(MAX_OCCURRENCES_PER_TERM) }
            .distinct()

        return starts.map { index ->
            val start = (index - WINDOW_PREFIX_CHARS).coerceAtLeast(0)
            val end = (index + WINDOW_SUFFIX_CHARS).coerceAtMost(text.length)
            ExcerptWindow.score(start, end, text, terms)
        }
    }

    private data class ExcerptWindow(
        val start: Int,
        val end: Int,
        val distinctTermCount: Int,
        val totalTermHits: Int,
    ) {
        fun overlaps(other: ExcerptWindow, mergeGap: Int): Boolean =
            other.start <= end + mergeGap && start <= other.end + mergeGap

        fun merge(other: ExcerptWindow, text: String, terms: List<String>): ExcerptWindow =
            score(minOf(start, other.start), maxOf(end, other.end), text, terms)

        fun renderedLength(textLength: Int): Int =
            end - start + (if (start > 0) 3 else 0) + (if (end < textLength) 3 else 0)

        fun render(text: String): String {
            val prefix = if (start > 0) "..." else ""
            val suffix = if (end < text.length) "..." else ""
            return "$prefix${text.substring(start, end).trim()}$suffix"
        }

        companion object {
            fun score(start: Int, end: Int, text: String, terms: List<String>): ExcerptWindow {
                val window = text.substring(start, end)
                val hits = terms.map { term -> window.countOccurrencesOf(term) }
                return ExcerptWindow(
                    start = start,
                    end = end,
                    distinctTermCount = hits.count { it > 0 },
                    totalTermHits = hits.sum(),
                )
            }
        }
    }

    private fun String.searchTerms(): List<String> =
        split(Regex("[^\\p{L}\\p{N}_-]+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinctBy { it.lowercase() }
            .take(MAX_QUERY_TERMS)

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

    private fun String.truncateForExcerpt(maxChars: Int): String {
        val trimmed = trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars) + "\n[truncated ${trimmed.length - maxChars} chars]"
    }

    private const val MAX_QUERY_TERMS = 16
    private const val MAX_OCCURRENCES_PER_TERM = 64
    private const val WINDOW_PREFIX_CHARS = 600
    private const val WINDOW_SUFFIX_CHARS = 1_100
    private const val WINDOW_MERGE_GAP = 120
    private const val MIN_USEFUL_WINDOW_CHARS = 300
    private const val MATCHING_EXCERPTS_HEADER = "...[matching excerpts]..."
    private const val SELECTED_WINDOW_SEPARATOR = "\n"
}
