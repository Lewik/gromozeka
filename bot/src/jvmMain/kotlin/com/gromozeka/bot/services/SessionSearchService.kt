package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatSessionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class SessionSearchService(
    // Kept for backward compatibility during migration
    @Suppress("UNUSED_PARAMETER") private val sessionJsonlService: SessionJsonlService,
    @Suppress("UNUSED_PARAMETER") private val settingsService: SettingsService,
) {
    // TODO: Remove this service entirely after UI cleanup

    suspend fun searchSessions(query: String): List<ChatSessionMetadata> = withContext(Dispatchers.IO) {
        // TODO: Search disabled - JSONL parsing deprecated, will be removed
        return@withContext emptyList()
    }

    // TODO: Disabled - JSONL parsing deprecated
    // private suspend fun searchInSession(...): Boolean { ... }

    // TODO: Disabled - JSONL parsing deprecated
    // private fun searchInFullText(...): Boolean { ... }

    // TODO: Disabled - JSONL parsing deprecated
    // private fun extractConversationText(...): String { ... }

    // TODO: Disabled - JSONL parsing deprecated
    // private fun searchInLine(...): Boolean { ... }

    // TODO: Disabled - JSONL parsing deprecated
    // private fun extractSearchableText(...): String { ... }

    // TODO: Disabled - JSONL parsing deprecated
    // private fun findSessionFile(...): File? { ... }

    // ==================== FUZZY SEARCH ALGORITHMS (DISABLED) ====================

    // TODO: Disabled - JSONL parsing deprecated
    /*
    private fun partialRatio(query: String, text: String): Double {
        if (query.isEmpty() || text.isEmpty()) return 0.0

        val lowerQuery = query.lowercase()
        val lowerText = text.lowercase()

        // If query is longer than text, swap them
        val (shorter, longer) = if (lowerQuery.length <= lowerText.length) {
            lowerQuery to lowerText
        } else {
            lowerText to lowerQuery
        }

        // Find best matching substring
        var maxRatio = 0.0
        val shorterLength = shorter.length

        for (i in 0..longer.length - shorterLength) {
            val substring = longer.substring(i, i + shorterLength)
            val ratio = levenshteinSimilarity(shorter, substring)
            maxRatio = max(maxRatio, ratio)
        }

        return maxRatio
    }

    /**
     * Jaro-Winkler similarity algorithm
     */
    private fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val str1 = s1.lowercase()
        val str2 = s2.lowercase()

        val jaroSimilarity = jaroSimilarity(str1, str2)
        if (jaroSimilarity < 0.7) return jaroSimilarity

        // Calculate common prefix length (up to 4 characters)
        val prefixLength = commonPrefixLength(str1, str2, 4)

        // Jaro-Winkler = Jaro + (prefixLength * p * (1 - Jaro))
        // where p = 0.1 (standard scaling factor)
        return jaroSimilarity + (prefixLength * 0.1 * (1.0 - jaroSimilarity))
    }

    private fun jaroSimilarity(s1: String, s2: String): Double {
        val len1 = s1.length
        val len2 = s2.length

        if (len1 == 0 && len2 == 0) return 1.0
        if (len1 == 0 || len2 == 0) return 0.0

        val matchWindow = max(len1, len2) / 2 - 1
        if (matchWindow < 0) return if (s1 == s2) 1.0 else 0.0

        val s1Matches = BooleanArray(len1)
        val s2Matches = BooleanArray(len2)

        var matches = 0

        // Find matches
        for (i in s1.indices) {
            val start = max(0, i - matchWindow)
            val end = min(i + matchWindow + 1, len2)

            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        // Count transpositions
        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        return (matches.toDouble() / len1 + matches.toDouble() / len2 +
                (matches - transpositions / 2.0) / matches) / 3.0
    }

    private fun commonPrefixLength(s1: String, s2: String, maxLength: Int): Int {
        var prefixLength = 0
        val minLength = min(min(s1.length, s2.length), maxLength)

        for (i in 0 until minLength) {
            if (s1[i] == s2[i]) {
                prefixLength++
            } else {
                break
            }
        }

        return prefixLength
    }

    /**
     * Token set ratio - compares sets of unique tokens
     */
    private fun tokenSetRatio(query: String, text: String): Double {
        val queryTokens = tokenize(query.lowercase())
        val textTokens = tokenize(text.lowercase())

        if (queryTokens.isEmpty() && textTokens.isEmpty()) return 1.0
        if (queryTokens.isEmpty() || textTokens.isEmpty()) return 0.0

        val intersection = queryTokens.intersect(textTokens.toSet())
        val union = queryTokens.union(textTokens.toSet())

        return if (union.isNotEmpty()) {
            intersection.size.toDouble() / union.size
        } else 0.0
    }

    private fun tokenize(text: String): Set<String> {
        return text.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Levenshtein similarity (1 - normalized distance)
     */
    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val distance = levenshteinDistance(s1, s2)
        val maxLength = max(s1.length, s2.length)
        return if (maxLength > 0) {
            1.0 - distance.toDouble() / maxLength
        } else 1.0
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        if (len1 == 0) return len2
        if (len2 == 0) return len1

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Initialize first row and column
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }
    */
}