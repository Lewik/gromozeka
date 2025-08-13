package com.gromozeka.bot.utils

import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.serialization.json.*

/**
 * Calculates token usage from Claude Code CLI session data
 * Based on analysis of real JSONL files from ~/.claude/projects/
 */
object TokenUsageCalculator {

    /**
     * Token usage statistics for a session
     */
    data class SessionTokenUsage(
        val totalInputTokens: Int,           // СУММА всех input_tokens
        val totalOutputTokens: Int,          // СУММА всех output_tokens  
        val totalCacheCreationTokens: Int,   // СУММА всех cache_creation_input_tokens
        val currentCacheReadTokens: Int      // ПОСЛЕДНЕЕ значение cache_read_input_tokens (уже кумулятивно!)
    ) {
        // Claude Code counts only input + output + cache_read, NOT cache_creation
        val grandTotal: Int get() = 
            totalInputTokens + totalOutputTokens + currentCacheReadTokens
            
        val contextUsagePercent: Float get() = grandTotal / 200_000f // ~200k context limit
        
        // Separate cache creation tokens - they are one-time costs, not part of main count
        val totalCacheTokens: Int get() = totalCacheCreationTokens + currentCacheReadTokens
    }

    /**
     * Extract token usage from assistant message - try metadata first, then fall back to originalJson
     */
    private fun extractTokenUsage(message: ChatMessage): TokenUsage? {
        // Try to extract from metadata first (for historical messages)
        when (val metadata = message.llmSpecificMetadata) {
            is ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry -> {
                metadata.usage?.let { usage ->
                    val tokenUsage = TokenUsage(
                        inputTokens = usage.inputTokens,
                        outputTokens = usage.outputTokens,
                        cacheCreationTokens = usage.cacheCreationTokens ?: 0,
                        cacheReadTokens = usage.cacheReadTokens ?: 0
                    )
                    return tokenUsage
                }
            }
            null -> {
                // No metadata, fall through to originalJson parsing
            }
        }
        
        // Fall back to parsing originalJson (for live messages)
        return extractTokenUsageFromJson(message.originalJson)
    }

    /**
     * Extract token usage from assistant message's original JSON
     */
    private fun extractTokenUsageFromJson(originalJson: String?): TokenUsage? {
        if (originalJson == null) {
            println("[TokenUsageCalculator] originalJson is null, trying metadata...")
            return null
        }
        
        return try {
            val json = Json.parseToJsonElement(originalJson)
            if (json !is JsonObject) {
                println("[TokenUsageCalculator] json is not JsonObject: ${json::class.simpleName}")
                return null
            }
            
            val message = json["message"]?.jsonObject
            if (message == null) {
                println("[TokenUsageCalculator] No 'message' field found in JSON")
                return null
            }
            
            val usage = message["usage"]?.jsonObject
            if (usage == null) {
                println("[TokenUsageCalculator] No 'usage' field found in message")
                return null
            }
            
            val inputTokens = usage["input_tokens"]?.jsonPrimitive?.int ?: 0
            val outputTokens = usage["output_tokens"]?.jsonPrimitive?.int ?: 0
            val cacheCreationTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.int ?: 0
            val cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.int ?: 0
            
            val tokenUsage = TokenUsage(inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens)

            tokenUsage
        } catch (e: Exception) {
            println("[TokenUsageCalculator] Error parsing usage from JSON: ${e.message}")
            println("[TokenUsageCalculator] JSON snippet: ${originalJson.take(200)}...")
            null
        }
    }

    /**
     * Calculate total token usage for a session from all messages
     */
    fun calculateSessionUsage(messages: List<ChatMessage>): SessionTokenUsage {
        var totalInputTokens = 0
        var totalOutputTokens = 0
        var totalCacheCreationTokens = 0
        var latestCacheReadTokens = 0
        
        val assistantMessages = messages.filter { it.role == ChatMessage.Role.ASSISTANT }
        println("[TokenUsageCalculator] Processing ${assistantMessages.size} assistant messages out of ${messages.size} total")
        
        // Only process ASSISTANT messages - they contain usage info
        assistantMessages.forEachIndexed { index, message ->
                println("[TokenUsageCalculator] Processing assistant message $index: uuid=${message.uuid}, isHistorical=${message.isHistorical}")
                val usage = extractTokenUsage(message)
                if (usage != null) {
                    totalInputTokens += usage.inputTokens
                    totalOutputTokens += usage.outputTokens
                    
                    // Cache creation tokens: only count from non-historical messages
                    // Historical messages may contain accumulated cache_creation_tokens from previous sessions
                    if (!message.isHistorical) {
                        totalCacheCreationTokens += usage.cacheCreationTokens
                        println("[TokenUsageCalculator] Added cache_create=${usage.cacheCreationTokens} (non-historical)")
                    } else {
                        println("[TokenUsageCalculator] Skipped cache_create=${usage.cacheCreationTokens} (historical message)")
                    }
                    
                    // Cache read tokens are cumulative - take the latest value
                    if (usage.cacheReadTokens > latestCacheReadTokens) {
                        latestCacheReadTokens = usage.cacheReadTokens
                    }
                    println("[TokenUsageCalculator] Running totals: in=$totalInputTokens, out=$totalOutputTokens, cache_create=$totalCacheCreationTokens, cache_read_latest=$latestCacheReadTokens")
                }
            }
        
        val result = SessionTokenUsage(
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            totalCacheCreationTokens = totalCacheCreationTokens,
            currentCacheReadTokens = latestCacheReadTokens
        )
        
        println("[TokenUsageCalculator] Final result: ${result.grandTotal} total (${(result.contextUsagePercent * 100).toInt()}%)")
        
        return result
    }


    /**
     * Internal data class for individual message usage
     */
    private data class TokenUsage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheCreationTokens: Int,
        val cacheReadTokens: Int
    )
}