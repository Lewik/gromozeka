package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step

/**
 * Domain service for Stride Engine orchestration.
 *
 * Orchestrates execution plan lifecycle:
 * - Plan creation from step definitions
 * - Step execution routing (ReAct vs passthrough)
 * - Status management and transitions
 * - Cascading invalidation
 *
 * Business Logic Agent implements this to coordinate:
 * - PlanRepository (graph storage)
 * - StepRepository (graph storage)
 * - ConversationDomainService (message/thread management)
 * - LLM calls (for ReAct execution)
 *
 * # Relationship to Conversation Execution
 *
 * This service orchestrates Stride Engine execution within the standard
 * LLM interaction loop provided by conversation engine.
 *
 * ## Execution Constraints
 *
 * **Tool call enforcement:**
 * - First LLM call when strideEnabled=true MUST use plan_steps (tool_choice=REQUIRED)
 * - Subsequent calls have unrestricted tool choice
 * - Execution continues while LLM produces tool calls
 * - Execution terminates when LLM response contains no tool calls
 *
 * ## Service Responsibilities
 *
 * **Plan lifecycle:**
 * - Create Plan from step definitions (via createPlan)
 * - Track Plan execution status
 * - Manage Step transitions (PENDING → EXECUTING → COMPLETED/FAILED)
 *
 * **Dependency management:**
 * - Enforce step execution order via depends_on relationships
 * - Perform cascading invalidation on step failure
 * - Return next executable step based on dependency graph
 *
 * **State coordination:**
 * - Update Neo4j graph state
 * - Provide execution context to LLM via tool responses
 * - Maintain execution invariants (no cycles, valid transitions)
 *
 * ## Design Invariants
 *
 * - Service operates synchronously with LLM calls (no background processing)
 * - State changes are immediately persisted to Neo4j
 * - Tool responses guide LLM behavior (declarative control)
 * - No workflow engine or state machine required
 *
 * This is a domain service - pure business logic, no infrastructure concerns.
 */
interface StrideEngineService {
    /**
     * Creates execution plan from step definitions.
     *
     * Called when plan_steps tool returns step array.
     *
     * Workflow:
     * 1. Create Plan entity (status = EXECUTING)
     * 2. Create Step entities (status = PENDING)
     * 3. Create DEPENDS_ON relationships from step.depends_on
     * 4. Perform topological sort to validate no cycles
     * 5. Store in Neo4j
     *
     * This is a transactional operation.
     *
     * @param conversationId conversation this plan belongs to
     * @param stepDefinitions array from plan_steps tool
     * @return created plan with all steps
     * @throws CyclicDependencyException if dependencies form cycle
     * @throws InvalidStepDefinitionException if step data is invalid
     */
    suspend fun createPlan(
        conversationId: Conversation.Id,
        stepDefinitions: List<StepDefinition>
    ): Plan

    /**
     * Finds next step to execute.
     *
     * Returns first PENDING step where all DEPENDS_ON steps are COMPLETED.
     * Returns null if no executable steps (waiting for dependencies or all done).
     *
     * @param planId plan identifier
     * @return next step to execute, null if none available
     */
    suspend fun getNextStep(planId: Plan.Id): Step?

    /**
     * Starts step execution.
     *
     * Updates step status to EXECUTING.
     * Called before ReAct loop or passthrough execution.
     *
     * This is a transactional operation.
     *
     * @param stepId step identifier
     * @throws StepNotFoundException if step not found
     * @throws InvalidStepStateException if step is not PENDING
     */
    suspend fun startStep(stepId: Step.Id)

    /**
     * Completes step successfully.
     *
     * Updates step status to COMPLETED, stores result, sets completedAt.
     * Called when step_complete tool is invoked.
     *
     * This is a transactional operation.
     *
     * @param stepId step identifier
     * @param result execution result summary
     * @throws StepNotFoundException if step not found
     * @throws InvalidStepStateException if step is not EXECUTING
     */
    suspend fun completeStep(stepId: Step.Id, result: String)

    /**
     * Marks step as failed.
     *
     * Updates step status to FAILED, stores error, sets completedAt.
     * Called when step_failed tool is invoked.
     *
     * Triggers cascading invalidation of dependent steps.
     *
     * This is a transactional operation.
     *
     * @param stepId step identifier
     * @param error failure reason
     * @return list of invalidated dependent steps (for ask_user notification)
     * @throws StepNotFoundException if step not found
     * @throws InvalidStepStateException if step is not EXECUTING
     */
    suspend fun failStep(stepId: Step.Id, error: String): List<Step>

    /**
     * Invalidates step due to dependency failure.
     *
     * Updates step status to INVALIDATED, sets completedAt.
     * Used for cascading invalidation when dependency fails.
     *
     * This is a transactional operation.
     *
     * @param stepId step identifier
     * @throws StepNotFoundException if step not found
     */
    suspend fun invalidateStep(stepId: Step.Id)

    /**
     * Performs cascading invalidation.
     *
     * When step fails or is invalidated, all transitive dependents are invalidated.
     * Returns list of invalidated steps for user notification.
     *
     * Workflow:
     * 1. Find all transitive dependents via DEPENDS_ON graph traversal
     * 2. Mark each as INVALIDATED
     * 3. Return list for ask_user tool
     *
     * This is a transactional operation.
     *
     * @param stepId failed/invalidated step
     * @return list of steps that were invalidated (empty if no dependents)
     */
    suspend fun cascadeInvalidation(stepId: Step.Id): List<Step>

    /**
     * Completes plan execution.
     *
     * Updates plan status based on step outcomes:
     * - All steps COMPLETED → COMPLETED
     * - Any step FAILED → FAILED
     * - User cancelled → CANCELLED
     *
     * Sets completedAt timestamp.
     *
     * This is a transactional operation.
     *
     * @param planId plan identifier
     * @param status final status (COMPLETED, FAILED, or CANCELLED)
     * @throws PlanNotFoundException if plan not found
     * @throws InvalidPlanStateException if plan is not EXECUTING
     */
    suspend fun completePlan(planId: Plan.Id, status: Plan.Status)

    /**
     * Checks if plan has more steps to execute.
     *
     * Returns true if there are PENDING steps with satisfied dependencies.
     * Returns false if all steps are in terminal states.
     *
     * @param planId plan identifier
     * @return true if plan can continue execution, false if finished
     */
    suspend fun hasMoreSteps(planId: Plan.Id): Boolean

    /**
     * Gets plan execution summary.
     *
     * Returns plan with all steps and current status.
     * Used for UI display and checkpoint validation.
     *
     * @param planId plan identifier
     * @return plan summary
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun getPlanSummary(planId: Plan.Id): PlanSummary
}

/**
 * Step definition from plan_steps tool.
 *
 * JSON structure returned by LLM when decomposing user message.
 */
data class StepDefinition(
    val text: String,
    val type: Step.Type,
    val certainty: Float,
    val entities: List<String>,
    val dependsOn: List<Int>,  // Indices of steps this step depends on
    val meta: Map<String, Any> = emptyMap()
)

/**
 * Plan execution summary.
 *
 * Aggregate view of plan with all steps for UI and checkpoints.
 */
data class PlanSummary(
    val plan: Plan,
    val steps: List<Step>,
    val currentStep: Step?,
    val completedSteps: Int,
    val totalSteps: Int,
    val invalidatedSteps: List<Step>
)
