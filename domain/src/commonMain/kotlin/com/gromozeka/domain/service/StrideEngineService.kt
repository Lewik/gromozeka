package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step
import kotlinx.serialization.Serializable

/**
 * Domain service for Stride Engine coordination.
 *
 * # Purpose
 *
 * Coordinates Plan and Step entities for tool implementations.
 * This is NOT an orchestrator (which manages flow) — it's a data coordinator
 * (which prepares results for tools).
 *
 * # Responsibilities
 *
 * **Plan lifecycle:**
 * - Create Plan from step definitions (via createPlan)
 * - Select next executable step based on dependencies
 * - Update plan state
 *
 * **Step management:**
 * - Complete step (success or failure)
 * - Update step state
 * - Validate and apply plan modifications
 *
 * **Tool result formation:**
 * - Generate instruction text based on step type and certainty
 * - Format unified tool_result structure
 * - Provide runtime step representation
 *
 * # What This Service Does NOT Do
 *
 * - Does NOT manage execution flow (LLM does via tool calls)
 * - Does NOT enforce tool_choice (ConversationEngineService does)
 * - Does NOT make LLM calls (tools do)
 * - Does NOT track state machine (app tracks via plan status)
 *
 * # Architecture
 *
 * ```
 * Tool (infrastructure-ai)
 *   → StrideEngineService (application layer implementation)
 *     → PlanRepository + StepRepository (infrastructure-db)
 * ```
 *
 * Domain defines interface, application implements, infrastructure uses.
 *
 * # Design Invariants
 *
 * - Service operates synchronously (no background processing)
 * - State changes immediately persisted to Neo4j
 * - Methods are transactional
 * - No side effects beyond Neo4j updates
 *
 * This is a domain service - pure business logic, no infrastructure concerns.
 */
interface StrideEngineService {
    /**
     * Creates execution plan from step definitions.
     *
     * Called when create_plan tool is invoked.
     *
     * Workflow:
     * 1. Create Plan entity (status = ACTIVE)
     * 2. Create Step entities (status = PENDING)
     * 3. Create DEPENDS_ON relationships from step.dependsOn
     * 4. Validate no cycles in dependency graph
     * 5. Select first step (first PENDING with no dependencies)
     * 6. Mark first step as IN_PROGRESS
     * 7. Store in Neo4j
     * 8. Generate instruction for first step
     * 9. Return tool_result structure
     *
     * This is a transactional operation.
     *
     * @param conversationId conversation this plan belongs to
     * @param stepInputs array from create_plan tool
     * @return tool_result structure (planId, steps, currentStepId, instruction)
     * @throws CyclicDependencyException if dependencies form cycle
     * @throws InvalidStepDefinitionException if step data is invalid
     */
    suspend fun createPlan(
        conversationId: Conversation.Id,
        stepInputs: List<StepInput>
    ): PlanResult

    /**
     * Completes current step (success or failure).
     *
     * Called when step_complete tool is invoked.
     *
     * Workflow for success:
     * 1. Update Step: status = COMPLETED, result = provided, completedAt = now
     * 2. Find next PENDING step with satisfied dependencies
     * 3. If next exists: mark as IN_PROGRESS, generate instruction, return
     * 4. If no next: mark Plan as COMPLETED, generate summary instruction, return
     *
     * Workflow for failure:
     * 1. Update Step: status = FAILED, result = error, completedAt = now
     * 2. Update Plan: status = FAILED, completedAt = now
     * 3. Generate explanation instruction
     * 4. Return tool_result structure
     *
     * This is a transactional operation.
     *
     * @param planId plan identifier
     * @param status "success" or "fail"
     * @param result execution result or error description
     * @return tool_result structure (planId, steps, currentStepId, instruction)
     * @throws PlanNotFoundException if plan not found
     * @throws InvalidPlanStateException if plan not ACTIVE
     */
    suspend fun completeStep(
        planId: Plan.Id,
        status: String,
        result: String
    ): PlanResult

    /**
     * Updates plan by modifying steps.
     *
     * Called when update_plan tool is invoked.
     *
     * Validation rules:
     * - Cannot modify COMPLETED or FAILED steps
     * - Can modify PENDING and IN_PROGRESS steps
     * - Can add new PENDING steps
     * - Can remove PENDING steps (if no dependents)
     * - Cannot create cycles in dependencies
     *
     * Workflow:
     * 1. Validate: no modifications to COMPLETED/FAILED steps
     * 2. Detect changes: added, removed, modified steps
     * 3. Update Neo4j: modify/add/remove steps and dependencies
     * 4. Generate instruction for current step
     * 5. Return tool_result structure
     *
     * This is a transactional operation.
     *
     * @param planId plan identifier
     * @param stepInputs updated step definitions
     * @return tool_result structure or error
     * @throws PlanNotFoundException if plan not found
     * @throws InvalidPlanStateException if plan not ACTIVE
     * @throws ValidationException if modification invalid
     */
    suspend fun updatePlan(
        planId: Plan.Id,
        stepInputs: List<StepInput>
    ): UpdatePlanResult

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
     * If certainty < 1.0, adds modifier:
     * "Confidence in this statement is low ({certainty}). Gather additional
     * context, make decision autonomously. Don't ask user — decide yourself."
     *
     * @param step step to generate instruction for
     * @return instruction text for LLM
     */
    fun generateInstruction(step: Step): String

    /**
     * Finds currently active plan for conversation.
     *
     * @param conversationId conversation identifier
     * @return active plan if exists, null otherwise
     */
    suspend fun findActivePlan(conversationId: Conversation.Id): Plan?
}

/**
 * Step input from create_plan or update_plan tool.
 *
 * Pure domain type shared across tool contracts and application services.
 */
@Serializable
data class StepInput(
    val text: String,
    val type: Step.Type,
    val certainty: Float,
    val entities: List<String>,
    val dependsOn: List<Int>
)

/**
 * Unified tool_result structure for create_plan, step_complete tools.
 *
 * Pure domain result shape shared across tool contracts and application services.
 */
@Serializable
data class PlanResult(
    val planId: String,
    val steps: List<StepRuntime>,
    val currentStepId: Int?,
    val instruction: String
)

/**
 * Tool_result structure for update_plan (can be success or error).
 *
 * Pure domain result shape shared across tool contracts and application services.
 */
@Serializable
data class UpdatePlanResult(
    val ok: Boolean,
    val error: String? = null,
    val planId: String? = null,
    val steps: List<StepRuntime>? = null,
    val currentStepId: Int? = null,
    val instruction: String? = null
)

/**
 * Step runtime representation (full state including execution progress).
 *
 * Pure domain result shape shared across tool contracts and application services.
 */
@Serializable
data class StepRuntime(
    val id: Int,
    val text: String,
    val type: String,
    val status: String,
    val result: String?,
    val certainty: Float,
    val entities: List<String>,
    val dependsOn: List<Int>
)
