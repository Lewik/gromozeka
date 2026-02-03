package com.gromozeka.domain.service.plan

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus

/**
 * Service for plan management - business logic for working with plans and steps.
 *
 * Coordinates operations between PlanRepository and provides
 * high-level operations for MCP tools and UI.
 *
 * Implementation in application layer.
 */
interface PlanManagementService {
    
    // ============ Plan Operations ============
    
    /**
     * Creates a new plan.
     *
     * Generates ID and timestamp automatically.
     *
     * @param name plan name
     * @param description task description
     * @param isTemplate template flag
     * @return created plan
     */
    suspend fun createPlan(
        name: String,
        description: String,
        isTemplate: Boolean = false
    ): Plan
    
    /**
     * Updates plan.
     *
     * Only non-null parameters are updated.
     *
     * @param planId plan ID
     * @param name new name (if null - not updated)
     * @param description new description (if null - not updated)
     * @param isTemplate new template flag (if null - not updated)
     * @return updated plan
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun updatePlan(
        planId: Plan.Id,
        name: String? = null,
        description: String? = null,
        isTemplate: Boolean? = null
    ): Plan
    
    /**
     * Deletes plan with all steps.
     *
     * @param planId plan ID
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun deletePlan(planId: Plan.Id)
    
    /**
     * Gets plan with all steps.
     *
     * Returns plan and list of all its steps.
     * Steps in flat list, hierarchy via parentId.
     *
     * @param planId plan ID
     * @return pair (plan, list of steps)
     * @throws PlanNotFoundException if plan not found
     */
    suspend fun getPlanWithSteps(planId: Plan.Id): Pair<Plan, List<PlanStep>>
    
    /**
     * Clones plan.
     *
     * Creates a copy of plan with all steps.
     * All steps get PENDING status, results are cleared.
     *
     * @param sourcePlanId ID of plan to clone
     * @param newName name of new plan (if null - uses "Copy of {original}")
     * @return cloned plan
     * @throws PlanNotFoundException if source plan not found
     */
    suspend fun clonePlan(sourcePlanId: Plan.Id, newName: String? = null): Plan
    
    /**
     * Search plans.
     *
     * Uses semantic search over description and name.
     *
     * @param query search query
     * @param limit maximum number of results
     * @return list of plans sorted by relevance
     */
    suspend fun searchPlans(query: String, limit: Int = 10): List<Plan>
    
    /**
     * Gets all plans.
     *
     * @param includeTemplates whether to include templates
     * @return list of all plans
     */
    suspend fun getAllPlans(includeTemplates: Boolean = true): List<Plan>
    
    // ============ Step Operations ============
    
    /**
     * Adds text step to plan.
     *
     * Generates ID automatically.
     *
     * @param planId plan ID
     * @param instruction instruction text
     * @param parentId parent step ID (for hierarchy)
     * @return added step
     * @throws PlanNotFoundException if plan not found
     * @throws StepNotFoundException if parentId specified but parent step not found
     */
    suspend fun addTextStep(
        planId: Plan.Id,
        instruction: String,
        parentId: PlanStep.Id? = null
    ): PlanStep.Text
    
    /**
     * Updates step.
     *
     * Only non-null parameters are updated.
     * Step type cannot be changed (need to delete and create new).
     *
     * @param stepId step ID
     * @param instruction new instruction (only for Text steps)
     * @param result new result
     * @param status new status
     * @param parentId new parent step
     * @return updated step
     * @throws StepNotFoundException if step not found
     */
    suspend fun updateStep(
        stepId: PlanStep.Id,
        instruction: String? = null,
        result: String? = null,
        status: StepStatus? = null,
        parentId: PlanStep.Id? = null
    ): PlanStep
    
    /**
     * Deletes step.
     *
     * Cascades to all child steps.
     *
     * @param stepId step ID
     * @throws StepNotFoundException if step not found
     */
    suspend fun deleteStep(stepId: PlanStep.Id)
    
    /**
     * Updates step status.
     *
     * Convenient method for quick status change (e.g., via UI checkbox).
     *
     * @param stepId step ID
     * @param status new status
     * @return updated step
     * @throws StepNotFoundException if step not found
     */
    suspend fun updateStepStatus(stepId: PlanStep.Id, status: StepStatus): PlanStep
}
