package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryCursor
import com.gromozeka.domain.model.ConversationHistoryMessage
import com.gromozeka.domain.model.ConversationHistoryPage
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.repository.ConversationHistoryRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.PositionedConversationMessage
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class ConversationHistoryReadService(
    private val conversations: ConversationRepository,
    private val messages: ConversationHistoryRepository,
    private val runtime: ConversationRuntimeCoordinator,
) {
    suspend fun page(conversationId: Conversation.Id, request: ConversationHistoryPageRequest): ConversationHistoryPage {
        val sequence = runtime.lastEventSequence(conversationId)
        val threadId = currentThread(conversationId)
        val cursorThread = (request.before ?: request.after)?.threadId
        val reset = cursorThread != null && cursorThread != threadId
        val actual = if (reset) ConversationHistoryPageRequest(limit = request.limit) else request
        val anchorPosition = actual.around?.let { messages.position(threadId, it) } ?: actual.positionHint
        val before = actual.before?.position ?: anchorPosition?.let { it + actual.limit / 2 + 1 }
        val candidates = messages.page(threadId, before, actual.after?.position, actual.limit)
        val projected = candidates.map { ConversationMessageProjection.project(it) }
        val calls = projected.flatMap { it.message.content }.filterIsInstance<Conversation.Message.ContentItem.ToolCall>().mapTo(mutableSetOf()) { it.id.value }
        val results = messages.toolResults(threadId, calls)
        fun relatedTo(entries: List<ConversationHistoryMessage>): List<ConversationHistoryMessage> {
            val included = entries.mapTo(mutableSetOf()) { it.message.id }
            val ids = entries.flatMap { it.message.content }
                .filterIsInstance<Conversation.Message.ContentItem.ToolCall>().mapTo(mutableSetOf()) { it.id }
            return results.filter { it.message.id !in included }.mapNotNull { result ->
                val content = result.message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                    .filter { it.toolUseId in ids }
                if (content.isEmpty()) return@mapNotNull null
                ConversationHistoryMessage(
                    position = result.position,
                    message = Conversation.Message(
                        id = result.message.id,
                        conversationId = result.message.conversationId,
                        role = result.message.role,
                        content = content.map { it.copy(result = emptyList(), toolName = it.toolName.take(256)) },
                        createdAt = result.message.createdAt,
                    ),
                    hasMoreContent = content.any { it.result.isNotEmpty() },
                )
            }
        }
        val chosen = mutableListOf<ConversationHistoryMessage>()
        val ordered = when {
            anchorPosition != null -> projected.sortedWith(compareBy<ConversationHistoryMessage> { kotlin.math.abs(it.position - anchorPosition) }.thenBy { it.position })
            actual.after != null -> projected
            else -> projected.asReversed()
        }
        for (entry in ordered) {
            val proposed = chosen + entry
            val bytes = pageJson.encodeToString(proposed + relatedTo(proposed)).encodeToByteArray().size + PAGE_ENVELOPE_BYTES
            if (chosen.isNotEmpty() && bytes > MAX_PAGE_BYTES) break
            chosen += entry
        }
        val bounded = chosen.sortedBy { it.position }
        val related = relatedTo(bounded)
        val older = bounded.firstOrNull()?.let { first ->
            messages.page(threadId, first.position, null, 1).firstOrNull()?.let { ConversationHistoryCursor(threadId, first.position) }
        }
        val newer = bounded.lastOrNull()?.let { last ->
            messages.page(threadId, null, last.position, 1).firstOrNull()?.let { ConversationHistoryCursor(threadId, last.position) }
        }
        return ConversationHistoryPage(threadId, bounded, related, older, newer, sequence, reset)
    }

    suspend fun message(conversationId: Conversation.Id, messageId: Conversation.Message.Id): Conversation.Message =
        requireNotNull(messages.message(currentThread(conversationId), messageId)) { "Message is not in the current conversation thread" }.message

    suspend fun eventCheckpoint(conversationId: Conversation.Id): Long = runtime.lastEventSequence(conversationId)

    suspend fun eventMessage(conversationId: Conversation.Id, message: Conversation.Message): Pair<Conversation.Thread.Id, ConversationHistoryMessage>? {
        val threadId = currentThread(conversationId)
        val position = messages.position(threadId, message.id) ?: return null
        return threadId to ConversationMessageProjection.project(PositionedConversationMessage(position, message))
    }

    suspend fun selectedIds(conversationId: Conversation.Id, selection: ConversationMessageSelection): List<Conversation.Message.Id> =
        messages.selectedIds(currentThread(conversationId), selection)

    suspend fun latestUserMessage(conversationId: Conversation.Id): Conversation.Message? =
        messages.latestUserMessage(currentThread(conversationId))?.message

    private suspend fun currentThread(conversationId: Conversation.Id): Conversation.Thread.Id =
        requireNotNull(conversations.findById(conversationId)) { "Conversation not found" }.currentThread

    companion object {
        const val MAX_PAGE_BYTES = 256 * 1024
        private const val PAGE_ENVELOPE_BYTES = 2 * 1024
        private val pageJson = Json { encodeDefaults = true }
    }
}
