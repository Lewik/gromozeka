package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.infrastructure.db.vector.QdrantVectorStore
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.document.Document
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class VectorMemoryService(
    private val qdrantVectorStore: QdrantVectorStore?,
    private val threadMessageRepository: ThreadMessageRepository
) : com.gromozeka.domain.service.VectorMemoryService {
    private val log = KLoggers.logger(this)

    override suspend fun rememberThread(threadId: String) = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable()) {
            log.debug { "Vector storage disabled or unavailable, skipping rememberThread" }
            return@withContext
        }

        try {
            val threadMessages = threadMessageRepository.getMessagesByThread(
                Conversation.Thread.Id(threadId)
            ).filter { message ->
                message.role in listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT) &&
                !hasToolCalls(message) &&
                !hasThinking(message)
            }

            val documents = threadMessages.map { message ->
                Document(
                    message.id.value,
                    extractTextContent(message),
                    mapOf("threadId" to threadId)
                )
            }
            
            if (documents.isNotEmpty()) {
                qdrantVectorStore?.add(documents)
                log.info { "Remembered ${documents.size} messages for thread $threadId" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember thread $threadId: ${e.message}" }
        }
    }

    override suspend fun recall(
        query: String,
        threadId: String?,
        limit: Int
    ): List<com.gromozeka.domain.service.VectorMemoryService.Memory> = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable()) {
            log.debug { "Vector storage disabled or unavailable, skipping recall" }
            return@withContext emptyList()
        }

        try {
            val filterExpression = threadId?.let { "threadId == '$it'" }
            val results = qdrantVectorStore?.search(query, limit, filterExpression) ?: emptyList()

            results.map { doc ->
                com.gromozeka.domain.service.VectorMemoryService.Memory(
                    content = doc.formattedContent,
                    messageId = doc.id,
                    threadId = doc.metadata["threadId"] as? String ?: "",
                    score = doc.metadata["distance"] as? Double ?: 1.0
                )
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to recall memories for query '$query': ${e.message}" }
            emptyList()
        }
    }

    override suspend fun forgetMessage(messageId: String) = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable()) {
            log.debug { "Vector storage disabled or unavailable, skipping forgetMessage" }
            return@withContext
        }

        try {
            qdrantVectorStore?.delete(listOf(messageId))
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

    private fun isMemoryAvailable(): Boolean {
        return qdrantVectorStore != null
    }
}
