package com.gromozeka.domain.repository

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep

/**
 * Repository for working with plans and their steps.
 *
 * Plans are stored in Neo4j with vectorization for semantic search.
 * Graph structure:
 * ```
 * (Plan)-[:HAS_STEP]->(PlanStep)
 * (PlanStep)-[:PARENT_STEP]->(PlanStep)  // for tree structure
 * ```
 *
 * Vectorization:
 * - Plan.description -> embedding (for plan search)
 * - PlanStep.getTextForVectorization() -> embedding (for step search)
 */
interface PlanRepository {
    
    // ============ Plan CRUD ============
    
    /**
     * Creates a new plan.
     *
     * @param plan plan to create
     * @return created plan
     * @throws DuplicatePlanException if plan with this ID already exists
     */
    suspend fun createPlan(plan: Plan): Plan
    
    /**
     * Finds plan by ID.
     *
     * @param id plan identifier
     * @return plan if found, null if not found
     */
    suspend fun findPlanById(id: Plan.Id): Plan?
    
    /**
     * Updates existing plan.
     *
     * Only name, description, isTemplate fields are updated.
     * Use add/update/deleteStep methods for working with steps.
     *
     * @param plan plan with updated data
     * @return updated plan
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun updatePlan(plan: Plan): Plan
    
    /**
     * Deletes plan and all its steps.
     *
     * Cascade deletion:
     * - All plan steps are deleted
     * - Thread relationships are deleted (future)
     *
     * @param id plan identifier
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun deletePlan(id: Plan.Id)
    
    /**
     * Clones plan with all steps.
     *
     * Creates a full copy of the plan:
     * - New IDs are generated for plan and all steps
     * - Tree structure is preserved (parentIds are updated)
     * - All steps get PENDING status
     * - Step results are cleared
     *
     * @param sourceId ID of plan to clone
     * @param newName name of new plan
     * @return cloned plan
     * @throws PlanNotFoundException if source plan not found
     */
    suspend fun clonePlan(sourceId: Plan.Id, newName: String): Plan
    
    // ============ Plan Search ============
    
    /**
     * Search plans by text (semantic + keyword).
     *
     * Uses hybrid search (BM25 + vector similarity) over fields:
     * - Plan.name
     * - Plan.description
     *
     * @param query search query
     * @param limit maximum number of results
     * @return list of plans sorted by relevance
     */
    suspend fun searchPlans(query: String, limit: Int = 10): List<Plan>
    
    /**
     * Returns all plans (for UI list).
     *
     * @param includeTemplates whether to include templates
     * @return list of all plans sorted by creation date (newest first)
     */
    suspend fun findAllPlans(includeTemplates: Boolean = true): List<Plan>
    
    // ============ Step CRUD ============
    
    /**
     * Adds step to plan.
     *
     * @param step step to add
     * @return added step
     * @throws PlanNotFoundException if plan not found
     * @throws StepNotFoundException if parentId specified but parent step not found
     * @throws DuplicateStepException if step with this ID already exists
     */
    suspend fun addStep(step: PlanStep): PlanStep
    
    /**
     * Finds step by ID.
     *
     * @param id step identifier
     * @return step if found, null if not found
     */
    suspend fun findStepById(id: PlanStep.Id): PlanStep?
    
    /**
     * Updates existing step.
     *
     * Any step fields can be updated depending on type.
     * Cannot change: id, planId (step cannot be moved to another plan).
     *
     * @param step step with updated data
     * @return updated step
     * @throws StepNotFoundException if step not found
     * @throws StepNotFoundException if new parentId specified but parent step not found
     */
    suspend fun updateStep(step: PlanStep): PlanStep
    
    /**
     * Deletes step from plan.
     *
     * Cascade deletion:
     * - All child steps are deleted (where parentId = deleted step)
     *
     * @param id step identifier
     * @throws StepNotFoundException if step not found
     */
    suspend fun deleteStep(id: PlanStep.Id)
    
    /**
     * Returns all plan steps.
     *
     * Steps are returned in a flat list without hierarchy.
     * Use parentId on client side to build tree.
     *
     * @param planId plan identifier
     * @return list of all plan steps
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun findStepsByPlanId(planId: Plan.Id): List<PlanStep>
}

// ============ Exceptions ============

class PlanNotFoundException(planId: Plan.Id) : 
    RuntimeException("Plan not found: $planId")

class StepNotFoundException(stepId: PlanStep.Id) : 
    RuntimeException("Step not found: $stepId")

class DuplicatePlanException(planId: Plan.Id) : 
    RuntimeException("Plan already exists: $planId")

class DuplicateStepException(stepId: PlanStep.Id) : 
    RuntimeException("Step already exists: $stepId")

class MultipleRootStepsException(planId: Plan.Id) : 
    RuntimeException("Plan already has a root step (parentId=null). Only one root step is allowed per plan: $planId")
