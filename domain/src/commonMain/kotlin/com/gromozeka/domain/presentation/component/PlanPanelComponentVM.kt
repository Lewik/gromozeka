package com.gromozeka.domain.presentation.component

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import kotlinx.coroutines.flow.StateFlow

/**
 * [UI CONTRACT] ViewModel for plan management panel.
 *
 * ASCII Layout:
 * ```
 * ┌─────────────────────────────────────┐
 * │ Plans                      [+ New]  │
 * ├─────────────────────────────────────┤
 * │ [Search: ____________]              │
 * ├─────────────────────────────────────┤
 * │ ▸ Implement auth feature            │ ← collapsed
 * │ ▾ Add caching layer          [Edit] │ ← expanded
 * │   ☐ Analyze current code            │ ← PENDING
 * │   ☑ Design cache interface          │ ← DONE
 * │   ☐ Implement Redis adapter         │ ← PENDING
 * │   [+ Add Step]                      │
 * │ ▸ Refactor domain layer             │
 * └─────────────────────────────────────┘
 * ```
 *
 * Functionality:
 * - Plan list with search
 * - Expand/collapse to view steps
 * - Create/edit/delete plans
 * - Add/edit/delete steps
 * - Checkbox for quick step status change
 * - Clone plans
 *
 * Implementation in presentation layer.
 */
interface PlanPanelComponentVM {
    
    // ============ State ============
    
    /**
     * List of all plans.
     */
    val plans: StateFlow<List<Plan>>
    
    /**
     * Expanded plans (steps are shown).
     * Map: Plan.Id -> List<PlanStep>
     */
    val expandedPlans: StateFlow<Map<Plan.Id, List<PlanStep>>>
    
    /**
     * Search query.
     */
    val searchQuery: StateFlow<String>
    
    /**
     * Loading state.
     */
    val isLoading: StateFlow<Boolean>
    
    /**
     * Error (if any).
     */
    val error: StateFlow<String?>
    
    // ============ Plan Actions ============
    
    /**
     * Loads all plans.
     */
    fun loadPlans()
    
    /**
     * Search plans by query.
     */
    fun searchPlans(query: String)
    
    /**
     * Creates new plan.
     *
     * Opens plan creation dialog.
     */
    fun createPlan()
    
    /**
     * Edits plan.
     *
     * Opens edit dialog.
     */
    fun editPlan(planId: Plan.Id)
    
    /**
     * Deletes plan.
     *
     * Shows confirmation dialog.
     */
    fun deletePlan(planId: Plan.Id)
    
    /**
     * Clones plan.
     *
     * Opens dialog for entering new name.
     */
    fun clonePlan(planId: Plan.Id)
    
    /**
     * Expands/collapses plan (show/hide steps).
     */
    fun togglePlanExpanded(planId: Plan.Id)
    
    // ============ Step Actions ============
    
    /**
     * Adds step to plan.
     *
     * Opens step creation dialog.
     */
    fun addStep(planId: Plan.Id, parentId: PlanStep.Id? = null)
    
    /**
     * Edits step.
     *
     * Opens edit dialog.
     */
    fun editStep(stepId: PlanStep.Id)
    
    /**
     * Deletes step.
     *
     * Shows confirmation dialog.
     */
    fun deleteStep(stepId: PlanStep.Id)
    
    /**
     * Toggles step status (PENDING ↔ DONE).
     *
     * Called on checkbox click.
     */
    fun toggleStepStatus(stepId: PlanStep.Id)
}
