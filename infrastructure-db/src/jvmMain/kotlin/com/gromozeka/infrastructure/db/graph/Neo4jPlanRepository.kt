package com.gromozeka.infrastructure.db.graph

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.repository.PlanRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.neo4j.driver.Values
import org.springframework.stereotype.Service

@Service
class Neo4jPlanRepository(
    private val neo4jGraphStore: Neo4jGraphStore
) : PlanRepository {

    override suspend fun create(plan: Plan): Plan {
        val query = """
            CREATE (p:Plan {
                id: ${'$'}id,
                conversationId: ${'$'}conversationId,
                status: ${'$'}status,
                createdAt: datetime(${'$'}createdAt),
                completedAt: ${'$'}completedAt
            })
            RETURN p
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "id" to plan.id.value,
                "conversationId" to plan.conversationId.value,
                "status" to plan.status.name,
                "createdAt" to plan.createdAt.toString(),
                "completedAt" to (plan.completedAt?.toString() ?: Values.NULL)
            )
        )

        return plan
    }

    override suspend fun findById(id: Plan.Id): Plan? {
        val query = """
            MATCH (p:Plan {id: ${'$'}id})
            RETURN p
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(query, mapOf("id" to id.value))
        return results.firstOrNull()?.let { parsePlan(it) }
    }

    override suspend fun findByConversationId(conversationId: Conversation.Id): List<Plan> {
        val query = """
            MATCH (p:Plan {conversationId: ${'$'}conversationId})
            RETURN p
            ORDER BY p.createdAt DESC
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(
            query,
            mapOf("conversationId" to conversationId.value)
        )
        return results.mapNotNull { parsePlan(it) }
    }

    override suspend fun findActivePlans(conversationId: Conversation.Id): List<Plan> {
        val query = """
            MATCH (p:Plan {conversationId: ${'$'}conversationId, status: 'ACTIVE'})
            RETURN p
            ORDER BY p.createdAt DESC
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(
            query,
            mapOf("conversationId" to conversationId.value)
        )
        return results.mapNotNull { parsePlan(it) }
    }

    override suspend fun updateStatus(id: Plan.Id, status: Plan.Status) {
        val completedAt = if (status in setOf(Plan.Status.COMPLETED, Plan.Status.FAILED, Plan.Status.CANCELLED)) {
            Clock.System.now()
        } else {
            null
        }

        val query = """
            MATCH (p:Plan {id: ${'$'}id})
            SET p.status = ${'$'}status,
                p.completedAt = ${'$'}completedAt
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "id" to id.value,
                "status" to status.name,
                "completedAt" to (completedAt?.toString() ?: Values.NULL)
            )
        )
    }

    override suspend fun delete(id: Plan.Id) {
        val query = """
            MATCH (p:Plan {id: ${'$'}id})
            OPTIONAL MATCH (p)-[:HAS_STEP]->(s:Step)
            DETACH DELETE p, s
        """.trimIndent()

        neo4jGraphStore.executeQuery(query, mapOf("id" to id.value))
    }

    private fun parsePlan(result: Map<String, Any>): Plan? {
        @Suppress("UNCHECKED_CAST")
        val data = when (val node = result["p"]) {
            is Map<*, *> -> node as Map<String, Any>
            is org.neo4j.driver.types.Node -> node.asMap()
            else -> return null
        }
        
        return Plan(
            id = Plan.Id(data["id"]?.toString() ?: return null),
            conversationId = Conversation.Id(data["conversationId"]?.toString() ?: return null),
            status = Plan.Status.valueOf(data["status"]?.toString() ?: return null),
            createdAt = parseInstant(data["createdAt"]) ?: return null,
            completedAt = data["completedAt"]?.let { parseInstant(it) }
        )
    }

    private fun parseInstant(value: Any?): Instant? {
        return when (value) {
            null -> null
            is String -> Instant.parse(value)
            else -> Instant.parse(value.toString())
        }
    }
}
