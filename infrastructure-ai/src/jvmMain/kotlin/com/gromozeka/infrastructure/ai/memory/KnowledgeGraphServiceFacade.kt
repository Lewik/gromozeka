package com.gromozeka.infrastructure.ai.memory

import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.repository.KnowledgeGraphRepository
import com.gromozeka.domain.service.KnowledgeGraphService
import klog.KLoggers
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class KnowledgeGraphServiceFacade(
    private val knowledgeGraphRepository: KnowledgeGraphRepository,
    private val entityExtractionService: EntityExtractionService,
    private val relationshipExtractionService: RelationshipExtractionService,
    private val entityDeduplicationService: EntityDeduplicationService,
    private val embeddingModel: EmbeddingModel
) : KnowledgeGraphService {
    private val log = KLoggers.logger(this)
    private val groupId = "dev-user"

    override suspend fun saveMemoryObject(memoryObject: MemoryObject): MemoryObject {
        knowledgeGraphRepository.saveToGraph(listOf(memoryObject), emptyList())
        return memoryObject
    }
    
    override suspend fun saveMemoryLink(link: MemoryLink): MemoryLink {
        knowledgeGraphRepository.saveToGraph(emptyList(), listOf(link))
        return link
    }
    
    override suspend fun findMemoryObject(name: String): MemoryObject? {
        val results = knowledgeGraphRepository.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            RETURN n.uuid as uuid, n.name as name, n.embedding as embedding, 
                   n.summary as summary, n.group_id as groupId, labels(n) as labels, 
                   n.created_at as createdAt
            LIMIT 1
            """.trimIndent(),
            mapOf("name" to name, "groupId" to groupId)
        )
        
        return results.firstOrNull()?.let { record ->
            MemoryObject(
                uuid = record["uuid"] as String,
                name = record["name"] as String,
                embedding = (record["embedding"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() } ?: emptyList(),
                summary = record["summary"] as? String ?: "",
                groupId = record["groupId"] as String,
                labels = (record["labels"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                createdAt = Clock.System.now()
            )
        }
    }
    
    override suspend fun search(query: String, limit: Int): KnowledgeGraphService.SearchResult {
        // Simple search implementation - can be enhanced with vector similarity
        val embedding = embeddingModel.embed(query)
        
        val objectResults = knowledgeGraphRepository.executeQuery(
            """
            MATCH (n:MemoryObject {group_id: ${'$'}groupId})
            RETURN n.uuid as uuid, n.name as name, n.embedding as embedding,
                   n.summary as summary, n.group_id as groupId, labels(n) as labels,
                   n.created_at as createdAt
            LIMIT ${'$'}limit
            """.trimIndent(),
            mapOf("groupId" to groupId, "limit" to limit)
        )
        
        val objects = objectResults.map { record ->
            MemoryObject(
                uuid = record["uuid"] as String,
                name = record["name"] as String,
                embedding = (record["embedding"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() } ?: emptyList(),
                summary = record["summary"] as? String ?: "",
                groupId = record["groupId"] as String,
                labels = (record["labels"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                createdAt = Clock.System.now()
            )
        }
        
        return KnowledgeGraphService.SearchResult(objects, emptyList())
    }
    
    override suspend fun extractAndSaveToGraph(
        content: String,
        previousMessages: String
    ): String = extractAndSaveToGraph(content, previousMessages, Clock.System.now(), null)
    
    suspend fun extractAndSaveToGraph(
        content: String,
        previousMessages: String = "",
        referenceTime: Instant = Clock.System.now(),
        episodeId: String? = null
    ): String = coroutineScope {
        log.info { "Starting knowledge extraction from content (${content.length} chars)" }
        
        val entities = entityExtractionService.extractEntities(content, previousMessages)
        log.debug { "Extracted ${entities.size} entities, starting deduplication" }

        val uuidMapping = entityDeduplicationService.deduplicateExtractedEntities(
            extractedEntities = entities,
            content = content,
            previousMessages = previousMessages
        )

        log.debug { "Creating ${entities.size} entity nodes with deduplicated UUIDs" }

        val entityNodes = buildList {
            for ((index, entity) in entities.withIndex()) {
                val entityTypeId = entity.second
                val entityTypeName = entityExtractionService.entityTypes
                    .find { it.id == entityTypeId }?.name ?: "Unknown"
                
                val embedding = embeddingModel.embed(entity.first)
                val uuid = uuidMapping[index] ?: UUID.randomUUID().toString()
                
                val existingSummary = if (uuidMapping.containsKey(index)) {
                    fetchExistingSummary(uuid)
                } else {
                    null
                }
                
                val summary = entityExtractionService.generateEntitySummary(
                    entity.first, 
                    entityTypeName, 
                    content, 
                    existingSummary
                )

                add(MemoryObject(
                    uuid = uuid,
                    name = entity.first,
                    embedding = embedding.toList(),
                    summary = summary,
                    groupId = groupId,
                    labels = listOf(entityTypeName),
                    createdAt = referenceTime
                ))
            }
        }

        val relationships = if (entityNodes.size >= 2) {
            relationshipExtractionService.extractRelationships(content, entityNodes, referenceTime, episodeId)
        } else {
            log.warn { "Only ${entityNodes.size} entities extracted, skipping relationship extraction (need at least 2)" }
            emptyList()
        }

        knowledgeGraphRepository.saveToGraph(entityNodes, relationships)

        val result = "Added ${entityNodes.size} entities and ${relationships.size} relationships to knowledge graph"
        log.info { result }
        return@coroutineScope result
    }

    private suspend fun fetchExistingSummary(uuid: String): String? {
        return try {
            val results = knowledgeGraphRepository.executeQuery(
                """
                MATCH (n:MemoryObject {uuid: ${'$'}uuid, group_id: ${'$'}groupId})
                RETURN n.summary as summary
                """.trimIndent(),
                mapOf("uuid" to uuid, "groupId" to groupId)
            )
            
            results.firstOrNull()?.get("summary") as? String
        } catch (e: Exception) {
            log.warn(e) { "Failed to fetch existing summary for $uuid: ${e.message}" }
            null
        }
    }
}
