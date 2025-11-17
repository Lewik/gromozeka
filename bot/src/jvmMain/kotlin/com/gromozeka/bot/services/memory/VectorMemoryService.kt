package com.gromozeka.bot.services.memory

import com.gromozeka.bot.services.SettingsService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadMessageRepository
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.stereotype.Service

@Service
class VectorMemoryService(
    private val vectorStore: VectorStore?,
    private val settingsService: SettingsService,
    private val threadMessageRepository: ThreadMessageRepository
) {
    private val log = KLoggers.logger(this)

    suspend fun rememberThread(threadId: String) = withContext(Dispatchers.IO) {
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
                vectorStore?.add(documents)
                log.info { "Remembered ${documents.size} messages for thread $threadId" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember thread $threadId: ${e.message}" }
        }
    }

    suspend fun recall(
        query: String,
        threadId: String? = null,
        limit: Int = 5
    ): List<Memory> = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable()) {
            log.debug { "Vector storage disabled or unavailable, skipping recall" }
            return@withContext emptyList()
        }

        try {
            val searchRequest = SearchRequest.builder()
                .query(query)
                .topK(limit)
                .apply {
                    threadId?.let {
                        filterExpression("threadId == '$it'")
                    }
                }
                .build()

            val results = vectorStore?.similaritySearch(searchRequest) ?: emptyList()

            results.map { doc ->
                Memory(
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

    suspend fun forgetMessage(messageId: String) = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable()) {
            log.debug { "Vector storage disabled or unavailable, skipping forgetMessage" }
            return@withContext
        }

        try {
            vectorStore?.delete(listOf(messageId))
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
        return settingsService.settings.vectorStorageEnabled && vectorStore != null
    }
}

data class Memory(
    val content: String,
    val messageId: String,
    val threadId: String,
    val score: Double = 1.0
)
