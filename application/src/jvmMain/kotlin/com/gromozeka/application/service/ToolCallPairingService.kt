package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import org.springframework.stereotype.Service

/**
 * Service for analyzing ToolCall/ToolResult pairing in message threads.
 * 
 * Provides single source of truth for ToolCall/ToolResult pairing analysis.
 * Returns unified pairing map that can be filtered for different use cases.
 */
@Service
class ToolCallPairingService {
    
    /**
     * Pair of ToolCall and its corresponding ToolResult.
     * 
     * @property toolCall the tool call (null if orphaned ToolResult)
     * @property toolResult the tool result (null if orphaned ToolCall)
     */
    data class ToolCallPair(
        val toolCall: Conversation.Message.ContentItem.ToolCall?,
        val toolResult: Conversation.Message.ContentItem.ToolResult?
    )
    
    /**
     * Build pairing map for all ToolCalls and ToolResults in messages.
     * 
     * Returns map where:
     * - Key: ToolCall.Id
     * - Value: ToolCallPair with toolCall and/or toolResult
     * 
     * Use cases:
     * - Filter pairs where toolResult == null → orphaned ToolCalls
     * - Filter pairs where both != null → paired items
     * - Filter pairs where toolCall == null → orphaned ToolResults
     * 
     * @param messages messages to analyze
     * @return map of ToolCall.Id → ToolCallPair
     */
    fun buildPairingMap(messages: List<Conversation.Message>): Map<Conversation.Message.ContentItem.ToolCall.Id, ToolCallPair> {
        val pairingMap = mutableMapOf<Conversation.Message.ContentItem.ToolCall.Id, ToolCallPair>()
        
        messages.forEach { msg ->
            msg.content.forEach { content ->
                when (content) {
                    is Conversation.Message.ContentItem.ToolCall -> {
                        val pair = pairingMap.getOrPut(content.id) { ToolCallPair(null, null) }
                        pairingMap[content.id] = pair.copy(toolCall = content)
                    }
                    is Conversation.Message.ContentItem.ToolResult -> {
                        val pair = pairingMap.getOrPut(content.toolUseId) { ToolCallPair(null, null) }
                        pairingMap[content.toolUseId] = pair.copy(toolResult = content)
                    }
                    else -> return@forEach
                }
            }
        }
        
        return pairingMap
    }
}
