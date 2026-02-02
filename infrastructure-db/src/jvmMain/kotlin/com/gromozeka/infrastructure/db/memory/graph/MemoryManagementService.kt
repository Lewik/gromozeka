package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.domain.repository.MemoryManagementService as IMemoryManagementService
import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
        summary: String?,
        validAt: String?,
        invalidAt: String?
    ): String {
        val referenceTime = Clock.System.now()  // Transaction-time
        
        // Parse temporal fields with explicit semantics
        val validTime = when (validAt?.lowercase()) {
            "always" -> com.gromozeka.domain.model.memory.TemporalConstants.ALWAYS_VALID_FROM
            "now" -> referenceTime
            null -> throw IllegalArgumentException("validAt is required (use 'always', 'now', or ISO 8601 timestamp)")
            else -> try {
                Instant.parse(validAt)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid validAt format: '$validAt'. Use 'always', 'now', or ISO 8601 timestamp", e)
            }
        }
        
        val invalidTime = when (invalidAt?.lowercase()) {
            "always" -> com.gromozeka.domain.model.memory.TemporalConstants.STILL_VALID
            "now" -> referenceTime
            null -> throw IllegalArgumentException("invalidAt is required (use 'always', 'now', or ISO 8601 timestamp)")
            else -> try {
                Instant.parse(invalidAt)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid invalidAt format: '$invalidAt'. Use 'always', 'now', or ISO 8601 timestamp", e)
            }
        }

        log.info { "Adding fact directly: $from -[$relation]-> $to (valid: $validTime, invalid: $invalidTime)" }

        // Normalize names to prevent duplicates from case/whitespace variations
        val fromNormalized = from.trim().lowercase()
        val toNormalized = to.trim().lowercase()

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

        // Nodes are timeless concepts - always use default temporal values
        val nodeValidAt = com.gromozeka.domain.model.memory.TemporalConstants.ALWAYS_VALID_FROM
        val nodeInvalidAt = com.gromozeka.domain.model.memory.TemporalConstants.STILL_VALID

        neo4jGraphStore.executeQuery(
            """
            MERGE (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
            ON CREATE SET
                n.uuid = ${'$'}uuid,
                n.name = ${'$'}name,
                n.normalized_name = ${'$'}normalizedName,
                n.embedding = ${'$'}embedding,
                n.summary = ${'$'}summary,
                n.labels = ${'$'}labels,
                n.created_at = datetime(${'$'}createdAt),
                n.valid_at = datetime(${'$'}nodeValidAt),
                n.invalid_at = datetime(${'$'}nodeInvalidAt)
            ON MATCH SET
                n.name = ${'$'}name,
                n.embedding = ${'$'}embedding,
                n.summary = CASE WHEN ${'$'}summary <> '' THEN ${'$'}summary ELSE n.summary END
            RETURN n.uuid as uuid
            """.trimIndent(),
            mapOf(
                "name" to from,
                "normalizedName" to fromNormalized,
                "groupId" to groupId,
                "uuid" to fromUuid,
                "embedding" to fromEmbedding.toList(),
                "summary" to fromSummary,
                "labels" to listOf(fromType),
                "createdAt" to referenceTime.toString(),
                "nodeValidAt" to nodeValidAt.toString(),
                "nodeInvalidAt" to nodeInvalidAt.toString()
            )
        )

        neo4jGraphStore.executeQuery(
            """
            MERGE (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
            ON CREATE SET
                n.uuid = ${'$'}uuid,
                n.name = ${'$'}name,
                n.normalized_name = ${'$'}normalizedName,
                n.embedding = ${'$'}embedding,
                n.summary = ${'$'}summary,
                n.labels = ${'$'}labels,
                n.created_at = datetime(${'$'}createdAt),
                n.valid_at = datetime(${'$'}nodeValidAt),
                n.invalid_at = datetime(${'$'}nodeInvalidAt)
            ON MATCH SET
                n.name = ${'$'}name,
                n.embedding = ${'$'}embedding
            RETURN n.uuid as uuid
            """.trimIndent(),
            mapOf(
                "name" to to,
                "normalizedName" to toNormalized,
                "groupId" to groupId,
                "uuid" to toUuid,
                "embedding" to toEmbedding.toList(),
                "summary" to toSummary,
                "labels" to listOf(toType),
                "createdAt" to referenceTime.toString(),
                "nodeValidAt" to nodeValidAt.toString(),
                "nodeInvalidAt" to nodeInvalidAt.toString()
            )
        )

        neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject {normalized_name: ${'$'}fromNormalized, group_id: ${'$'}groupId})
            MATCH (target:MemoryObject {normalized_name: ${'$'}toNormalized, group_id: ${'$'}groupId})
            MERGE (source)-[r:LINKS_TO {
                uuid: ${'$'}edgeUuid,
                group_id: ${'$'}groupId
            }]->(target)
            ON CREATE SET
                r.description = ${'$'}description,
                r.description_embedding = ${'$'}embedding,
                r.episodes = [],
                r.valid_at = datetime(${'$'}validAt),
                r.invalid_at = datetime(${'$'}invalidAt),
                r.created_at = datetime(${'$'}createdAt)
            RETURN r.uuid as uuid
            """.trimIndent(),
            mapOf(
                "fromNormalized" to fromNormalized,
                "toNormalized" to toNormalized,
                "groupId" to groupId,
                "edgeUuid" to edgeUuid,
                "description" to relation,
                "embedding" to relationEmbedding.toList(),
                "validAt" to validTime.toString(),
                "invalidAt" to invalidTime.toString(),
                "createdAt" to referenceTime.toString()
            )
        )

        return "Successfully added fact: '$from' -[$relation]-> '$to'"
    }

    override suspend fun getEntityDetails(name: String): String {
        log.info { "Getting entity details for: $name" }

        val normalizedName = name.trim().lowercase()

        val entityResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
            RETURN n.uuid as uuid, n.name as name, n.summary as summary, n.labels as labels, n.created_at as createdAt
            """.trimIndent(),
            mapOf("normalizedName" to normalizedName, "groupId" to groupId)
        )

        if (entityResult.isEmpty()) {
            return "Entity '$name' not found in knowledge graph"
        }

        val entity = entityResult.first()
        val uuid = entity["uuid"] as? String ?: ""
        val displayName = entity["name"] as? String ?: name  // Use stored display name
        val summary = entity["summary"] as? String ?: ""
        val labels = entity["labels"] as? List<*> ?: emptyList<String>()
        val createdAt = entity["createdAt"]?.toString() ?: ""

        val now = Clock.System.now()
        val outgoingResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {uuid: ${'$'}uuid})-[r:LINKS_TO]->(target:MemoryObject)
            WHERE datetime(r.invalid_at) > datetime(${'$'}asOf)
            RETURN r.description as relation, target.name as targetName
            """.trimIndent(),
            mapOf("uuid" to uuid, "asOf" to now.toString())
        )

        val outgoing = outgoingResult.map { record ->
            "${record["relation"]} -> ${record["targetName"]}"
        }

        val incomingResult = neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject)-[r:LINKS_TO]->(n:MemoryObject {uuid: ${'$'}uuid})
            WHERE datetime(r.invalid_at) > datetime(${'$'}asOf)
            RETURN source.name as sourceName, r.description as relation
            """.trimIndent(),
            mapOf("uuid" to uuid, "asOf" to now.toString())
        )

        val incoming = incomingResult.map { record ->
            "${record["sourceName"]} -> ${record["relation"]}"
        }

        return buildString {
            appendLine("Entity: $displayName")
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

        val fromNormalized = from.trim().lowercase()
        val toNormalized = to.trim().lowercase()

        val result = neo4jGraphStore.executeQuery(
            """
            MATCH (source:MemoryObject {normalized_name: ${'$'}fromNormalized, group_id: ${'$'}groupId})
                  -[r:LINKS_TO {description: ${'$'}description}]->
                  (target:MemoryObject {normalized_name: ${'$'}toNormalized, group_id: ${'$'}groupId})
            WHERE datetime(r.invalid_at) > datetime(${'$'}now)
            SET r.invalid_at = datetime(${'$'}invalidAt)
            RETURN count(r) as count
            """.trimIndent(),
            mapOf(
                "fromNormalized" to fromNormalized,
                "toNormalized" to toNormalized,
                "description" to relation,
                "groupId" to groupId,
                "now" to invalidAt.toString(),
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

        val normalizedName = name.trim().lowercase()

        val setClauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>(
            "normalizedName" to normalizedName,
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
            MATCH (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
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

        val normalizedName = name.trim().lowercase()

        val countResult = neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
            OPTIONAL MATCH (n)-[r:LINKS_TO]-()
            RETURN count(DISTINCT n) as nodeCount, count(DISTINCT r) as edgeCount
            """.trimIndent(),
            mapOf("normalizedName" to normalizedName, "groupId" to groupId)
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
                MATCH (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
                MATCH (n)-[r:LINKS_TO]-()
                DELETE r
                """.trimIndent(),
                mapOf("normalizedName" to normalizedName, "groupId" to groupId)
            )
            log.warn { "Deleted $edgeCount relationship(s) for entity: $name" }
        }

        neo4jGraphStore.executeQuery(
            """
            MATCH (n:MemoryObject {normalized_name: ${'$'}normalizedName, group_id: ${'$'}groupId})
            DELETE n
            """.trimIndent(),
            mapOf("normalizedName" to normalizedName, "groupId" to groupId)
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
