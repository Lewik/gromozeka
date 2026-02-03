package com.gromozeka.domain.service.plan

import com.gromozeka.domain.model.plan.Plan

/**
 * Event bus for plan-related changes.
 * 
 * Allows decoupled communication between plan tools (infrastructure)
 * and UI (presentation) without direct dependencies.
 * 
 * Pattern: Event-driven architecture with Kotlin Flow.
 */
interface PlanEventBus {
    /**
     * Events stream for subscribing to plan changes.
     * 
     * UI components can collect this flow to react to changes
     * made by LLM tools or other parts of the system.
     */
    val events: kotlinx.coroutines.flow.Flow<PlanEvent>
    
    /**
     * Emit plan event to all subscribers.
     * 
     * Called by plan tools after modifying data.
     */
    suspend fun emit(event: PlanEvent)
}

/**
 * Events related to plan changes.
 * 
 * Simple event model: just notify that something changed.
 * UI reloads all plans (simple and reliable).
 */
sealed interface PlanEvent {
    /**
     * Plans list changed (any create/update/delete/clone operation).
     * UI should reload all plans.
     */
    data object PlansChanged : PlanEvent
    
    /**
     * Steps in specific plan changed (add/update/delete).
     * UI should reload steps for this plan if it's expanded.
     * 
     * @property planId plan that was modified
     */
    data class StepsChanged(val planId: Plan.Id) : PlanEvent
}
