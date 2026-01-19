package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.infrastructure.db.memory.graph.ConversationMessageGraphService
import kotlinx.datetime.Clock
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class VectorMemoryService(
    private val conversationMessageGraphService: ConversationMessageGraphService,
    private val embeddingModel: EmbeddingModel,
    private val threadMessageRepository: ThreadMessageRepository
) : com.gromozeka.domain.service.VectorMemoryService {
    private val log = KLoggers.logger(this)

    override suspend fun rememberThread(threadId: String) = withContext(Dispatchers.IO) {
        try {
            val threadMessages = threadMessageRepository.getMessagesByThread(
                Conversation.Thread.Id(threadId)
            ).filter { message ->
                message.role in listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT) &&
                !hasToolCalls(message) &&
                !hasThinking(message)
            }

            if (threadMessages.isEmpty()) {
                log.debug { "No messages to remember for thread $threadId" }
                return@withContext
            }

            val messageNodes = threadMessages.map { message ->
                val content = extractTextContent(message)
                val embedding = embeddingModel.embed(content).toList()

                ConversationMessageGraphService.ConversationMessageNode(
                    id = message.id.value,
                    content = content,
                    threadId = threadId,
                    role = message.role.name,
                    embedding = embedding,
                    createdAt = Clock.System.now().toString()
                )
            }

            conversationMessageGraphService.saveMessages(messageNodes)
            log.info { "Remembered ${messageNodes.size} messages for thread $threadId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember thread $threadId: ${e.message}" }
        }
    }

    override suspend fun recall(
        query: String,
        threadId: String?,
        limit: Int
    ): List<com.gromozeka.domain.service.VectorMemoryService.Memory> = withContext(Dispatchers.IO) {
        try {
            val queryEmbedding = embeddingModel.embed(query).toList()
            val results = conversationMessageGraphService.vectorSearch(
                queryEmbedding = queryEmbedding,
                threadId = threadId,
                limit = limit
            )

            results.map { result ->
                com.gromozeka.domain.service.VectorMemoryService.Memory(
                    content = result.content,
                    messageId = result.id,
                    threadId = result.threadId,
                    score = result.score
                )
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to recall memories for query '$query': ${e.message}" }
            emptyList()
        }
    }

    override suspend fun forgetMessage(messageId: String) = withContext(Dispatchers.IO) {
        try {
            conversationMessageGraphService.deleteMessage(messageId)
            log.debug { "Forgot message $messageId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to forget message $messageId: ${e.message}" }
        }
    }

    private fun extractTextContent(message: Conversation.Message): String {
        return message.content.mapNotNull { contentItem ->
            when (contentItem) {
                is Conversation.Message.ContentItem.UserMessage -> contentItem.text
                is Conversation.Message.ContentItem.AssistantMessage -> contentItem.structured.fullText
                else -> null
            }
        }.joinToString("\n")
    }

    private fun hasToolCalls(message: Conversation.Message): Boolean {
        return message.content.any { it is Conversation.Message.ContentItem.ToolCall }
    }

    private fun hasThinking(message: Conversation.Message): Boolean {
        return message.content.any { it is Conversation.Message.ContentItem.Thinking }
    }
}
