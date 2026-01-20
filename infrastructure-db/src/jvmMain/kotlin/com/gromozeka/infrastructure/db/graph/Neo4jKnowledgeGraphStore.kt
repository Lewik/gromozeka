package com.gromozeka.infrastructure.db.graph

import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.repository.KnowledgeGraphStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class Neo4jKnowledgeGraphStore(
    private val neo4jGraphStore: Neo4jGraphStore
) : KnowledgeGraphStore {

    override suspend fun saveEntities(entities: List<MemoryObject>) {
        entities.forEach { node ->
            val query = """
                MERGE (n:MemoryObject {uuid: ${'$'}uuid})
                SET n.name = ${'$'}name,
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
                    "embedding" to node.embedding,
                    "summary" to node.summary,
                    "groupId" to node.groupId,
                    "labels" to node.labels,
                    "createdAt" to node.createdAt.toString()
                ).filterValues { it != null } as Map<String, Any>
            )
        }
    }

    override suspend fun saveRelationships(relationships: List<MemoryLink>) {
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
    }

    override suspend fun saveToGraph(
        entities: List<MemoryObject>,
        relationships: List<MemoryLink>
    ) {
        saveEntities(entities)
        saveRelationships(relationships)
    }

    override suspend fun findEntityByName(name: String, groupId: String): MemoryObject? {
        val results = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            RETURN n
            """.trimIndent(),
            mapOf("name" to name, "groupId" to groupId)
        )

        return results.firstOrNull()?.let { parseMemoryObject(it) }
    }

    override suspend fun findEntityByUuid(uuid: String): MemoryObject? {
        val results = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {uuid: ${'$'}uuid})
            RETURN n
            """.trimIndent(),
            mapOf("uuid" to uuid)
        )

        return results.firstOrNull()?.let { parseMemoryObject(it) }
    }

    override suspend fun executeQuery(
        cypher: String,
        params: Map<String, Any>
    ): List<Map<String, Any>> {
        return neo4jGraphStore.executeQuery(cypher, params)
    }

    override suspend fun syncProject(project: com.gromozeka.domain.model.Project) {
        val query = """
            MERGE (p:MemoryObject:Project {project_id: ${'$'}projectId})
            SET p.uuid = COALESCE(p.uuid, randomUUID()),
                p.name = ${'$'}name,
                p.group_id = ${'$'}groupId,
                p.labels = ['Project'],
                p.created_at = COALESCE(p.created_at, datetime())
            RETURN p.uuid as uuid
        """.trimIndent()

        val displayName = project.name.takeIf { it.isNotBlank() } 
            ?: project.path.substringAfterLast('/')
        
        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "projectId" to project.id.value,
                "name" to displayName,
                "groupId" to "dev-user" // TODO: Get from context
            )
        )
    }

    override suspend fun deleteCodeSpecsByProject(projectId: String): Int {
        val query = """
            MATCH (spec)-[:BELONGS_TO_PROJECT]->(p:Project {project_id: ${'$'}projectId})
            WHERE spec:DomainInterface OR spec:DomainClass OR spec:DomainFunction 
               OR spec:DomainProperty OR spec:DomainConstructor OR spec:DomainEnum
            DETACH DELETE spec
            RETURN count(spec) as deletedCount
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(
            query,
            mapOf("projectId" to projectId)
        )

        return results.firstOrNull()?.get("deletedCount")?.let {
            when (it) {
                is Long -> it.toInt()
                is Int -> it
                else -> 0
            }
        } ?: 0
    }

    override suspend fun findProjectEntity(projectId: String): MemoryObject? {
        val results = neo4jGraphStore.executeQuery(
            """
            MATCH (p:Project {project_id: ${'$'}projectId})
            RETURN p
            """.trimIndent(),
            mapOf("projectId" to projectId)
        )

        return results.firstOrNull()?.let { row ->
            @Suppress("UNCHECKED_CAST")
            val nodeData = row["p"] as? Map<String, Any> ?: return null
            parseMemoryObject(nodeData)
        }
    }

    private fun parseMemoryObject(data: Map<String, Any>): MemoryObject? {
        return try {
            MemoryObject(
                uuid = data["uuid"] as? String ?: return null,
                name = data["name"] as? String ?: return null,
                embedding = (data["embedding"] as? List<*>)?.mapNotNull { it as? Float },
                summary = data["summary"] as? String ?: "",
                groupId = data["group_id"] as? String ?: "",
                labels = (data["labels"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                createdAt = kotlinx.datetime.Instant.parse(data["created_at"] as? String ?: return null)
            )
        } catch (e: Exception) {
            null
        }
    }
}
