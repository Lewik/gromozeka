package com.gromozeka.bot.services.memory.graph

import com.gromozeka.bot.services.memory.graph.models.MemoryObject
import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.neo4j.driver.Driver
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class KnowledgeGraphServiceFacade(
    private val neo4jDriver: Driver?,
    private val neo4jGraphStore: Neo4jGraphStore,
    private val entityExtractionService: EntityExtractionService,
    private val relationshipExtractionService: RelationshipExtractionService,
    private val entityDeduplicationService: EntityDeduplicationService,
    private val graphPersistenceService: GraphPersistenceService,
    private val embeddingModel: EmbeddingModel
) {
    private val log = KLoggers.logger(this)
    private val groupId = "dev-user"

    suspend fun extractAndSaveToGraph(
        content: String,
        previousMessages: String = "",
        referenceTime: Instant = Clock.System.now(),
        episodeId: String? = null
    ): String = coroutineScope {
        log.info { "Starting knowledge extraction from content (${content.length} chars)" }
        
        if (neo4jDriver == null) {
            log.warn { "Neo4j driver not available, cannot extract to graph" }
            return@coroutineScope "Neo4j driver not available"
        }

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

        graphPersistenceService.saveToNeo4j(entityNodes, relationships)

        val result = "Added ${entityNodes.size} entities and ${relationships.size} relationships to knowledge graph"
        log.info { result }
        return@coroutineScope result
    }

    private suspend fun fetchExistingSummary(uuid: String): String? {
        return try {
            val results = neo4jGraphStore.executeQuery(
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
