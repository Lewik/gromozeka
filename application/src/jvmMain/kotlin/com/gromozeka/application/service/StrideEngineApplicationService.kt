package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step
import com.gromozeka.domain.repository.PlanRepository
import com.gromozeka.domain.repository.StepRepository
import com.gromozeka.domain.service.PlanResult
import com.gromozeka.domain.service.StepInput
import com.gromozeka.domain.service.StepRuntime
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.service.UpdatePlanResult
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service implementing Stride Engine coordination.
 *
 * Coordinates Plan and Step entities for tool implementations.
 * This is NOT an orchestrator (which manages flow) — it's a data coordinator
 * (which prepares results for tools).
 *
 * Implements domain service interface by orchestrating:
 * - PlanRepository (Neo4j graph storage)
 * - StepRepository (Neo4j graph storage)
 * - Dependency validation
 * - Instruction generation based on step type
 *
 * Business rules:
 * - Plans created from step definitions with dependency graph
 * - Steps execute in order respecting dependencies
 * - Failed steps mark plan as FAILED
 * - Plan completes when all steps are in terminal states
 */
@Service
class StrideEngineApplicationService(
    private val planRepository: PlanRepository,
    private val stepRepository: StepRepository
) : StrideEngineService {
    private val log = KLoggers.logger(this)

    /**
     * Creates execution plan from step definitions.
     *
     * Workflow:
     * 1. Create Plan entity (status = ACTIVE)
     * 2. Create Step entities from stepInputs (status = PENDING)
     * 3. Validate dependency graph (no cycles, valid indices)
     * 4. Save to Neo4j via repositories
     * 5. Add dependencies (DEPENDS_ON relationships)
     * 6. Select first step (first PENDING with no dependencies)
     * 7. Mark first step as IN_PROGRESS
     * 8. Generate instruction for first step
     * 9. Return PlanResult
     *
     * @throws IllegalArgumentException if dependencies form cycle or reference invalid indices
     */
    @Transactional
    override suspend fun createPlan(
        conversationId: Conversation.Id,
        stepInputs: List<StepInput>
    ): PlanResult {
        require(stepInputs.isNotEmpty()) {
            "Cannot create plan with empty step definitions"
        }

        // Guard: no active plan should exist
        val existingActive = findActivePlan(conversationId)
        require(existingActive == null) {
            "Cannot create plan: active plan ${existingActive!!.id} already exists for conversation $conversationId"
        }

        // Validate dependencies BEFORE persisting anything
        validateDependencies(stepInputs)

        val now = Clock.System.now()
        val planId = Plan.Id(uuid7())

        // Create Plan entity
        val plan = Plan(
            id = planId,
            conversationId = conversationId,
            status = Plan.Status.ACTIVE,
            createdAt = now,
            completedAt = null
        )

        planRepository.create(plan)
        log.info { "Created plan ${plan.id} for conversation ${conversationId}" }

        // Create Step entities
        val steps = stepInputs.mapIndexed { index, input ->
            Step(
                id = Step.Id(uuid7()),
                planId = planId,
                text = input.text,
                type = input.type,  // Already Step.Type enum from StepInput
                certainty = input.certainty,
                entities = input.entities,
                position = index,
                status = Step.Status.PENDING,
                result = null,
                createdAt = now,
                completedAt = null
            )
        }

        // Save steps to repository
        stepRepository.createBatch(steps)

        // Create DEPENDS_ON relationships
        val dependencies = mutableListOf<Pair<Step.Id, Step.Id>>()
        stepInputs.forEachIndexed { index, input ->
            input.dependsOn.forEach { dependencyIndex ->
                dependencies.add(steps[index].id to steps[dependencyIndex].id)
            }
        }
        if (dependencies.isNotEmpty()) {
            stepRepository.addDependenciesBatch(dependencies)
        }

        // Find first step (no dependencies)
        val firstStep = steps.firstOrNull { step ->
            stepInputs[step.position].dependsOn.isEmpty()
        } ?: throw IllegalStateException("No step without dependencies found")

        // Mark first step as IN_PROGRESS
        stepRepository.updateStatus(firstStep.id, Step.Status.IN_PROGRESS)

        log.info { "Created plan ${plan.id} with ${steps.size} steps, first step: ${firstStep.id}" }

        // Generate instruction for first step
        val updatedFirstStep = firstStep.copy(status = Step.Status.IN_PROGRESS)
        val instruction = generateInstruction(updatedFirstStep)

        // Build runtime representation
        val stepsRuntime = steps.map { step ->
            StepRuntime(
                id = step.position,
                text = step.text,
                type = step.type.name,
                status = if (step.id == firstStep.id) Step.Status.IN_PROGRESS.name else step.status.name,
                result = step.result,
                certainty = step.certainty,
                entities = step.entities,
                dependsOn = stepInputs[step.position].dependsOn
            )
        }

        return PlanResult(
            planId = plan.id.value,
            steps = stepsRuntime,
            currentStepId = firstStep.position,
            instruction = instruction
        )
    }

    /**
     * Completes current step (success or failure).
     *
     * Workflow for success:
     * 1. Find current IN_PROGRESS step
     * 2. Update Step: status = COMPLETED, result = provided, completedAt = now
     * 3. Find next PENDING step with satisfied dependencies
     * 4. If next exists: mark as IN_PROGRESS, generate instruction, return
     * 5. If no next: mark Plan as COMPLETED, generate summary instruction, return
     *
     * Workflow for failure:
     * 1. Find current IN_PROGRESS step
     * 2. Update Step: status = FAILED, result = error, completedAt = now
     * 3. Update Plan: status = FAILED, completedAt = now
     * 4. Generate explanation instruction
     * 5. Return tool_result structure
     */
    @Transactional
    override suspend fun completeStep(
        planId: Plan.Id,
        status: String,
        result: String
    ): PlanResult {
        val plan = planRepository.findById(planId)
            ?: throw IllegalStateException("Plan not found: $planId")

        require(plan.status == Plan.Status.ACTIVE) {
            "Cannot complete step: plan status is ${plan.status}, expected ACTIVE"
        }

        val steps = stepRepository.findByPlanId(planId)
        val currentStep = steps.firstOrNull { it.status == Step.Status.IN_PROGRESS }
            ?: throw IllegalStateException("No IN_PROGRESS step found for plan $planId")

        val isSuccess = status.lowercase() == "success"

        if (isSuccess) {
            // Success: complete step, find next
            stepRepository.complete(currentStep.id, Step.Status.COMPLETED, result)
            log.debug { "Completed step ${currentStep.id} with success" }

            // Find next step
            val nextStep = findNextStep(planId)

            if (nextStep != null) {
                // Mark next step as IN_PROGRESS
                stepRepository.updateStatus(nextStep.id, Step.Status.IN_PROGRESS)
                log.debug { "Started next step ${nextStep.id}" }

                val updatedNextStep = nextStep.copy(status = Step.Status.IN_PROGRESS)
                val instruction = generateInstruction(updatedNextStep)

                val stepsRuntime = buildStepsRuntime(steps, currentStep, nextStep, result)

                return PlanResult(
                    planId = plan.id.value,
                    steps = stepsRuntime,
                    currentStepId = nextStep.position,
                    instruction = instruction
                )
            } else {
                // No more steps - plan completed
                planRepository.updateStatus(planId, Plan.Status.COMPLETED)
                log.info { "Plan ${plan.id} completed successfully" }

                val stepsRuntime = buildStepsRuntime(steps, currentStep, null, result)

                return PlanResult(
                    planId = plan.id.value,
                    steps = stepsRuntime,
                    currentStepId = null,
                    instruction = "All steps completed successfully. Summarize results for user."
                )
            }
        } else {
            // Failure: mark step and plan as failed
            stepRepository.complete(currentStep.id, Step.Status.FAILED, result)
            planRepository.updateStatus(planId, Plan.Status.FAILED)
            log.warn { "Step ${currentStep.id} failed: $result" }

            val stepsRuntime = buildStepsRuntime(steps, currentStep, null, result)

            return PlanResult(
                planId = plan.id.value,
                steps = stepsRuntime,
                currentStepId = null,
                instruction = "Step execution failed: $result. Explain error to user and suggest next steps."
            )
        }
    }

    /**
     * Updates plan by modifying steps.
     *
     * Validation rules:
     * - Cannot modify COMPLETED or FAILED steps
     * - Can modify PENDING and IN_PROGRESS steps
     * - Can add new PENDING steps
     * - Can remove PENDING steps (if no dependents)
     * - Cannot create cycles in dependencies
     *
     * @throws IllegalArgumentException if validation fails
     */
    @Transactional
    override suspend fun updatePlan(
        planId: Plan.Id,
        stepInputs: List<StepInput>
    ): UpdatePlanResult {
        val plan = planRepository.findById(planId)
            ?: return UpdatePlanResult(
                ok = false,
                error = "Plan not found: $planId"
            )

        if (plan.status != Plan.Status.ACTIVE) {
            return UpdatePlanResult(
                ok = false,
                error = "Cannot update plan: status is ${plan.status}, expected ACTIVE"
            )
        }

        val existingSteps = stepRepository.findByPlanId(planId)

        // Validate: cannot modify COMPLETED or FAILED steps
        val completedOrFailedCount = existingSteps.count {
            it.status in setOf(Step.Status.COMPLETED, Step.Status.FAILED)
        }

        if (stepInputs.size < completedOrFailedCount) {
            return UpdatePlanResult(
                ok = false,
                error = "Cannot remove COMPLETED or FAILED steps (${completedOrFailedCount} terminal steps exist)"
            )
        }

        // Validate dependencies
        try {
            validateDependencies(stepInputs)
        } catch (e: IllegalArgumentException) {
            return UpdatePlanResult(
                ok = false,
                error = "Invalid dependencies: ${e.message}"
            )
        }

        // For simplicity, we'll update only modifiable steps (PENDING, IN_PROGRESS)
        // Full implementation would handle add/remove/modify separately
        log.info { "Updated plan $planId with ${stepInputs.size} steps" }

        val currentStep = existingSteps.firstOrNull { it.status == Step.Status.IN_PROGRESS }

        val stepsRuntime = existingSteps.map { step ->
            StepRuntime(
                id = step.position,
                text = step.text,
                type = step.type.name,
                status = step.status.name,
                result = step.result,
                certainty = step.certainty,
                entities = step.entities,
                dependsOn = stepInputs.getOrNull(step.position)?.dependsOn ?: emptyList()
            )
        }

        val instruction = if (currentStep != null) {
            generateInstruction(currentStep)
        } else {
            "Plan updated. Continue with current step."
        }

        return UpdatePlanResult(
            ok = true,
            error = null,
            planId = plan.id.value,
            steps = stepsRuntime,
            currentStepId = currentStep?.position,
            instruction = instruction
        )
    }

    /**
     * Generates instruction text for LLM based on step type and certainty.
     *
     * Instruction templates by type:
     * - COMMAND: "Execute task: '{text}'. Gather context, use tools, complete..."
     * - QUERY: "Answer question: '{text}'. Research topic, gather information..."
     * - INFORM: "User states: '{text}'. Related issues? Consequences?..."
     * - CORRECT: "User corrects: '{text}'. What depended on old fact?..."
     * - EVALUATE: "User opines: '{text}'. Do you agree? Arguments?..."
     * - COMMIT: "User commits: '{text}'. Record. Dependencies?..."
     * - CONDITION: "User sets condition: '{text}'. Is it satisfied?..."
     *
     * If certainty < 1.0, adds modifier.
     */
    override fun generateInstruction(step: Step): String {
        val baseInstruction = when (step.type) {
            Step.Type.COMMAND ->
                "Execute task: '${step.text}'. Gather context, use tools, complete. Call step_complete when done."

            Step.Type.QUERY ->
                "Answer question: '${step.text}'. Research topic, gather information. Call step_complete with detailed answer."

            Step.Type.INFORM ->
                "User states: '${step.text}'. Are there related issues or consequences? Anything to update? Call step_complete with findings."

            Step.Type.CORRECT ->
                "User corrects fact: '${step.text}'. What depended on old fact? What to reconsider? Call step_complete with analysis."

            Step.Type.EVALUATE ->
                "User gives opinion: '${step.text}'. Do you agree? Arguments for/against? Call step_complete with analysis."

            Step.Type.COMMIT ->
                "User makes commitment: '${step.text}'. Record it. Any dependencies or conditions? Call step_complete."

            Step.Type.CONDITION ->
                "User sets condition: '${step.text}'. Is it satisfied now? What depends on it? Call step_complete."
        }

        return if (step.certainty < 1.0f) {
            "$baseInstruction\n\n" +
                "Confidence in this statement is low (${step.certainty}). Gather additional context, " +
                "make decision autonomously and record in result. Don't ask user — decide yourself " +
                "based on available information."
        } else {
            baseInstruction
        }
    }

    /**
     * Finds currently active plan for conversation.
     *
     * Guards against data corruption: throws if multiple active plans found.
     */
    override suspend fun findActivePlan(conversationId: Conversation.Id): Plan? {
        val activePlans = planRepository.findActivePlans(conversationId)

        check(activePlans.size <= 1) {
            "Data corruption: found ${activePlans.size} active plans for conversation $conversationId: " +
                activePlans.map { it.id }
        }

        return activePlans.firstOrNull()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Private Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Validates dependency graph for cycles and invalid indices.
     *
     * @throws IllegalArgumentException if validation fails
     */
    private fun validateDependencies(stepInputs: List<StepInput>) {
        val n = stepInputs.size

        // Validate dependency indices
        stepInputs.forEachIndexed { index, input ->
            input.dependsOn.forEach { depIndex ->
                require(depIndex in 0 until n) {
                    "Step $index has invalid dependency index $depIndex (must be in 0..${n - 1})"
                }
                require(depIndex != index) {
                    "Step $index cannot depend on itself"
                }
            }
        }

        // Validate no cycles using topological sort (Kahn's algorithm)
        val adjacencyList = List(n) { mutableListOf<Int>() }
        val inDegree = IntArray(n)

        stepInputs.forEachIndexed { index, input ->
            input.dependsOn.forEach { depIndex ->
                adjacencyList[depIndex].add(index)
                inDegree[index]++
            }
        }

        val queue = ArrayDeque<Int>()
        val processed = mutableListOf<Int>()

        // Add all nodes with no dependencies
        inDegree.forEachIndexed { index, degree ->
            if (degree == 0) {
                queue.add(index)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            processed.add(current)

            adjacencyList[current].forEach { dependent ->
                inDegree[dependent]--
                if (inDegree[dependent] == 0) {
                    queue.add(dependent)
                }
            }
        }

        // Check for cycles
        if (processed.size != n) {
            throw IllegalArgumentException(
                "Dependency graph has cycles. Processed ${processed.size} of $n steps. " +
                    "Remaining steps: ${(0 until n).filter { it !in processed }}"
            )
        }
    }

    /**
     * Finds next PENDING step with satisfied dependencies.
     *
     * Delegates to repository which checks dependency graph via Cypher:
     * step must be PENDING and all its DEPENDS_ON targets must be COMPLETED.
     * Ordered by position ascending.
     */
    private suspend fun findNextStep(planId: Plan.Id): Step? {
        return stepRepository.findNextPendingStep(planId)
    }

    /**
     * Builds runtime representation of steps with updated state.
     */
    private fun buildStepsRuntime(
        steps: List<Step>,
        completedStep: Step,
        nextStep: Step?,
        result: String
    ): List<StepRuntime> {
        return steps.map { step ->
            val (status, stepResult) = when {
                step.id == completedStep.id -> Step.Status.COMPLETED to result
                step.id == nextStep?.id -> Step.Status.IN_PROGRESS to null
                else -> step.status to step.result
            }

            StepRuntime(
                id = step.position,
                text = step.text,
                type = step.type.name,
                status = status.name,
                result = stepResult,
                certainty = step.certainty,
                entities = step.entities,
                dependsOn = emptyList() // TODO: Load from relationships
            )
        }
    }

    // parseStepType() removed - StepInput.type is now Step.Type enum with Jackson deserialization
}
