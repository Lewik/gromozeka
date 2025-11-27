package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.domain.repository.MemoryManagementService as IMemoryManagementService
import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class MemoryManagementService(
    private val neo4jGraphStore: Neo4jGraphStore,
    private val embeddingModel: EmbeddingModel
) : IMemoryManagementService {
    private val log = KLoggers.logger(this)
    private val groupId = "dev-user"

    override suspend fun addFactDirectly(
        from: String,
        relation: String,
        to: String,
        summary: String?
    ): String {
        val referenceTime = Clock.System.now()

        log.info { "Adding fact directly: $from -[$relation]-> $to" }

        val fromEmbedding = embeddingModel.embed(from)
        val toEmbedding = embeddingModel.embed(to)
        val relationEmbedding = embeddingModel.embed(relation)

        val fromUuid = UUID.randomUUID().toString()
        val toUuid = UUID.randomUUID().toString()
        val edgeUuid = UUID.randomUUID().toString()

        val fromType = "Entity"
        val toType = "Entity"

        val fromSummary = summary ?: ""
        val toSummary = ""

        neo4jGraphStore.executeQuery(
            """
            MERGE (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            ON CREATE SET
                n.uuid = ${'$'}uuid,
                n.embedding = ${'$'}embedding,
                n.summary = ${'$'}summary,
                n.labels = ${'$'}labels,
                n.created_at = datetime(${'$'}createdAt)
            ON MATCH SET
                n.embedding = ${'$'}embedding,
                n.summary = CASE WHEN ${'$'}summary <> '' THEN ${'$'}summary ELSE n.summary END
            RETURN n.uuid as uuid
            """.trimIndent(),
            mapOf(
                "name" to from,
                "groupId" to groupId,
                "uuid" to fromUuid,
                "embedding" to fromEmbedding.toList(),
                "summary" to fromSummary,
                "labels" to listOf(fromType),
                "createdAt" to referenceTime.toString()
            )
        )

        neo4jGraphStore.executeQuery(
            """
            MERGE (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            ON CREATE SET
                n.uuid = ${'$'}uuid,
                n.embedding = ${'$'}embedding,
                n.summary = ${'$'}summary,
                n.labels = ${'$'}labels,
                n.created_at = datetime(${'$'}createdAt)
            ON MATCH SET
                n.embedding = ${'$'}embedding
            RETURN n.uuid as uuid
            """.trimIndent(),
            mapOf(
                "name" to to,
                "groupId" to groupId,
                "uuid" to toUuid,
                "embedding" to toEmbedding.toList(),
                "summary" to toSummary,
                "labels" to listOf(toType),
                "createdAt" to referenceTime.toString()
            )
        )

        neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject {name: ${'$'}fromName, group_id: ${'$'}groupId})
            MATCH (target:MemoryObject {name: ${'$'}toName, group_id: ${'$'}groupId})
            MERGE (source)-[r:LINKS_TO {
                uuid: ${'$'}edgeUuid,
                group_id: ${'$'}groupId
            }]->(target)
            ON CREATE SET
                r.description = ${'$'}description,
                r.description_embedding = ${'$'}embedding,
                r.episodes = [],
                r.valid_at = datetime(${'$'}validAt),
                r.invalid_at = null,
                r.created_at = datetime(${'$'}createdAt)
            RETURN r.uuid as uuid
            """.trimIndent(),
            mapOf(
                "fromName" to from,
                "toName" to to,
                "groupId" to groupId,
                "edgeUuid" to edgeUuid,
                "description" to relation,
                "embedding" to relationEmbedding.toList(),
                "validAt" to referenceTime.toString(),
                "createdAt" to referenceTime.toString()
            )
        )

        return "Successfully added fact: '$from' -[$relation]-> '$to'"
    }

    override suspend fun getEntityDetails(name: String): String {
        log.info { "Getting entity details for: $name" }

        val entityResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            RETURN n.uuid as uuid, n.summary as summary, n.labels as labels, n.created_at as createdAt
            """.trimIndent(),
            mapOf("name" to name, "groupId" to groupId)
        )

        if (entityResult.isEmpty()) {
            return "Entity '$name' not found in knowledge graph"
        }

        val entity = entityResult.first()
        val uuid = entity["uuid"] as? String ?: ""
        val summary = entity["summary"] as? String ?: ""
        val labels = entity["labels"] as? List<*> ?: emptyList<String>()
        val createdAt = entity["createdAt"]?.toString() ?: ""

        val outgoingResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {uuid: ${'$'}uuid})-[r:LINKS_TO]->(target:MemoryObject)
            WHERE r.invalid_at IS NULL
            RETURN r.description as relation, target.name as targetName
            """.trimIndent(),
            mapOf("uuid" to uuid)
        )

        val outgoing = outgoingResult.map { record ->
            "${record["relation"]} -> ${record["targetName"]}"
        }

        val incomingResult = neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject)-[r:LINKS_TO]->(n:MemoryObject {uuid: ${'$'}uuid})
            WHERE r.invalid_at IS NULL
            RETURN source.name as sourceName, r.description as relation
            """.trimIndent(),
            mapOf("uuid" to uuid)
        )

        val incoming = incomingResult.map { record ->
            "${record["sourceName"]} -> ${record["relation"]}"
        }

        return buildString {
            appendLine("Entity: $name")
            appendLine("Type: ${labels.firstOrNull() ?: "Unknown"}")
            appendLine("Summary: $summary")
            appendLine("Created: $createdAt")
            appendLine()
            if (outgoing.isNotEmpty()) {
                appendLine("Outgoing relationships:")
                outgoing.forEach { appendLine("  - $it") }
                appendLine()
            }
            if (incoming.isNotEmpty()) {
                appendLine("Incoming relationships:")
                incoming.forEach { appendLine("  - $it") }
            }
            if (outgoing.isEmpty() && incoming.isEmpty()) {
                appendLine("No relationships found")
            }
        }
    }

    override suspend fun invalidateFact(
        from: String,
        relation: String,
        to: String
    ): String {
        val invalidAt = Clock.System.now()

        log.info { "Invalidating fact: $from -[$relation]-> $to" }

        val result = neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject {name: ${'$'}fromName, group_id: ${'$'}groupId})
                  -[r:LINKS_TO {description: ${'$'}description}]->
                  (target:MemoryObject {name: ${'$'}toName, group_id: ${'$'}groupId})
            WHERE r.invalid_at IS NULL
            SET r.invalid_at = datetime(${'$'}invalidAt)
            RETURN count(r) as count
            """.trimIndent(),
            mapOf(
                "fromName" to from,
                "toName" to to,
                "description" to relation,
                "groupId" to groupId,
                "invalidAt" to invalidAt.toString()
            )
        )

        val count = result.firstOrNull()?.get("count") as? Long ?: 0L
        return if (count > 0) {
            log.debug { "Invalidated $count relationship(s)" }
            "Successfully invalidated fact: '$from' -[$relation]-> '$to' (marked as invalid at $invalidAt)"
        } else {
            "No matching fact found to invalidate: '$from' -[$relation]-> '$to'"
        }
    }

    override suspend fun updateEntity(
        name: String,
        newSummary: String?,
        newType: String?
    ): String {
        log.info { "Updating entity: $name (summary: ${newSummary != null}, type: ${newType != null})" }

        val setClauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>(
            "name" to name,
            "groupId" to groupId
        )

        if (newSummary != null) {
            setClauses.add("n.summary = \$newSummary")
            params["newSummary"] = newSummary
        }

        if (newType != null) {
            setClauses.add("n.labels = \$newLabels")
            params["newLabels"] = listOf(newType)
        }

        if (setClauses.isEmpty()) {
            return "No updates specified for entity '$name'"
        }

        val result = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            SET ${setClauses.joinToString(", ")}
            RETURN count(n) as count
            """.trimIndent(),
            params
        )

        val count = result.firstOrNull()?.get("count") as? Long ?: 0L
        return if (count > 0) {
            val updates = mutableListOf<String>()
            if (newSummary != null) updates.add("summary")
            if (newType != null) updates.add("type")

            "Successfully updated entity '$name' (${updates.joinToString(", ")})"
        } else {
            "Entity '$name' not found in knowledge graph"
        }
    }

    override suspend fun hardDeleteEntity(
        name: String,
        cascade: Boolean
    ): String {
        log.warn { "⚠️ HARD DELETE requested for entity: $name (cascade: $cascade)" }

        val countResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            OPTIONAL MATCH (n)-[r:LINKS_TO]-()
            RETURN count(DISTINCT n) as nodeCount, count(DISTINCT r) as edgeCount
            """.trimIndent(),
            mapOf("name" to name, "groupId" to groupId)
        )

        if (countResult.isEmpty()) {
            return "Entity '$name' not found in knowledge graph"
        }

        val counts = countResult.first()
        val nodeCount = counts["nodeCount"] as? Long ?: 0L
        val edgeCount = counts["edgeCount"] as? Long ?: 0L

        if (nodeCount == 0L) {
            return "Entity '$name' not found in knowledge graph"
        }

        if (cascade && edgeCount > 0) {
            neo4jGraphStore.executeQuery(
                """
                MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
                MATCH (n)-[r:LINKS_TO]-()
                DELETE r
                """.trimIndent(),
                mapOf("name" to name, "groupId" to groupId)
            )
            log.warn { "Deleted $edgeCount relationship(s) for entity: $name" }
        }

        neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {name: ${'$'}name, group_id: ${'$'}groupId})
            DELETE n
            """.trimIndent(),
            mapOf("name" to name, "groupId" to groupId)
        )

        log.warn { "⚠️ HARD DELETED entity: $name" }

        return buildString {
            appendLine("⚠️ PERMANENTLY DELETED entity '$name':")
            appendLine("- Nodes deleted: $nodeCount")
            if (cascade) {
                appendLine("- Relationships deleted: $edgeCount")
            } else {
                appendLine("- Relationships preserved: $edgeCount")
            }
            appendLine()
            appendLine("This operation CANNOT be undone!")
        }
    }
}
