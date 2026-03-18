package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Step

/**
 * Repository for execution plan steps.
 *
 * Steps are stored in Neo4j graph database as (Step) nodes.
 *
 * Relationships:
 * - (Plan)-[:HAS_STEP]->(Step) - plan ownership
 * - (Step)-[:DEPENDS_ON]->(Step) - execution dependencies
 *
 * Storage: Neo4j graph database
 */
interface StepRepository {
    /**
     * Creates new step and links to plan.
     *
     * Creates (Step) node and (Plan)-[:HAS_STEP]->(Step) relationship.
     * Does NOT create DEPENDS_ON relationships (use addDependency).
     *
     * This is a transactional operation.
     *
     * @param step step to create
     * @return created step
     * @throws DuplicateStepException if step with same ID already exists
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun create(step: Step): Step

    /**
     * Creates multiple steps in batch.
     *
     * More efficient than creating steps one by one.
     * Creates all (Step) nodes and HAS_STEP relationships in single transaction.
     *
     * This is a transactional operation.
     *
     * @param steps steps to create
     * @return created steps
     * @throws DuplicateStepException if any step ID already exists
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun createBatch(steps: List<Step>): List<Step>

    /**
     * Adds dependency relationship between steps.
     *
     * Creates (from)-[:DEPENDS_ON]->(to) relationship.
     * Used for execution ordering.
     *
     * This is a transactional operation.
     *
     * @param from dependent step (waits for 'to' to complete)
     * @param to dependency step (must complete first)
     * @throws StepNotFoundException if either step not found
     * @throws CyclicDependencyException if creates cycle in dependency graph
     */
    suspend fun addDependency(from: Step.Id, to: Step.Id)

    /**
     * Adds multiple dependencies in batch.
     *
     * More efficient than adding dependencies one by one.
     *
     * This is a transactional operation.
     *
     * @param dependencies list of (from, to) pairs
     * @throws StepNotFoundException if any step not found
     * @throws CyclicDependencyException if creates cycle in dependency graph
     */
    suspend fun addDependenciesBatch(dependencies: List<Pair<Step.Id, Step.Id>>)

    /**
     * Finds step by ID.
     *
     * @param id step identifier
     * @return step if found, null otherwise
     */
    suspend fun findById(id: Step.Id): Step?

    /**
     * Finds all steps for plan.
     *
     * Returns steps ordered by position ascending (execution order).
     *
     * @param planId plan identifier
     * @return list of steps (empty if none found)
     */
    suspend fun findByPlanId(planId: Plan.Id): List<Step>

    /**
     * Finds next pending step for execution.
     *
     * Returns first step where:
     * - status = PENDING
     * - all dependencies (DEPENDS_ON) are COMPLETED
     *
     * Ordered by position ascending.
     *
     * @param planId plan identifier
     * @return next step to execute, null if no pending steps with satisfied dependencies
     */
    suspend fun findNextPendingStep(planId: Plan.Id): Step?

    /**
     * Finds steps that depend on given step.
     *
     * Traverses DEPENDS_ON relationships to find all dependent steps.
     * Returns direct dependents only (not transitive).
     *
     * @param stepId step identifier
     * @return list of dependent steps (empty if none)
     */
    suspend fun findDependentSteps(stepId: Step.Id): List<Step>

    /**
     * Updates step status.
     *
     * Updates status and sets completedAt if transitioning to terminal state
     * (COMPLETED, FAILED).
     *
     * This is a transactional operation.
     *
     * @param id step identifier
     * @param status new status
     * @throws StepNotFoundException if step not found
     */
    suspend fun updateStatus(id: Step.Id, status: Step.Status)

    /**
     * Updates step result.
     *
     * Stores execution result.
     * Full execution trace is in Messages (separate from graph).
     *
     * This is a transactional operation.
     *
     * @param id step identifier
     * @param result execution result
     * @throws StepNotFoundException if step not found
     */
    suspend fun updateResult(id: Step.Id, result: String)

    /**
     * Completes step (status + result + timestamp).
     *
     * Convenience method for atomic update of all completion fields.
     *
     * This is a transactional operation.
     *
     * @param id step identifier
     * @param status final status (COMPLETED or FAILED)
     * @param result execution result
     * @throws StepNotFoundException if step not found
     * @throws IllegalArgumentException if status is not terminal
     */
    suspend fun complete(id: Step.Id, status: Step.Status, result: String)

    /**
     * Updates multiple steps in batch.
     *
     * Efficient for update_plan operations where LLM modifies multiple steps.
     *
     * Only PENDING and IN_PROGRESS steps can be modified.
     * COMPLETED and FAILED steps are immutable.
     *
     * This is a transactional operation.
     *
     * @param steps updated steps
     * @return updated steps
     * @throws StepNotFoundException if any step not found
     * @throws IllegalStateException if trying to modify COMPLETED/FAILED step
     */
    suspend fun updateBatch(steps: List<Step>): List<Step>
}
