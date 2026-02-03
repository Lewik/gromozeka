package com.gromozeka.infrastructure.db.graph

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.domain.repository.*
import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service

/**
 * Neo4j implementation of PlanRepository.
 *
 * Storage:
 * - Plan nodes with vector embeddings
 * - PlanStep nodes with vector embeddings
 * - Relationships: (Plan)-[:HAS_STEP]->(PlanStep), (PlanStep)-[:PARENT_STEP]->(PlanStep)
 *
 * Vectorization:
 * - Plan: name + description
 * - PlanStep: getTextForVectorization()
 *
 * Indexes:
 * - plan_vector: HNSW vector index on Plan.embedding
 * - plan_fulltext: Fulltext index on Plan.name and Plan.description
 * - step_vector: HNSW vector index on PlanStep.embedding
 */
@Service
class Neo4jPlanRepository(
    private val neo4jGraphStore: Neo4jGraphStore,
    private val embeddingModel: EmbeddingModel
) : PlanRepository {

    private val log = KLoggers.logger(this)

    // ============ Plan CRUD ============

    override suspend fun createPlan(plan: Plan): Plan {
        log.debug { "Creating plan: id=${plan.id}, name='${plan.name}'" }

        // Check if plan already exists
        val exists = neo4jGraphStore.executeQuery(
            "MATCH (p:Plan {id: \$id}) RETURN count(p) as count",
            mapOf("id" to plan.id.value)
        ).firstOrNull()?.get("count") as? Long ?: 0L

        if (exists > 0) {
            throw DuplicatePlanException(plan.id)
        }

        // Generate embedding
        val embedding = embeddingModel.embed(plan.getTextForVectorization()).toList()

        val cypherQuery = """
            CREATE (p:Plan {
                id: ${'$'}id,
                name: ${'$'}name,
                description: ${'$'}description,
                isTemplate: ${'$'}isTemplate,
                embedding: ${'$'}embedding,
                createdAt: datetime(${'$'}createdAt)
            })
            RETURN p
        """.trimIndent()

        val params = mapOf(
            "id" to plan.id.value,
            "name" to plan.name,
            "description" to plan.description,
            "isTemplate" to plan.isTemplate,
            "embedding" to embedding,
            "createdAt" to plan.createdAt.toString()
        )

        try {
            neo4jGraphStore.executeQuery(cypherQuery, params)
            log.debug { "Successfully created plan: id=${plan.id}" }
            return plan
        } catch (e: Exception) {
            log.error(e) { "Failed to create plan: ${e.message}" }
            throw e
        }
    }

    override suspend fun findPlanById(id: Plan.Id): Plan? {
        log.debug { "Finding plan by id: $id" }

        val cypherQuery = """
            MATCH (p:Plan {id: ${'$'}id})
            RETURN p
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(cypherQuery, mapOf("id" to id.value))
        return results.firstOrNull()?.let { row ->
            nodeToMap(row["p"])?.let { mapToPlan(it) }
        }
    }

    override suspend fun updatePlan(plan: Plan): Plan {
        log.debug { "Updating plan: id=${plan.id}" }

        // Check if plan exists
        val existing = findPlanById(plan.id)
            ?: throw PlanNotFoundException(plan.id)

        // Generate new embedding
        val embedding = embeddingModel.embed(plan.getTextForVectorization()).toList()

        val cypherQuery = """
            MATCH (p:Plan {id: ${'$'}id})
            SET p.name = ${'$'}name,
                p.description = ${'$'}description,
                p.isTemplate = ${'$'}isTemplate,
                p.embedding = ${'$'}embedding
            RETURN p
        """.trimIndent()

        val params = mapOf(
            "id" to plan.id.value,
            "name" to plan.name,
            "description" to plan.description,
            "isTemplate" to plan.isTemplate,
            "embedding" to embedding
        )

        try {
            neo4jGraphStore.executeQuery(cypherQuery, params)
            log.debug { "Successfully updated plan: id=${plan.id}" }
            return plan
        } catch (e: Exception) {
            log.error(e) { "Failed to update plan: ${e.message}" }
            throw e
        }
    }

    override suspend fun deletePlan(id: Plan.Id) {
        log.debug { "Deleting plan: id=$id" }

        // Check if plan exists
        findPlanById(id) ?: throw PlanNotFoundException(id)

        // Cascade delete: plan and all its steps
        val cypherQuery = """
            MATCH (p:Plan {id: ${'$'}id})
            OPTIONAL MATCH (p)-[:HAS_STEP]->(s:PlanStep)
            DETACH DELETE p, s
        """.trimIndent()

        try {
            neo4jGraphStore.executeQuery(cypherQuery, mapOf("id" to id.value))
            log.debug { "Successfully deleted plan and all its steps: id=$id" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete plan: ${e.message}" }
            throw e
        }
    }

    override suspend fun clonePlan(sourceId: Plan.Id, newName: String): Plan {
        log.debug { "Cloning plan: sourceId=$sourceId, newName='$newName'" }

        // Find source plan
        val sourcePlan = findPlanById(sourceId)
            ?: throw PlanNotFoundException(sourceId)

        // Find all source steps
        val sourceSteps = findStepsByPlanId(sourceId)

        // Create new plan with new ID
        val newPlan = Plan(
            id = Plan.Id.generate(),
            name = newName,
            description = sourcePlan.description,
            isTemplate = false, // Clones are not templates
            createdAt = kotlinx.datetime.Clock.System.now()
        )

        createPlan(newPlan)

        // Clone steps with new IDs, preserving tree structure
        val oldToNewIdMap = mutableMapOf<PlanStep.Id, PlanStep.Id>()

        // Generate new IDs for all steps
        sourceSteps.forEach { sourceStep ->
            val newStepId = PlanStep.Id.generate()
            oldToNewIdMap[sourceStep.id] = newStepId
        }

        // Create all step nodes (without parent relationships yet)
        sourceSteps.forEach { sourceStep ->
            val newStepId = oldToNewIdMap[sourceStep.id]!!
            val newParentId = sourceStep.parentId?.let { oldToNewIdMap[it] }

            // Generate embedding
            val embedding = embeddingModel.embed(sourceStep.getTextForVectorization()).toList()

            // Build step properties using the same method as addStep
            val clonedStep = when (sourceStep) {
                is PlanStep.Text -> PlanStep.Text(
                    id = newStepId,
                    planId = newPlan.id,
                    parentId = newParentId,
                    status = StepStatus.PENDING,
                    instruction = sourceStep.instruction,
                    result = null // Clear result for cloned step
                )
            }
            
            val stepProperties = buildStepProperties(clonedStep, embedding)

            // Create step node and link to plan
            val createStepQuery = """
                MATCH (p:Plan {id: ${'$'}planId})
                CREATE (s:PlanStep)
                SET s = ${'$'}props
                CREATE (p)-[:HAS_STEP]->(s)
            """.trimIndent()

            neo4jGraphStore.executeQuery(
                createStepQuery,
                mapOf("planId" to newPlan.id.value, "props" to stepProperties)
            )
        }

        // Create parent relationships after all steps are created
        sourceSteps.forEach { sourceStep ->
            sourceStep.parentId?.let { oldParentId ->
                val newStepId = oldToNewIdMap[sourceStep.id]!!
                val newParentId = oldToNewIdMap[oldParentId]!!

                neo4jGraphStore.executeQuery(
                    """
                    MATCH (child:PlanStep {id: ${'$'}childId})
                    MATCH (parent:PlanStep {id: ${'$'}parentId})
                    CREATE (child)-[:PARENT_STEP]->(parent)
                    """.trimIndent(),
                    mapOf("childId" to newStepId.value, "parentId" to newParentId.value)
                )
            }
        }

        log.debug { "Successfully cloned plan: sourceId=$sourceId -> newId=${newPlan.id}, steps=${sourceSteps.size}" }
        return newPlan
    }

    // ============ Plan Search ============

    override suspend fun searchPlans(query: String, limit: Int): List<Plan> = coroutineScope {
        log.debug { "Searching plans: query='$query', limit=$limit" }

        // Generate query embedding
        val queryEmbedding = embeddingModel.embed(query).toList()

        // Execute keyword and semantic searches in parallel
        val candidateLimit = maxOf(limit * 3, 20)

        val (keywordResults, semanticResults) = listOf(
            async { keywordSearchPlans(query, candidateLimit) },
            async { semanticSearchPlans(queryEmbedding, candidateLimit) }
        ).awaitAll()

        // Combine results using hybrid scoring (0.4 keyword, 0.6 semantic)
        val combinedScores = mutableMapOf<Plan.Id, Pair<Plan, Double>>()

        keywordResults.forEach { (plan, score) ->
            combinedScores[plan.id] = plan to (score * 0.4)
        }

        semanticResults.forEach { (plan, score) ->
            val existing = combinedScores[plan.id]
            val weightedScore = score * 0.6

            if (existing != null) {
                combinedScores[plan.id] = plan to (existing.second + weightedScore)
            } else {
                combinedScores[plan.id] = plan to weightedScore
            }
        }

        // Sort by combined score and take top results
        combinedScores.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    override suspend fun findAllPlans(includeTemplates: Boolean): List<Plan> {
        log.debug { "Finding all plans: includeTemplates=$includeTemplates" }

        val whereClause = if (!includeTemplates) "WHERE p.isTemplate = false" else ""

        val cypherQuery = """
            MATCH (p:Plan)
            $whereClause
            RETURN p
            ORDER BY p.createdAt DESC
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(cypherQuery)
        return results.mapNotNull { row ->
            nodeToMap(row["p"])?.let { mapToPlan(it) }
        }
    }

    // ============ Step CRUD ============

    override suspend fun addStep(step: PlanStep): PlanStep {
        log.debug { "Adding step: id=${step.id}, planId=${step.planId}" }

        // Check if plan exists
        findPlanById(step.planId) ?: throw PlanNotFoundException(step.planId)

        // Check if parent step exists (if specified)
        step.parentId?.let { parentId ->
            findStepById(parentId) ?: throw StepNotFoundException(parentId)
        }

        // Check if step already exists
        val exists = neo4jGraphStore.executeQuery(
            "MATCH (s:PlanStep {id: \$id}) RETURN count(s) as count",
            mapOf("id" to step.id.value)
        ).firstOrNull()?.get("count") as? Long ?: 0L

        if (exists > 0) {
            throw DuplicateStepException(step.id)
        }

        // Generate embedding
        val embedding = embeddingModel.embed(step.getTextForVectorization()).toList()

        // Create step node
        val stepProperties = buildStepProperties(step, embedding)

        val createStepQuery = """
            CREATE (s:PlanStep)
            SET s = ${'$'}props
            RETURN s
        """.trimIndent()

        neo4jGraphStore.executeQuery(createStepQuery, mapOf("props" to stepProperties))

        // Create relationship to plan
        val createPlanRelQuery = """
            MATCH (p:Plan {id: ${'$'}planId})
            MATCH (s:PlanStep {id: ${'$'}stepId})
            CREATE (p)-[:HAS_STEP]->(s)
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            createPlanRelQuery,
            mapOf("planId" to step.planId.value, "stepId" to step.id.value)
        )

        // Create relationship to parent step (if exists)
        step.parentId?.let { parentId ->
            val createParentRelQuery = """
                MATCH (child:PlanStep {id: ${'$'}childId})
                MATCH (parent:PlanStep {id: ${'$'}parentId})
                CREATE (child)-[:PARENT_STEP]->(parent)
            """.trimIndent()

            neo4jGraphStore.executeQuery(
                createParentRelQuery,
                mapOf("childId" to step.id.value, "parentId" to parentId.value)
            )
        }

        log.debug { "Successfully added step: id=${step.id}" }
        return step
    }

    override suspend fun findStepById(id: PlanStep.Id): PlanStep? {
        log.debug { "Finding step by id: $id" }

        val cypherQuery = """
            MATCH (s:PlanStep {id: ${'$'}id})
            RETURN s
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(cypherQuery, mapOf("id" to id.value))
        return results.firstOrNull()?.let { row ->
            nodeToMap(row["s"])?.let { mapToStep(it) }
        }
    }

    override suspend fun updateStep(step: PlanStep): PlanStep {
        log.debug { "Updating step: id=${step.id}" }

        // Check if step exists
        findStepById(step.id) ?: throw StepNotFoundException(step.id)

        // Check if new parent exists (if specified)
        step.parentId?.let { parentId ->
            findStepById(parentId) ?: throw StepNotFoundException(parentId)
        }

        // Generate new embedding
        val embedding = embeddingModel.embed(step.getTextForVectorization()).toList()

        // Update step properties
        val stepProperties = buildStepProperties(step, embedding)

        val updateStepQuery = """
            MATCH (s:PlanStep {id: ${'$'}id})
            SET s = ${'$'}props
            RETURN s
        """.trimIndent()

        neo4jGraphStore.executeQuery(
            updateStepQuery,
            mapOf("id" to step.id.value, "props" to stepProperties)
        )

        // Update parent relationship
        // First, delete existing parent relationship
        neo4jGraphStore.executeQuery(
            """
            MATCH (s:PlanStep {id: ${'$'}id})-[r:PARENT_STEP]->()
            DELETE r
            """.trimIndent(),
            mapOf("id" to step.id.value)
        )

        // Then create new parent relationship (if specified)
        step.parentId?.let { parentId ->
            neo4jGraphStore.executeQuery(
                """
                MATCH (child:PlanStep {id: ${'$'}childId})
                MATCH (parent:PlanStep {id: ${'$'}parentId})
                CREATE (child)-[:PARENT_STEP]->(parent)
                """.trimIndent(),
                mapOf("childId" to step.id.value, "parentId" to parentId.value)
            )
        }

        log.debug { "Successfully updated step: id=${step.id}" }
        return step
    }

    override suspend fun deleteStep(id: PlanStep.Id) {
        log.debug { "Deleting step: id=$id" }

        // Check if step exists
        findStepById(id) ?: throw StepNotFoundException(id)

        // Cascade delete: step and all its children
        val cypherQuery = """
            MATCH (s:PlanStep {id: ${'$'}id})
            OPTIONAL MATCH (child:PlanStep)-[:PARENT_STEP*]->(s)
            DETACH DELETE s, child
        """.trimIndent()

        try {
            neo4jGraphStore.executeQuery(cypherQuery, mapOf("id" to id.value))
            log.debug { "Successfully deleted step and all its children: id=$id" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete step: ${e.message}" }
            throw e
        }
    }

    override suspend fun findStepsByPlanId(planId: Plan.Id): List<PlanStep> {
        log.debug { "Finding steps for plan: planId=$planId" }

        // Check if plan exists
        findPlanById(planId) ?: throw PlanNotFoundException(planId)

        val cypherQuery = """
            MATCH (p:Plan {id: ${'$'}planId})-[:HAS_STEP]->(s:PlanStep)
            RETURN s
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(cypherQuery, mapOf("planId" to planId.value))
        return results.mapNotNull { row ->
            nodeToMap(row["s"])?.let { mapToStep(it) }
        }
    }

    // ============ Helper Methods ============

    private suspend fun keywordSearchPlans(query: String, limit: Int): List<Pair<Plan, Double>> {
        val cypherQuery = """
            CALL db.index.fulltext.queryNodes('plan_fulltext', ${'$'}query)
            YIELD node, score
            RETURN node, score
            ORDER BY score DESC
            LIMIT ${'$'}limit
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(cypherQuery, mapOf("query" to query, "limit" to limit))
        return results.mapNotNull { row ->
            val nodeData = nodeToMap(row["node"]) ?: return@mapNotNull null
            val plan = mapToPlan(nodeData)
            val score = (row["score"] as Number).toDouble()
            plan?.let { it to score }
        }
    }

    private suspend fun semanticSearchPlans(queryEmbedding: List<Float>, limit: Int): List<Pair<Plan, Double>> {
        val cypherQuery = """
            CALL db.index.vector.queryNodes('plan_vector', ${'$'}limit, ${'$'}queryEmbedding)
            YIELD node, score
            RETURN node, score
            ORDER BY score DESC
        """.trimIndent()

        val results = neo4jGraphStore.executeQuery(
            cypherQuery,
            mapOf("limit" to limit, "queryEmbedding" to queryEmbedding)
        )

        return results.mapNotNull { row ->
            val nodeData = nodeToMap(row["node"]) ?: return@mapNotNull null
            val plan = mapToPlan(nodeData)
            val score = (row["score"] as Number).toDouble()
            plan?.let { it to score }
        }
    }

    /**
     * Convert Neo4j Node to Map for mapping.
     */
    private fun nodeToMap(value: Any?): Map<String, Any>? {
        return when (value) {
            is org.neo4j.driver.types.Node -> value.asMap()
            is Map<*, *> -> value as Map<String, Any>
            else -> null
        }
    }

    private fun buildStepProperties(step: PlanStep, embedding: List<Float>): Map<String, Any> {
        val baseProperties = mutableMapOf<String, Any>(
            "id" to step.id.value,
            "planId" to step.planId.value,
            "status" to step.status.name,
            "embedding" to embedding,
            "stepType" to when (step) {
                is PlanStep.Text -> "Text"
            }
        )

        step.parentId?.let { baseProperties["parentId"] = it.value }

        // Add type-specific properties
        when (step) {
            is PlanStep.Text -> {
                baseProperties["instruction"] = step.instruction
                step.result?.let { baseProperties["result"] = it }
            }
        }

        return baseProperties
    }

    private fun mapToPlan(data: Map<String, Any>): Plan? {
        return try {
            val createdAt = when (val value = data["createdAt"]) {
                is java.time.ZonedDateTime -> kotlinx.datetime.Instant.fromEpochMilliseconds(value.toInstant().toEpochMilli())
                else -> return null
            }

            Plan(
                id = Plan.Id(data["id"] as String),
                name = data["name"] as String,
                description = data["description"] as String,
                isTemplate = data["isTemplate"] as Boolean,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            log.error(e) { "Failed to map Plan from Neo4j data: ${e.message}" }
            null
        }
    }

    private fun mapToStep(data: Map<String, Any>): PlanStep? {
        return try {
            val stepType = data["stepType"] as String
            val id = PlanStep.Id(data["id"] as String)
            val planId = Plan.Id(data["planId"] as String)
            val parentId = (data["parentId"] as? String)?.let { PlanStep.Id(it) }
            val status = StepStatus.valueOf(data["status"] as String)

            when (stepType) {
                "Text" -> PlanStep.Text(
                    id = id,
                    planId = planId,
                    parentId = parentId,
                    status = status,
                    instruction = data["instruction"] as String,
                    result = data["result"] as? String
                )
                else -> {
                    log.warn { "Unknown step type: $stepType" }
                    null
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to map PlanStep from Neo4j data: ${e.message}" }
            null
        }
    }

    /**
     * Initialize Neo4j indexes for plans and steps.
     * Should be called on application startup.
     */
    suspend fun initializeIndexes() {
        log.info { "Initializing plan indexes..." }

        try {
            // Create vector index for plans
            neo4jGraphStore.executeQuery(
                """
                CREATE VECTOR INDEX plan_vector IF NOT EXISTS
                FOR (p:Plan) ON (p.embedding)
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
            log.info { "Vector index 'plan_vector' created successfully" }

            // Create fulltext index for plans
            neo4jGraphStore.executeQuery(
                """
                CREATE FULLTEXT INDEX plan_fulltext IF NOT EXISTS
                FOR (p:Plan) ON EACH [p.name, p.description]
                """.trimIndent()
            )
            log.info { "Fulltext index 'plan_fulltext' created successfully" }

            // Create vector index for steps
            neo4jGraphStore.executeQuery(
                """
                CREATE VECTOR INDEX step_vector IF NOT EXISTS
                FOR (s:PlanStep) ON (s.embedding)
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
            log.info { "Vector index 'step_vector' created successfully" }

        } catch (e: Exception) {
            log.error(e) { "Failed to initialize indexes: ${e.message}" }
            throw e
        }
    }
}
