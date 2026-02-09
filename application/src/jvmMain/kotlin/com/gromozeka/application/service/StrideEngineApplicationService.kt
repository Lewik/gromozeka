package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step
import com.gromozeka.domain.repository.PlanRepository
import com.gromozeka.domain.repository.StepRepository
import com.gromozeka.domain.service.StepDefinition
import com.gromozeka.domain.service.PlanSummary
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for Stride Engine orchestration.
 *
 * Implements domain service interface by coordinating:
 * - PlanRepository (Neo4j graph storage)
 * - StepRepository (Neo4j graph storage)
 * - Topological sorting for dependency validation
 * - Cascading invalidation for dependency failures
 *
 * Business rules:
 * - Plans created from step definitions with dependency graph
 * - Steps execute in order respecting dependencies
 * - Failed steps invalidate all transitive dependents
 * - Plan completes when all steps are in terminal states
 */
@Service
class StrideEngineApplicationService(
    private val planRepository: PlanRepository,
    private val stepRepository: StepRepository
) : StrideEngineService {
    private val log = KLoggers.logger(this)

    /**
     * Creates execution plan with dependency graph validation.
     *
     * Workflow:
     * 1. Create Plan entity (status = EXECUTING)
     * 2. Create Step entities from definitions (status = PENDING)
     * 3. Perform topological sort to validate dependencies and assign positions
     * 4. Save Plan and Steps to Neo4j
     * 5. Create DEPENDS_ON relationships
     *
     * @throws IllegalArgumentException if dependencies form cycle or reference invalid indices
     */
    @Transactional
    override suspend fun createPlan(
        conversationId: Conversation.Id,
        stepDefinitions: List<StepDefinition>
    ): Plan {
        require(stepDefinitions.isNotEmpty()) {
            "Cannot create plan with empty step definitions"
        }

        val now = Clock.System.now()
        val planId = Plan.Id(uuid7())

        // Create Plan entity
        val plan = Plan(
            id = planId,
            conversationId = conversationId,
            status = Plan.Status.EXECUTING,
            createdAt = now,
            completedAt = null
        )

        planRepository.create(plan)
        log.info { "Created plan ${plan.id} for conversation ${conversationId}" }

        // Perform topological sort to get execution order and validate dependencies
        val sortedIndices = topologicalSort(stepDefinitions)

        // Create Step entities in topological order
        val steps = sortedIndices.mapIndexed { position, originalIndex ->
            val def = stepDefinitions[originalIndex]
            Step(
                id = Step.Id(uuid7()),
                planId = planId,
                text = def.text,
                type = def.type,
                certainty = def.certainty,
                entities = def.entities,
                meta = if (def.meta.isEmpty()) JsonObject(emptyMap()) else kotlinx.serialization.json.JsonObject(def.meta.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) }),
                position = position,
                status = Step.Status.PENDING,
                result = null,
                createdAt = now,
                completedAt = null
            )
        }

        // Save steps to repository
        stepRepository.createBatch(steps)

        // Create DEPENDS_ON relationships
        sortedIndices.forEachIndexed { newIndex, originalIndex ->
            val def = stepDefinitions[originalIndex]
            val currentStep = steps[newIndex]

            def.dependsOn.forEach { dependsOnOriginalIndex ->
                // Find new position of dependency
                val dependsOnNewIndex = sortedIndices.indexOf(dependsOnOriginalIndex)
                if (dependsOnNewIndex != -1) {
                    val dependencyStep = steps[dependsOnNewIndex]
                    stepRepository.addDependency(currentStep.id, dependencyStep.id)
                }
            }
        }

        log.info { "Created plan ${plan.id} with ${steps.size} steps" }

        return plan
    }

    /**
     * Topological sort using Kahn's algorithm.
     *
     * Returns list of original indices in execution order (dependencies before dependents).
     *
     * @throws IllegalArgumentException if graph has cycles or invalid dependencies
     */
    private fun topologicalSort(stepDefinitions: List<StepDefinition>): List<Int> {
        val n = stepDefinitions.size
        
        // Validate dependency indices
        stepDefinitions.forEachIndexed { index, def ->
            def.dependsOn.forEach { depIndex ->
                require(depIndex in 0 until n) {
                    "Step $index has invalid dependency index $depIndex (must be in 0..${n-1})"
                }
                require(depIndex != index) {
                    "Step $index cannot depend on itself"
                }
            }
        }

        // Build adjacency list and in-degree count
        val adjacencyList = List(n) { mutableListOf<Int>() }
        val inDegree = IntArray(n)

        stepDefinitions.forEachIndexed { index, def ->
            def.dependsOn.forEach { depIndex ->
                adjacencyList[depIndex].add(index)
                inDegree[index]++
            }
        }

        // Kahn's algorithm
        val queue = ArrayDeque<Int>()
        val result = mutableListOf<Int>()

        // Add all nodes with no dependencies
        inDegree.forEachIndexed { index, degree ->
            if (degree == 0) {
                queue.add(index)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)

            // Reduce in-degree for dependents
            adjacencyList[current].forEach { dependent ->
                inDegree[dependent]--
                if (inDegree[dependent] == 0) {
                    queue.add(dependent)
                }
            }
        }

        // Check for cycles
        if (result.size != n) {
            throw IllegalArgumentException(
                "Dependency graph has cycles. Processed ${result.size} of $n steps. " +
                "Remaining steps: ${(0 until n).filter { it !in result }}"
            )
        }

        return result
    }

    @Transactional
    override suspend fun getNextStep(planId: Plan.Id): Step? {
        // Repository already implements this logic
        return stepRepository.findNextPendingStep(planId)
    }

    @Transactional
    override suspend fun startStep(stepId: Step.Id) {
        val step = stepRepository.findById(stepId)
            ?: throw IllegalStateException("Step not found: $stepId")

        require(step.status == Step.Status.PENDING) {
            "Cannot start step ${step.id}: current status is ${step.status}, expected PENDING"
        }

        stepRepository.updateStatus(stepId, Step.Status.EXECUTING)
        log.debug { "Started step ${step.id}" }
    }

    @Transactional
    override suspend fun completeStep(stepId: Step.Id, result: String) {
        val step = stepRepository.findById(stepId)
            ?: throw IllegalStateException("Step not found: $stepId")

        require(step.status == Step.Status.EXECUTING) {
            "Cannot complete step ${step.id}: current status is ${step.status}, expected EXECUTING"
        }

        stepRepository.complete(stepId, Step.Status.COMPLETED, result)
        log.debug { "Completed step ${step.id}" }
    }

    @Transactional
    override suspend fun failStep(stepId: Step.Id, error: String): List<Step> {
        val step = stepRepository.findById(stepId)
            ?: throw IllegalStateException("Step not found: $stepId")

        require(step.status == Step.Status.EXECUTING) {
            "Cannot fail step ${step.id}: current status is ${step.status}, expected EXECUTING"
        }

        stepRepository.complete(stepId, Step.Status.FAILED, error)
        log.debug { "Failed step ${step.id}: $error" }

        // Cascade invalidation
        return cascadeInvalidation(stepId)
    }

    @Transactional
    override suspend fun invalidateStep(stepId: Step.Id) {
        val step = stepRepository.findById(stepId)
            ?: throw IllegalStateException("Step not found: $stepId")

        stepRepository.complete(stepId, Step.Status.INVALIDATED, "Invalidated due to dependency failure")
        log.debug { "Invalidated step ${step.id}" }
    }

    @Transactional
    override suspend fun cascadeInvalidation(stepId: Step.Id): List<Step> {
        val invalidatedSteps = mutableListOf<Step>()
        val toProcess = ArrayDeque<Step.Id>()
        toProcess.add(stepId)
        val processed = mutableSetOf<Step.Id>()

        while (toProcess.isNotEmpty()) {
            val currentId = toProcess.removeFirst()
            if (currentId in processed) continue
            processed.add(currentId)

            // Find all steps that depend on current step
            val dependents = stepRepository.findDependentSteps(currentId)
            
            dependents.forEach { dependent ->
                if (dependent.status !in setOf(Step.Status.COMPLETED, Step.Status.FAILED, Step.Status.INVALIDATED)) {
                    invalidateStep(dependent.id)
                    invalidatedSteps.add(dependent)
                    toProcess.add(dependent.id)
                }
            }
        }

        log.info { "Cascaded invalidation from step $stepId: invalidated ${invalidatedSteps.size} dependent steps" }
        return invalidatedSteps
    }

    @Transactional
    override suspend fun completePlan(planId: Plan.Id, status: Plan.Status) {
        val plan = planRepository.findById(planId)
            ?: throw IllegalStateException("Plan not found: $planId")

        require(plan.status == Plan.Status.EXECUTING) {
            "Cannot complete plan ${plan.id}: current status is ${plan.status}, expected EXECUTING"
        }

        require(status in setOf(Plan.Status.COMPLETED, Plan.Status.FAILED, Plan.Status.CANCELLED)) {
            "Invalid final status: $status (must be COMPLETED, FAILED, or CANCELLED)"
        }

        planRepository.updateStatus(planId, status)
        log.info { "Completed plan ${plan.id} with status $status" }
    }

    @Transactional
    override suspend fun hasMoreSteps(planId: Plan.Id): Boolean {
        val steps = stepRepository.findByPlanId(planId)
        
        // Check if there are any PENDING or EXECUTING steps
        return steps.any { it.status in setOf(Step.Status.PENDING, Step.Status.EXECUTING) }
    }

    @Transactional
    override suspend fun getPlanSummary(planId: Plan.Id): PlanSummary {
        val plan = planRepository.findById(planId)
            ?: throw IllegalStateException("Plan not found: $planId")

        val steps = stepRepository.findByPlanId(planId).sortedBy { it.position }
        
        val currentStep = steps.firstOrNull { it.status == Step.Status.EXECUTING }
        val completedSteps = steps.count { it.status == Step.Status.COMPLETED }
        val invalidatedSteps = steps.filter { it.status == Step.Status.INVALIDATED }

        return PlanSummary(
            plan = plan,
            steps = steps,
            currentStep = currentStep,
            completedSteps = completedSteps,
            totalSteps = steps.size,
            invalidatedSteps = invalidatedSteps
        )
    }
}
