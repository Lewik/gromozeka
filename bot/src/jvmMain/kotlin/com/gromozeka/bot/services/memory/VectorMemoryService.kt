package com.gromozeka.bot.services.memory

import com.gromozeka.bot.services.SettingsService
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.repository.ThreadMessageRepository
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class VectorMemoryService(
    private val vectorStore: VectorStore?,
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate?,
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

            val rememberedIds = getRememberedMessageIds(threadId)
            val newMessages = threadMessages.filterNot { it.id.value in rememberedIds }
            
            if (newMessages.isNotEmpty()) {
                val documents = newMessages.map { message ->
                    Document(
                        message.id.value,
                        extractTextContent(message),
                        mapOf("threadId" to threadId)
                    )
                }
                
                vectorStore?.add(documents)
                log.info { "Remembered ${newMessages.size} new messages for thread $threadId" }
            }

            val currentIds = threadMessages.map { it.id.value }.toSet()
            val deletedIds = rememberedIds - currentIds
            
            if (deletedIds.isNotEmpty()) {
                deletedIds.forEach { messageId ->
                    forgetMessage(messageId)
                }
                log.info { "Forgot ${deletedIds.size} deleted messages from thread $threadId" }
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
                    threadId = doc.metadata["threadId"] as? String ?: ""
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

    private suspend fun getRememberedMessageIds(threadId: String): Set<String> = withContext(Dispatchers.IO) {
        if (!isMemoryAvailable() || jdbcTemplate == null) return@withContext emptySet()

        try {
            jdbcTemplate.query(
                "SELECT id FROM public.vector_store WHERE metadata->>'threadId' = ?",
                { rs, _ -> rs.getString("id") },
                threadId
            ).toSet()
        } catch (e: Exception) {
            log.error(e) { "Failed to get remembered message IDs for thread $threadId: ${e.message}" }
            emptySet()
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
    val threadId: String
)
