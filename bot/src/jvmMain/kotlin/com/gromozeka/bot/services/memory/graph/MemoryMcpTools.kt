package com.gromozeka.bot.services.memory.graph

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

data class AddToGraphParams(
    val content: String,
    val previousMessages: String? = null
)

data class AddFactParams(
    val from: String,
    val relation: String,
    val to: String,
    val summary: String? = null
)

data class GetEntityDetailsParams(
    val name: String
)

data class InvalidateFactParams(
    val from: String,
    val relation: String,
    val to: String
)

data class UpdateEntityParams(
    val name: String,
    val newSummary: String? = null,
    val newType: String? = null
)

data class HardDeleteEntityParams(
    val name: String,
    val cascade: Boolean = true
)

@Configuration
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class MemoryMcpTools(
    private val knowledgeGraphService: KnowledgeGraphService
) {
    private val logger = LoggerFactory.getLogger(MemoryMcpTools::class.java)

    @Bean
    fun addToGraphTool(): ToolCallback {
        val function = object : BiFunction<AddToGraphParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: AddToGraphParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val result = runBlocking {
                        knowledgeGraphService.extractAndSaveToGraph(
                            content = request.content,
                            previousMessages = request.previousMessages ?: ""
                        )
                    }

                    logger.debug("Added to graph: $result")

                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                } catch (e: Exception) {
                    logger.error("Error adding to graph: ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error adding to knowledge graph: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("build_memory_from_text", function)
            .description("""
                Remember information from text by building memory objects and links using LLM extraction.

                **Usage:**
                - Extract entities (people, technologies, concepts, etc.) from the content
                - Identify relationships between entities with temporal information
                - Store in the knowledge graph with bi-temporal tracking

                **Parameters:**
                - content: The text content to extract from (required)
                - previousMessages: Optional context from previous conversation

                **Returns:** Confirmation message with count of entities and relationships added
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<AddToGraphParams>() {})
            .build()
    }

    @Bean
    fun addFactTool(): ToolCallback {
        val function = object : BiFunction<AddFactParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: AddFactParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val result = runBlocking {
                        knowledgeGraphService.addFactDirectly(
                            from = request.from,
                            relation = request.relation,
                            to = request.to,
                            summary = request.summary
                        )
                    }

                    logger.debug("Added fact directly: ${request.from} -[${request.relation}]-> ${request.to}")

                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                } catch (e: Exception) {
                    logger.error("Error adding fact: ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error adding fact to knowledge graph: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("add_memory_link", function)
            .description("""
                Directly add a fact to the knowledge graph without LLM parsing.
                Use this when the user provides explicit, structured information.

                **Usage:**
                - User gives ready information: "Gromozeka is written in Kotlin"
                - No LLM extraction needed - creates entities and relationship directly
                - Faster and more accurate than parsing through build_memory_from_text

                **Parameters:**
                - from: Source entity name (e.g., "Gromozeka")
                - relation: Relationship type (e.g., "written in", "uses", "created by")
                - to: Target entity name (e.g., "Kotlin")
                - summary: Optional summary for source entity

                **Returns:** Confirmation message with created entities and relationship
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<AddFactParams>() {})
            .build()
    }

    @Bean
    fun getEntityDetailsTool(): ToolCallback {
        val function = object : BiFunction<GetEntityDetailsParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: GetEntityDetailsParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val details = runBlocking {
                        knowledgeGraphService.getEntityDetails(request.name)
                    }

                    logger.debug("Retrieved entity details for: ${request.name}")

                    mapOf(
                        "type" to "text",
                        "text" to details
                    )
                } catch (e: Exception) {
                    logger.error("Error getting entity details for '${request.name}': ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error getting entity details: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("get_memory_object", function)
            .description("""
                Get detailed information about a specific entity in the knowledge graph.

                **Returns:**
                - Entity summary and type
                - All outgoing relationships (entity -> other)
                - All incoming relationships (other -> entity)
                - Creation timestamp
                - Related entities

                **Parameters:**
                - name: Entity name to look up (required)

                **Use Cases:**
                - Before updating: check what information exists
                - User asks: "What do we know about X?"
                - Debug: verify entity was created correctly
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<GetEntityDetailsParams>() {})
            .build()
    }

    @Bean
    fun invalidateFactTool(): ToolCallback {
        val function = object : BiFunction<InvalidateFactParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: InvalidateFactParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val result = runBlocking {
                        knowledgeGraphService.invalidateFact(
                            from = request.from,
                            relation = request.relation,
                            to = request.to
                        )
                    }

                    logger.debug("Invalidated fact: ${request.from} -[${request.relation}]-> ${request.to}")

                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                } catch (e: Exception) {
                    logger.error("Error invalidating fact: ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error invalidating fact: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("invalidate_memory_link", function)
            .description("""
                Mark a fact as outdated/invalid without deleting it (preserves history).
                Use this when correcting mistakes or updating information.

                **Bi-temporal Model:**
                - Sets invalid_at = current_timestamp
                - Fact remains in database for history
                - Future searches exclude invalidated facts
                - Can query historical state: "What was true on date X?"

                **Parameters:**
                - from: Source entity name
                - relation: Relationship type
                - to: Target entity name

                **Use Cases:**
                - User corrects: "No, Gromozeka is NOT written in Java"
                - Outdated info: "We no longer use PostgreSQL"
                - Mistake correction: invalidate wrong fact, add correct one

                **Returns:** Confirmation message
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<InvalidateFactParams>() {})
            .build()
    }

    @Bean
    fun updateEntityTool(): ToolCallback {
        val function = object : BiFunction<UpdateEntityParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: UpdateEntityParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val result = runBlocking {
                        knowledgeGraphService.updateEntity(
                            name = request.name,
                            newSummary = request.newSummary,
                            newType = request.newType
                        )
                    }

                    logger.debug("Updated entity: ${request.name}")

                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                } catch (e: Exception) {
                    logger.error("Error updating entity '${request.name}': ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error updating entity: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("update_memory_object", function)
            .description("""
                Update an existing entity's summary or type in the knowledge graph.

                **What Can Be Updated:**
                - Summary: Refine or add details to entity description
                - Type: Change entity classification (e.g., Concept -> Technology)

                **Parameters:**
                - name: Entity name to update (required)
                - newSummary: New summary text (optional)
                - newType: New entity type (optional)

                **Use Cases:**
                - User refines: "Add to Gromozeka summary that it has voice interface"
                - Reclassify: Change type from "Concept" to "Technology"
                - Enhance: Add missing details to existing summary

                **Returns:** Confirmation message with updated fields
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<UpdateEntityParams>() {})
            .build()
    }

    @Bean
    fun hardDeleteEntityTool(): ToolCallback {
        val function = object : BiFunction<HardDeleteEntityParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: HardDeleteEntityParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val result = runBlocking {
                        knowledgeGraphService.hardDeleteEntity(
                            name = request.name,
                            cascade = request.cascade
                        )
                    }

                    logger.warn("HARD DELETED entity: ${request.name} (cascade: ${request.cascade})")

                    mapOf(
                        "type" to "text",
                        "text" to result
                    )
                } catch (e: Exception) {
                    logger.error("Error hard deleting entity '${request.name}': ${e.message}", e)
                    mapOf(
                        "type" to "text",
                        "text" to "Error hard deleting entity: ${e.message}"
                    )
                }
            }
        }

        return FunctionToolCallback.builder("delete_memory_object", function)
            .description("""
                ⚠️ DANGER: Permanently delete an entity from the knowledge graph (NO UNDO!)

                **THIS IS A DESTRUCTIVE OPERATION:**
                - Entity is permanently removed from Neo4j database
                - NO history preservation (unlike invalidate_memory_link)
                - NO way to recover deleted data
                - If cascade=true, ALL relationships are also deleted

                **USE WITH EXTREME CAUTION!**

                **Parameters:**
                - name: Entity name to permanently delete (required)
                - cascade: Delete all relationships too (default: true)

                **When to Use:**
                - Removing test/dummy data
                - Cleaning up incorrectly created entities
                - Removing sensitive data that must be purged

                **When NOT to Use:**
                - Correcting mistakes → use invalidate_memory_link instead
                - Updating information → use update_memory_object instead
                - Temporary removal → use invalidate_memory_link instead

                **Safe Alternative:**
                Use invalidate_memory_link() for soft delete with history preservation!

                **Returns:** Confirmation message with count of deleted nodes and relationships
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<HardDeleteEntityParams>() {})
            .build()
    }
}
