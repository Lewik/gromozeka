package com.gromozeka.bot.config

import com.gromozeka.bot.services.memory.VectorMemoryService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.UUID
import java.util.function.BiFunction

@Configuration
class MemoryToolsConfig {

    private val logger = LoggerFactory.getLogger(MemoryToolsConfig::class.java)

    @Bean
    fun recallMemoryTool(vectorMemoryService: VectorMemoryService): ToolCallback {
        val function = object : BiFunction<RecallMemoryParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: RecallMemoryParams, context: ToolContext?): Map<String, Any> {
                return try {
                    if (!request.thread_id.isNullOrBlank()) {
                        try {
                            UUID.fromString(request.thread_id)
                        } catch (e: IllegalArgumentException) {
                            return mapOf(
                                "type" to "text",
                                "text" to "Error: thread_id must be a valid UUID format (e.g., '123e4567-e89b-12d3-a456-426614174000') or omitted entirely for global search."
                            )
                        }
                    }
                    
                    val memories = runBlocking {
                        vectorMemoryService.recall(
                            query = request.query,
                            threadId = request.thread_id?.takeIf { it.isNotBlank() },
                            limit = request.limit ?: 5
                        )
                    }

                    if (memories.isEmpty()) {
                        mapOf(
                            "type" to "text",
                            "text" to "No relevant memories found for query: ${request.query}"
                        )
                    } else {
                        val memoriesText = buildString {
                            appendLine("Found ${memories.size} relevant memories:")
                            memories.forEachIndexed { index, memory ->
                                appendLine("${index + 1}. ${memory.content}")
                            }
                        }
                        
                        logger.debug("Recalled ${memories.size} memories for query: ${request.query}")
                        
                        mapOf(
                            "type" to "text",
                            "text" to memoriesText
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error recalling memories for query: ${request.query}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error recalling memories: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("recall_memory", function)
            .description(
                """
                Recall relevant information from past conversations using semantic search.
                
                **Search Scope:**
                - Without thread_id: searches across all conversation threads
                - With thread_id: searches only in that specific thread (must be a valid UUID)
                
                **When to use:**
                - Finding user preferences, decisions, or facts from past conversations
                - Retrieving context about previously discussed topics
                - Understanding what was decided or agreed upon before
                
                **Search mechanism:** Uses AI embeddings to find semantically similar content, not just keyword matching.
                
                **Examples:**
                - "What programming language does the user prefer?" (no thread_id - searches all)
                - "What decisions were made about the database?" (no thread_id - searches all)
                - "What are user's favorite tools?" (no thread_id - searches all)
                """.trimIndent()
            )
            .inputType(object : ParameterizedTypeReference<RecallMemoryParams>() {})
            .build()
    }
}

data class RecallMemoryParams(
    val query: String,
    val thread_id: String? = null,
    val limit: Int? = 5
)
