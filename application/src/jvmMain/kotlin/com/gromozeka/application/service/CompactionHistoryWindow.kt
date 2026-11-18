package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import kotlinx.serialization.json.JsonPrimitive
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult

/** A raw suffix is independent of archived sources and never splits a tool exchange. */
internal object CompactionHistoryWindow {
    const val RETAINED_CHARACTERS = 80_000

    fun prefix(history: List<Conversation.Message>, pairs: ToolCallPairingService): List<Conversation.Message> {
        val visible = ConversationContext(history).messages()
        val positions = history.mapIndexed { index, message -> message.id to index }.toMap()
        val visibleIds = visible.mapTo(mutableSetOf()) { it.id }
        val retained = mutableSetOf<Conversation.Message.Id>()
        var characters = 0L
        for (message in visible.asReversed()) {
            // A prefix checkpoint is the previous window's handoff. A selective summary
            // does not erase earlier unselected messages from the recent-window budget.
            if (message.content.singleOrNull() is ContextCompactionResult) {
                if (ConversationContext(history.take(positions.getValue(message.id) + 1)).messages().singleOrNull()?.id == message.id) break
                continue
            }
            retained += message.id
            characters += MessageCompactionTextRenderer.characterCount(message)
            if (characters >= RETAINED_CHARACTERS) break
        }
        if (retained.isEmpty()) return history
        var boundary = history.indexOfFirst { it.id in retained }
        val clientMessages = visible.filter { it.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] != JsonPrimitive(true) }
        val openCalls = pairs.buildPairingMap(clientMessages).filterValues { it.toolCall != null && it.toolResult == null }.keys
        val openBoundary = history.indexOfFirst { message -> message.content.any {
            it is Conversation.Message.ContentItem.ToolCall && it.id in openCalls
        } }
        if (openBoundary >= 0) boundary = minOf(boundary, openBoundary)
        do {
            val previous = boundary
            val suffix = history.drop(boundary).map { it.id }
            val paired = pairs.includePairedToolMessages(visible, suffix)
            boundary = minOf(boundary, history.indexOfFirst { it.id in paired }.takeIf { it >= 0 } ?: boundary)
        } while (boundary != previous)
        val prefix = history.take(boundary)
        return if (prefix.any { it.id in visibleIds }) prefix else emptyList()
    }

    fun isFullSelection(history: List<Conversation.Message>, selected: Set<Conversation.Message.Id>): Boolean =
        ConversationContext(history).messages().all { it.id in selected }
}
