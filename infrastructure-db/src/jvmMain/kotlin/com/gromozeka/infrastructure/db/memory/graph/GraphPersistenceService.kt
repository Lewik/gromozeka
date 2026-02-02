package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class GraphPersistenceService(
    private val neo4jGraphStore: Neo4jGraphStore
) {
    private val log = KLoggers.logger(this)

    suspend fun saveToNeo4j(
        entityNodes: List<MemoryObject>,
        relationships: List<MemoryLink>
    ) {
        log.debug { "Saving ${entityNodes.size} nodes and ${relationships.size} edges to Neo4j" }

        try {
            entityNodes.forEach { node ->
                val query = """
                    MERGE (n:MemoryObject {uuid: ${'$'}uuid})
                    SET n.name = ${'$'}name,
                        n.normalized_name = ${'$'}normalizedName,
                        n.embedding = ${'$'}embedding,
                        n.summary = ${'$'}summary,
                        n.group_id = ${'$'}groupId,
                        n.labels = ${'$'}labels,
                        n.created_at = datetime(${'$'}createdAt)
                """.trimIndent()

                neo4jGraphStore.executeQuery(
                    query,
                    mapOf(
                        "uuid" to node.uuid,
                        "name" to node.name,
                        "normalizedName" to node.normalizedName,
                        "embedding" to node.embedding,
                        "summary" to node.summary,
                        "groupId" to node.groupId,
                        "labels" to node.labels,
                        "createdAt" to node.createdAt.toString()
                    ).filterValues { it != null } as Map<String, Any>
                )
            }

            relationships.forEach { edge ->
                val query = """
                    MATCH (source:MemoryObject {uuid: ${'$'}sourceUuid})
                    MATCH (target:MemoryObject {uuid: ${'$'}targetUuid})
                    MERGE (source)-[r:LINKS_TO {uuid: ${'$'}uuid}]->(target)
                    SET r.description = ${'$'}description,
                        r.description_embedding = ${'$'}embedding,
                        r.valid_at = datetime(${'$'}validAt),
                        r.invalid_at = datetime(${'$'}invalidAt),
                        r.created_at = datetime(${'$'}createdAt),
                        r.sources = ${'$'}sources,
                        r.group_id = ${'$'}groupId
                """.trimIndent()

                neo4jGraphStore.executeQuery(
                    query,
                    mapOf(
                        "uuid" to edge.uuid,
                        "sourceUuid" to edge.sourceNodeUuid,
                        "targetUuid" to edge.targetNodeUuid,
                        "description" to edge.description,
                        "embedding" to edge.embedding,
                        "validAt" to edge.validAt?.toString(),
                        "invalidAt" to edge.invalidAt?.toString(),
                        "createdAt" to edge.createdAt.toString(),
                        "sources" to edge.sources,
                        "groupId" to edge.groupId
                    ).filterValues { it != null } as Map<String, Any>
                )
            }
            log.info { "Successfully saved ${entityNodes.size} nodes and ${relationships.size} edges to Neo4j" }
        } catch (e: Exception) {
            log.error(e) { "Failed to save to Neo4j: ${e.message}" }
            throw e
        }
    }

    suspend fun initializeIndexes() {
        log.info { "Initializing Neo4j indexes..." }

        try {
            neo4jGraphStore.executeQuery(
                """
                CREATE FULLTEXT INDEX memory_object_index IF NOT EXISTS
                FOR (n:MemoryObject) ON EACH [n.name]
                """.trimIndent()
            )
            log.info { "Fulltext index 'memory_object_index' created successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create fulltext index: ${e.message}" }
            throw e
        }

        try {
            neo4jGraphStore.executeQuery(
                """
                CREATE FULLTEXT INDEX code_spec_index IF NOT EXISTS
                FOR (n:CodeSpec) ON EACH [n.name, n.summary]
                """.trimIndent()
            )
            log.info { "Fulltext index 'code_spec_index' created successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create CodeSpec fulltext index: ${e.message}" }
            throw e
        }

        try {
            createVectorIndex()
        } catch (e: Exception) {
            log.error(e) { "Failed to create vector index: ${e.message}" }
            throw e
        }

        try {
            createCodeSpecVectorIndex()
        } catch (e: Exception) {
            log.error(e) { "Failed to create CodeSpec vector index: ${e.message}" }
            throw e
        }
    }

    suspend fun createVectorIndex() {
        log.info { "Creating vector index 'memory_object_vector' for Entity name embeddings..." }

        try {
            neo4jGraphStore.executeQuery(
                """
                CREATE VECTOR INDEX memory_object_vector IF NOT EXISTS
                FOR (n:MemoryObject) ON (n.embedding)
                OPTIONS {
                    indexConfig: {
                        `vector.dimensions`: 3072,
                        `vector.similarity_function`: 'cosine',
                        `vector.hnsw.m`: 32,
                        `vector.hnsw.ef_construction`: 200
                    }
                }
                """.trimIndent()
            )
            log.info { "Vector index 'memory_object_vector' created successfully (M=32, ef_construction=200)" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create vector index: ${e.message}" }
            throw e
        }
    }

    suspend fun createCodeSpecVectorIndex() {
        log.info { "Creating vector index 'code_spec_vector' for CodeSpec embeddings..." }

        try {
            neo4jGraphStore.executeQuery(
                """
                CREATE VECTOR INDEX code_spec_vector IF NOT EXISTS
                FOR (n:CodeSpec) ON (n.embedding)
                OPTIONS {
                    indexConfig: {
                        `vector.dimensions`: 3072,
                        `vector.similarity_function`: 'cosine',
                        `vector.hnsw.m`: 32,
                        `vector.hnsw.ef_construction`: 200
                    }
                }
                """.trimIndent()
            )
            log.info { "Vector index 'code_spec_vector' created successfully (M=32, ef_construction=200)" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create CodeSpec vector index: ${e.message}" }
            throw e
        }
    }
}
