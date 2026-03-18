package com.gromozeka.infrastructure.db.graph

import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step
import com.gromozeka.domain.repository.StepRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class Neo4jStepRepository(
    private val neo4jGraphStore: Neo4jGraphStore,
    private val json: Json
) : StepRepository {

    override suspend fun create(step: Step): Step {
        val query = """
            MATCH (p:Plan {id: ${'$'}planId})
            CREATE (s:Step {
                id: ${'$'}id,
                planId: ${'$'}planId,
                text: ${'$'}text,
                type: ${'$'}type,
                certainty: ${'$'}certainty,
                entities: ${'$'}entities,
                position: ${'$'}position,
                status: ${'$'}status,
                result: ${'$'}result,
                createdAt: datetime(${'$'}createdAt),
                completedAt: ${'$'}completedAt
            })
            CREATE (p)-[:HAS_STEP]->(s)
            RETURN s
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "planId" to step.planId.value,
                "id" to step.id.value,
                "text" to step.text,
                "type" to step.type.name,
                "certainty" to step.certainty,
                "entities" to step.entities,
                "position" to step.position,
                "status" to step.status.name,
                "result" to (step.result ?: org.neo4j.driver.Values.NULL),
                "createdAt" to step.createdAt.toString(),
                "completedAt" to (step.completedAt?.toString() ?: org.neo4j.driver.Values.NULL)
            )
        )

        return step
    }

    override suspend fun createBatch(steps: List<Step>): List<Step> {
        if (steps.isEmpty()) return emptyList()

        val query = """
            MATCH (p:Plan {id: ${'$'}planId})
            UNWIND ${'$'}steps AS stepData
            CREATE (s:Step {
                id: stepData.id,
                planId: ${'$'}planId,
                text: stepData.text,
                type: stepData.type,
                certainty: stepData.certainty,
                entities: stepData.entities,
                position: stepData.position,
                status: stepData.status,
                result: stepData.result,
                createdAt: datetime(stepData.createdAt),
                completedAt: stepData.completedAt
            })
            CREATE (p)-[:HAS_STEP]->(s)
            RETURN s
        """.trimIndent()

        val stepsData = steps.map { step ->
            mapOf(
                "id" to step.id.value,
                "text" to step.text,
                "type" to step.type.name,
                "certainty" to step.certainty,
                "entities" to step.entities,
                "position" to step.position,
                "status" to step.status.name,
                "result" to step.result,
                "createdAt" to step.createdAt.toString(),
                "completedAt" to step.completedAt?.toString()
            )
        }

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "planId" to steps.first().planId.value,
                "steps" to stepsData
            )
        )

        return steps
    }

    override suspend fun addDependency(from: Step.Id, to: Step.Id) {
        val query = """
            MATCH (from:Step {id: ${'$'}fromId})
            MATCH (to:Step {id: ${'$'}toId})
            CREATE (from)-[:DEPENDS_ON]->(to)
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "fromId" to from.value,
                "toId" to to.value
            )
        )
    }

    override suspend fun addDependenciesBatch(dependencies: List<Pair<Step.Id, Step.Id>>) {
        if (dependencies.isEmpty()) return

        val query = """
            UNWIND ${'$'}dependencies AS dep
            MATCH (from:Step {id: dep.from})
            MATCH (to:Step {id: dep.to})
            CREATE (from)-[:DEPENDS_ON]->(to)
        """.trimIndent()

        val depsData = dependencies.map { (from, to) ->
            mapOf("from" to from.value, "to" to to.value)
        }

        neo4jGraphStore.executeQuery(query, mapOf("dependencies" to depsData))
    }

    override suspend fun findById(id: Step.Id): Step? {
        val query = """
            MATCH (s:Step {id: ${'$'}id})
            RETURN s
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(query, mapOf("id" to id.value))
        return results.firstOrNull()?.let { parseStep(it) }
    }

    override suspend fun findByPlanId(planId: Plan.Id): List<Step> {
        val query = """
            MATCH (p:Plan {id: ${'$'}planId})-[:HAS_STEP]->(s:Step)
            RETURN s
            ORDER BY s.position ASC
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(query, mapOf("planId" to planId.value))
        return results.mapNotNull { parseStep(it) }
    }

    override suspend fun findNextPendingStep(planId: Plan.Id): Step? {
        val query = """
            MATCH (p:Plan {id: ${'$'}planId})-[:HAS_STEP]->(s:Step {status: 'PENDING'})
            WHERE NOT EXISTS {
                MATCH (s)-[:DEPENDS_ON]->(dep:Step)
                WHERE dep.status <> 'COMPLETED'
            }
            RETURN s
            ORDER BY s.position ASC
            LIMIT 1
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(query, mapOf("planId" to planId.value))
        return results.firstOrNull()?.let { parseStep(it) }
    }

    override suspend fun findDependentSteps(stepId: Step.Id): List<Step> {
        val query = """
            MATCH (dep:Step)-[:DEPENDS_ON]->(s:Step {id: ${'$'}stepId})
            RETURN dep AS s
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(query, mapOf("stepId" to stepId.value))
        return results.mapNotNull { parseStep(it) }
    }

    override suspend fun updateStatus(id: Step.Id, status: Step.Status) {
        val completedAt = if (status in setOf(Step.Status.COMPLETED, Step.Status.FAILED)) {
            Clock.System.now()
        } else {
            null
        }

        val query = """
            MATCH (s:Step {id: ${'$'}id})
            SET s.status = ${'$'}status,
                s.completedAt = ${'$'}completedAt
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "id" to id.value,
                "status" to status.name,
                "completedAt" to (completedAt?.toString() ?: org.neo4j.driver.Values.NULL)
            )
        )
    }

    override suspend fun updateResult(id: Step.Id, result: String) {
        val query = """
            MATCH (s:Step {id: ${'$'}id})
            SET s.result = ${'$'}result
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "id" to id.value,
                "result" to result
            )
        )
    }

    override suspend fun complete(id: Step.Id, status: Step.Status, result: String) {
        require(status in setOf(Step.Status.COMPLETED, Step.Status.FAILED)) {
            "Status must be terminal (COMPLETED or FAILED)"
        }

        val completedAt = Clock.System.now()

        val query = """
            MATCH (s:Step {id: ${'$'}id})
            SET s.status = ${'$'}status,
                s.result = ${'$'}result,
                s.completedAt = ${'$'}completedAt
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            query,
            mapOf(
                "id" to id.value,
                "status" to status.name,
                "result" to result,
                "completedAt" to completedAt.toString()
            )
        )
    }

    override suspend fun updateBatch(steps: List<Step>): List<Step> {
        if (steps.isEmpty()) return emptyList()

        // Verify all steps are modifiable (PENDING or IN_PROGRESS)
        val immutableSteps = steps.filter { 
            it.status !in setOf(Step.Status.PENDING, Step.Status.IN_PROGRESS) 
        }
        if (immutableSteps.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot modify COMPLETED/FAILED steps: ${immutableSteps.map { it.id.value }}"
            )
        }

        val query = """
            UNWIND ${'$'}steps AS stepData
            MATCH (s:Step {id: stepData.id})
            SET s.text = stepData.text,
                s.type = stepData.type,
                s.certainty = stepData.certainty,
                s.entities = stepData.entities,
                s.position = stepData.position,
                s.status = stepData.status,
                s.result = stepData.result
            RETURN s
        """.trimIndent()

        val stepsData = steps.map { step ->
            mapOf(
                "id" to step.id.value,
                "text" to step.text,
                "type" to step.type.name,
                "certainty" to step.certainty,
                "entities" to step.entities,
                "position" to step.position,
                "status" to step.status.name,
                "result" to step.result
            )
        }

        neo4jGraphStore.executeQuery(query, mapOf("steps" to stepsData))

        return steps
    }

    private fun parseStep(result: Map<String, Any>): Step? {
        @Suppress("UNCHECKED_CAST")
        val data = when (val node = result["s"]) {
            is Map<*, *> -> node as Map<String, Any>
            is org.neo4j.driver.types.Node -> node.asMap()
            else -> return null
        }

        return Step(
            id = Step.Id(data["id"]?.toString() ?: return null),
            planId = Plan.Id(data["planId"]?.toString() ?: return null),
            text = data["text"]?.toString() ?: return null,
            type = Step.Type.valueOf(data["type"]?.toString() ?: return null),
            certainty = (data["certainty"] as? Number)?.toFloat() ?: 0f,
            entities = (data["entities"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            position = (data["position"] as? Number)?.toInt() ?: 0,
            status = Step.Status.valueOf(data["status"]?.toString() ?: return null),
            result = data["result"]?.toString(),
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
