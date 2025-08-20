@file:OptIn(ExperimentalTime::class)

package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatSessionMetadata
import com.gromozeka.bot.model.ClaudeLogEntry
import com.gromozeka.bot.services.ClaudeLogEntryMapper
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.ClaudeCodeToolCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.time.ExperimentalTime

@Service
class SessionSearchService(
    private val sessionJsonlService: SessionJsonlService,
    private val settingsService: SettingsService
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    
    suspend fun searchSessions(query: String): List<ChatSessionMetadata> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        
        println("[SessionSearchService] Starting search for: '$query'")
        
        // 1. Get all sessions (reuse loadAllSessionsMetadata)
        val allSessions = sessionJsonlService.loadAllSessionsMetadata()
        println("[SessionSearchService] Searching in ${allSessions.size} sessions")
        
        // 2. Search in parallel across all sessions
        val matchingSessions = allSessions.mapNotNull { session ->
            async {
                try {
                    if (searchInSession(session, query)) {
                        session
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    println("[SessionSearchService] Error searching session ${session.claudeSessionId}: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()
        
        println("[SessionSearchService] Found ${matchingSessions.size} sessions matching '$query'")
        return@withContext matchingSessions.sortedByDescending { it.lastTimestamp }
    }
    
    /**
     * Search within a single session - collect all text into one string, then search
     */
    private suspend fun searchInSession(session: ChatSessionMetadata, query: String): Boolean = withContext(Dispatchers.IO) {
        val sessionFile = findSessionFile(session.claudeSessionId.value, session.projectPath)
            ?: return@withContext false
        
        if (!sessionFile.exists() || !sessionFile.canRead()) {
            return@withContext false
        }
        
        return@withContext try {
            // Collect all textual content of the session into one string
            val fullSessionText = StringBuilder()
            
            sessionFile.useLines { lines ->
                lines.forEach { line ->
                    try {
                        val claudeEntry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                        val chatMessage = ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
                        val messageText = extractConversationText(chatMessage)
                        if (messageText.isNotEmpty()) {
                            fullSessionText.append(messageText).append(" ")
                        }
                    } catch (e: Exception) {
                        // Skip lines that cannot be parsed
                    }
                }
            }
            
            val sessionText = fullSessionText.toString().trim()
            if (sessionText.isEmpty()) return@withContext false
            
            // Apply search to the full session text
            searchInFullText(query, sessionText)
            
        } catch (e: Exception) {
            println("[SessionSearchService] Error reading session file ${sessionFile.path}: ${e.message}")
            false
        }
    }
    
    /**
     * Search within full session text
     */
    private fun searchInFullText(query: String, sessionText: String): Boolean {
        // Apply search: first exact match, then fuzzy
        if (sessionText.contains(query, ignoreCase = true)) {
            return true
        }
        
        // Fuzzy search with three algorithms
        val partialScore = partialRatio(query, sessionText)
        val jaroScore = jaroWinklerSimilarity(query, sessionText)
        val tokenScore = tokenSetRatio(query, sessionText)
        
        // Adaptive threshold: short queries require high accuracy
        val threshold = when {
            query.length <= 2 -> 0.95  // Very short - almost exact match
            query.length <= 5 -> 0.8   // Short - high accuracy  
            query.length <= 15 -> 0.65 // Medium queries
            else -> 0.5               // Long queries - softer threshold
        }
        
        val bestScore = maxOf(partialScore, jaroScore, tokenScore)
        
        // Additional check: at least 2 algorithms must give reasonable score
        val goodScores = listOf(partialScore, jaroScore, tokenScore).count { it > 0.4 }
        
        return bestScore > threshold && goodScores >= 2
    }
    
    /**
     * Extract only conversational text (exclude files, code, technical details)
     */
    private fun extractConversationText(message: ChatMessage?): String {
        if (message == null) return ""
        
        return message.content.joinToString(" ") { content ->
            when (content) {
                is ChatMessage.ContentItem.UserMessage -> content.text
                is ChatMessage.ContentItem.AssistantMessage -> content.structured.fullText
                is ChatMessage.ContentItem.System -> content.content
                is ChatMessage.ContentItem.Thinking -> content.thinking
                // Exclude technical details: tool calls, tool results
                else -> ""
            }
        }
    }

    /**
     * Search within a single line of jsonl file (deprecated - now using searchInFullText)
     */
    private fun searchInLine(line: String, query: String): Boolean {
        return try {
            // Deserialize the line
            val claudeEntry = json.decodeFromString<ClaudeLogEntry>(line.trim())
            val chatMessage = ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
            
            // Extract text from the message
            val messageText = extractSearchableText(chatMessage)
            if (messageText.isEmpty()) return false
            
            // Apply search: first exact match, then fuzzy
            if (messageText.contains(query, ignoreCase = true)) {
                return true
            }
            
            // Fuzzy search with three algorithms
            val partialScore = partialRatio(query, messageText)
            val jaroScore = jaroWinklerSimilarity(query, messageText)
            val tokenScore = tokenSetRatio(query, messageText)
            
            // Adaptive threshold: short queries require high accuracy
            val threshold = when {
                query.length <= 2 -> 0.95  // Very short - almost exact match
                query.length <= 5 -> 0.8   // Short - high accuracy  
                query.length <= 15 -> 0.65 // Medium queries
                else -> 0.5               // Long queries - softer threshold
            }
            
            val bestScore = maxOf(partialScore, jaroScore, tokenScore)
            
            // Additional check: at least 2 algorithms must give reasonable score
            val goodScores = listOf(partialScore, jaroScore, tokenScore).count { it > 0.4 }
            
            return bestScore > threshold && goodScores >= 2
            
        } catch (e: Exception) {
            // If line cannot be parsed - skip it
            false
        }
    }
    
    /**
     * Extract searchable text from ChatMessage
     */
    private fun extractSearchableText(message: ChatMessage?): String {
        if (message == null) return ""
        
        return message.content.joinToString(" ") { content ->
            when (content) {
                is ChatMessage.ContentItem.UserMessage -> content.text
                is ChatMessage.ContentItem.AssistantMessage -> content.structured.fullText
                is ChatMessage.ContentItem.ToolCall -> {
                    // Search within tool call parameters
                    when (val call = content.call) {
                        is ClaudeCodeToolCallData.Bash -> call.command
                        is ClaudeCodeToolCallData.Read -> call.filePath
                        is ClaudeCodeToolCallData.Edit -> "${call.filePath} ${call.oldString} ${call.newString}"
                        is ClaudeCodeToolCallData.Grep -> call.pattern
                        is ClaudeCodeToolCallData.WebSearch -> call.query
                        is ClaudeCodeToolCallData.WebFetch -> call.url
                        is ClaudeCodeToolCallData.Task -> call.description
                        is ClaudeCodeToolCallData.TodoWrite -> "todo list"
                        else -> ""
                    }
                }
                is ChatMessage.ContentItem.ToolResult -> {
                    content.result.joinToString(" ") { data ->
                        when (data) {
                            is ChatMessage.ContentItem.ToolResult.Data.Text -> data.content
                            else -> ""
                        }
                    }
                }
                is ChatMessage.ContentItem.System -> content.content
                is ChatMessage.ContentItem.Thinking -> content.thinking
                else -> ""
            }
        }
    }
    
    /**
     * Find session file
     */
    private fun findSessionFile(sessionId: String, projectPath: String): File? {
        return try {
            val projectsDir = settingsService.getClaudeProjectsDir()
            val encodedProjectPath = projectPath.replace("/", "-").replace("\\", "-")
            val projectDir = File(projectsDir, encodedProjectPath)
            val sessionFile = File(projectDir, "$sessionId.jsonl")
            
            if (sessionFile.exists()) sessionFile else null
        } catch (e: Exception) {
            println("[SessionSearchService] Error finding session file for $sessionId: ${e.message}")
            null
        }
    }
    
    // ==================== FUZZY SEARCH ALGORITHMS ====================
    
    /**
     * Partial ratio - finds best matching substring
     */
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
}