package com.gromozeka.infrastructure.ai.memory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gromozeka.infrastructure.ai.springai.ChatModelFactory
import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.model.AIProvider
import klog.KLoggers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.datetime.Instant
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class RelationshipExtractionService(
    @Lazy
    private val chatModelFactory: ChatModelFactory,
    private val embeddingModel: EmbeddingModel,
    @Value("\${gromozeka.ai.provider:CLAUDE_CODE}")
    private val aiProvider: String,
    @Value("\${gromozeka.ai.gemini.model:gemini-2.0-flash-thinking-exp-01-21}")
    private val geminiModel: String,
    @Value("\${gromozeka.ai.claude.model:claude-haiku-4-5}")
    private val claudeModel: String,
    @Value("\${gromozeka.ai.ollama.model:llama3}")
    private val ollamaModel: String,
    @Value("\${gromozeka.ai.anthropic.model:haiku}")
    private val anthropicModel: String,
) {
    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()
    private val groupId = "dev-user"

    private fun getChatModel() = chatModelFactory.get(
        provider = AIProvider.valueOf(aiProvider),
        modelName = when (AIProvider.valueOf(aiProvider)) {
            AIProvider.GEMINI -> geminiModel
            AIProvider.CLAUDE_CODE -> claudeModel
            AIProvider.OLLAMA -> ollamaModel
            AIProvider.OPEN_AI -> TODO()
            AIProvider.ANTHROPIC -> anthropicModel
        },
        projectPath = null
    )

    suspend fun extractRelationships(
        content: String,
        entityNodes: List<MemoryObject>,
        referenceTime: Instant,
        episodeId: String?
    ): List<MemoryLink> {
        log.debug { "Extracting relationships between ${entityNodes.size} entities" }
        
        val entitiesWithIds = entityNodes.mapIndexed { index, node -> (index + 1) to node.name }
        
        val prompt = MemoryExtractionPrompts.extractRelationshipsPrompt(
            content = content,
            entities = entitiesWithIds,
            referenceTime = referenceTime
        )
        
        log.info { "=== RELATIONSHIP EXTRACTION PROMPT ===" }
        log.info { "Prompt length: ${prompt.length} chars" }
        log.info { "Entities count: ${entityNodes.size}" }
        log.info { "Content: ${content.take(500)}..." }

        val chatModel = getChatModel()
        
        val response = chatModel.stream(Prompt(UserMessage(prompt))).collectList().awaitSingle().lastOrNull()
            ?.result?.output?.text

        log.info { "=== RELATIONSHIP EXTRACTION RESPONSE ===" }
        log.info { "Response: ${response ?: "NULL"}" }
        log.debug { "LLM response for relationship extraction (full): $response" }

        val relationships = parseRelationshipsResponse(response ?: "", entityNodes, referenceTime, episodeId)
        log.info { "Extracted ${relationships.size} relationships from content" }
        
        return relationships
    }

    suspend fun parseRelationshipsResponse(
        response: String,
        entityNodes: List<MemoryObject>,
        referenceTime: Instant,
        episodeId: String?
    ): List<MemoryLink> {
        log.info { "=== PARSING RELATIONSHIPS RESPONSE ===" }
        log.info { "Raw response length: ${response.length}" }
        log.info { "Raw response: $response" }
        log.info { "Entity nodes count: ${entityNodes.size}" }
        
        return try {
            log.debug { "Raw response before parsing (${response.length} chars): ${response.take(500)}..." }
            
            var cleanResponse = response.trim()
            if (cleanResponse.startsWith("```json")) {
                cleanResponse = cleanResponse.removePrefix("```json").trim()
            } else if (cleanResponse.startsWith("```")) {
                cleanResponse = cleanResponse.removePrefix("```").trim()
            }
            if (cleanResponse.endsWith("```")) {
                cleanResponse = cleanResponse.removeSuffix("```").trim()
            }
            
            log.debug { "After markdown removal (${cleanResponse.length} chars): ${cleanResponse.take(500)}..." }
            
            val jsonStart = cleanResponse.indexOf("{")
            val jsonEnd = cleanResponse.lastIndexOf("}") + 1
            
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                log.warn { "No JSON found in relationship extraction response. Full response: $cleanResponse" }
                return emptyList()
            }

            val jsonStr = cleanResponse.substring(jsonStart, jsonEnd)
            log.debug { "Extracted JSON string (${jsonStr.length} chars): $jsonStr" }
            
            val json: JsonNode = objectMapper.readTree(jsonStr)
            val edges = json.get("edges")
            
            if (edges == null) {
                log.warn { "No 'edges' field in response JSON. Available fields: ${json.fieldNames().asSequence().toList()}" }
                return emptyList()
            }

            val edgesList = edges.toList()
            log.debug { "Processing ${edgesList.size} edges from JSON" }

            buildList {
                for (edge in edgesList) {
                    val sourceId = edge.get("source_entity_id")?.asInt()
                    val targetId = edge.get("target_entity_id")?.asInt()
                    
                    if (sourceId == null || targetId == null) {
                        log.warn { "Missing or invalid entity IDs: source=$sourceId, target=$targetId, edge fields: ${edge.fieldNames().asSequence().toList()}" }
                        continue
                    }
                    
                    if (sourceId < 1 || sourceId > entityNodes.size || 
                        targetId < 1 || targetId > entityNodes.size) {
                        log.warn { "Invalid entity IDs in edge: source=$sourceId, target=$targetId (entities: ${entityNodes.size})" }
                        continue
                    }

                    val sourceNode = entityNodes[sourceId - 1]
                    val targetNode = entityNodes[targetId - 1]
                    val relationType = edge.get("relation_type")?.asText() ?: continue
                    val fact = edge.get("fact")?.asText() ?: continue
                    val validAtStr = edge.get("valid_at")?.asText()
                    val invalidAtStr = edge.get("invalid_at")?.asText()

                    val embedding = embeddingModel.embed(fact)

                    add(MemoryLink(
                        uuid = UUID.randomUUID().toString(),
                        sourceNodeUuid = sourceNode.uuid,
                        targetNodeUuid = targetNode.uuid,
                        relationType = relationType,
                        description = fact,
                        embedding = embedding.toList(),
                        validAt = parseInstant(validAtStr),
                        invalidAt = parseInstant(invalidAtStr),
                        createdAt = referenceTime,
                        sources = if (episodeId != null) listOf(episodeId) else emptyList(),
                        groupId = groupId
                    ))
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to parse relationships from response. Full response: $response. Exception: ${e.message}" }
            emptyList()
        }
    }

    private fun parseInstant(dateStr: String?): Instant {
        if (dateStr.isNullOrBlank()) return com.gromozeka.domain.model.memory.TemporalConstants.ALWAYS_VALID_FROM
        
        return try {
            Instant.parse(dateStr)
        } catch (e: Exception) {
            log.warn { "Failed to parse datetime '$dateStr': ${e.message}" }
            com.gromozeka.domain.model.memory.TemporalConstants.ALWAYS_VALID_FROM
        }
    }
}
