package com.gromozeka.application.service

import com.gromozeka.domain.model.CompactionMessageHeader
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
        val toolResult: Conversation.Message.ContentItem.ToolResult?,
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

    fun includePairedToolMessages(
        messages: List<Conversation.Message>,
        messageIds: Collection<Conversation.Message.Id>,
    ): Set<Conversation.Message.Id> {
        return includePairs(messages, messageIds, { it.id },
            { it.content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>().map { call -> call.id } },
            { it.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>().map { result -> result.toolUseId } })
    }

    fun includePairedHeaders(headers: List<CompactionMessageHeader>, ids: Collection<Conversation.Message.Id>) =
        includePairs(headers, ids, { it.id }, { it.calls }, { it.results })

    private fun <T> includePairs(
        rows: List<T>, ids: Collection<Conversation.Message.Id>, id: (T) -> Conversation.Message.Id,
        calls: (T) -> Collection<Conversation.Message.ContentItem.ToolCall.Id>,
        results: (T) -> Collection<Conversation.Message.ContentItem.ToolCall.Id>,
    ): Set<Conversation.Message.Id> {
        val called = rows.flatMapTo(mutableSetOf(), calls)
        val paired = rows.flatMapTo(mutableSetOf(), results).intersect(called)
        if (paired.isEmpty()) return ids.toSet()
        val edges = rows.associate { id(it) to (calls(it) + results(it)).filter(paired::contains) }
        val owners = edges.flatMap { (message, keys) -> keys.map { it to message } }.groupBy({ it.first }, { it.second })
        val selected = ids.toMutableSet()
        val queue = ArrayDeque(selected)
        val visited = mutableSetOf<Conversation.Message.ContentItem.ToolCall.Id>()
        while (queue.isNotEmpty()) {
            for (key in edges[queue.removeFirst()].orEmpty()) {
                if (!visited.add(key)) continue
                for (owner in owners.getValue(key)) {
                    if (selected.add(owner)) queue.addLast(owner)
                }
            }
        }
        return selected
    }
}
