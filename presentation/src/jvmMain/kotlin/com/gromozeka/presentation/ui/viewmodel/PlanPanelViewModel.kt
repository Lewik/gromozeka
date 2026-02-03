package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.domain.presentation.component.PlanPanelComponentVM
import com.gromozeka.domain.repository.MultipleRootStepsException
import com.gromozeka.domain.service.plan.PlanManagementService
import com.gromozeka.domain.service.plan.PlanEventBus
import com.gromozeka.domain.service.plan.PlanEvent
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of PlanPanelComponentVM.
 * 
 * Manages plan list, search, expand/collapse, and CRUD operations.
 */
class PlanPanelViewModel(
    private val planManagementService: PlanManagementService,
    private val planEventBus: PlanEventBus,
    private val scope: CoroutineScope
) : PlanPanelComponentVM {
    
    private val log = KLoggers.logger(this)
    
    // ============ State ============
    
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    override val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    
    private val _expandedPlans = MutableStateFlow<Map<Plan.Id, List<PlanStep>>>(emptyMap())
    override val expandedPlans: StateFlow<Map<Plan.Id, List<PlanStep>>> = _expandedPlans.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    override val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    // Dialog states (internal to ViewModel)
    private val _showCreatePlanDialog = MutableStateFlow(false)
    val showCreatePlanDialog: StateFlow<Boolean> = _showCreatePlanDialog.asStateFlow()
    
    private val _showEditPlanDialog = MutableStateFlow<Plan?>(null)
    val showEditPlanDialog: StateFlow<Plan?> = _showEditPlanDialog.asStateFlow()
    
    private val _showClonePlanDialog = MutableStateFlow<Plan?>(null)
    val showClonePlanDialog: StateFlow<Plan?> = _showClonePlanDialog.asStateFlow()
    
    private val _showCreateStepDialog = MutableStateFlow<Pair<Plan.Id, PlanStep.Id?>?>(null)
    val showCreateStepDialog: StateFlow<Pair<Plan.Id, PlanStep.Id?>?> = _showCreateStepDialog.asStateFlow()
    
    private val _showEditStepDialog = MutableStateFlow<PlanStep?>(null)
    val showEditStepDialog: StateFlow<PlanStep?> = _showEditStepDialog.asStateFlow()
    
    private val _showDeletePlanDialog = MutableStateFlow<Plan?>(null)
    val showDeletePlanDialog: StateFlow<Plan?> = _showDeletePlanDialog.asStateFlow()
    
    private val _showDeleteStepDialog = MutableStateFlow<PlanStep?>(null)
    val showDeleteStepDialog: StateFlow<PlanStep?> = _showDeleteStepDialog.asStateFlow()
    
    init {
        loadPlans()
        subscribeToEvents()
    }
    
    /**
     * Subscribe to plan events from tools.
     * Automatically updates UI when LLM modifies plans.
     */
    private fun subscribeToEvents() {
        scope.launch {
            planEventBus.events.collect { event ->
                log.info("Received plan event: $event")
                
                when (event) {
                    is PlanEvent.PlansChanged -> {
                        // Reload all plans (simple and reliable)
                        loadPlans()
                    }
                    
                    is PlanEvent.StepsChanged -> {
                        // Reload steps for affected plan if it's expanded
                        if (_expandedPlans.value.containsKey(event.planId)) {
                            try {
                                val (_, steps) = planManagementService.getPlanWithSteps(event.planId)
                                _expandedPlans.value = _expandedPlans.value + (event.planId to steps)
                                log.debug("Reloaded steps for plan ${event.planId}")
                            } catch (e: Exception) {
                                log.warn(e) { "Failed to reload steps for plan ${event.planId}: ${e.message}" }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ============ Plan Actions ============
    
    override fun loadPlans() {
        scope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                val allPlans = planManagementService.getAllPlans(includeTemplates = true)
                _plans.value = allPlans
                
                log.info("Loaded ${allPlans.size} plans")
            } catch (e: Exception) {
                log.warn(e) { "Failed to load plans: ${e.message}" }
                _error.value = "Failed to load plans: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    override fun searchPlans(query: String) {
        _searchQuery.value = query
        
        if (query.isBlank()) {
            loadPlans()
            return
        }
        
        scope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                val results = planManagementService.searchPlans(query, limit = 50)
                _plans.value = results
                
                log.info("Found ${results.size} plans matching '$query'")
            } catch (e: Exception) {
                log.warn(e) { "Search failed: ${e.message}" }
                _error.value = "Search failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    override fun createPlan() {
        _showCreatePlanDialog.value = true
    }
    
    fun confirmCreatePlan(name: String, description: String, isTemplate: Boolean) {
        scope.launch {
            try {
                _isSaving.value = true
                _error.value = null
                
                planManagementService.createPlan(
                    name = name,
                    description = description,
                    isTemplate = isTemplate
                )
                
                _showCreatePlanDialog.value = false
                loadPlans()
                
                log.info("Created plan: $name")
            } catch (e: Exception) {
                log.warn(e) { "Failed to create plan: ${e.message}" }
                _error.value = "Failed to create plan: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    fun dismissCreatePlanDialog() {
        _showCreatePlanDialog.value = false
    }
    
    override fun editPlan(planId: Plan.Id) {
        val plan = _plans.value.find { it.id == planId }
        if (plan != null) {
            _showEditPlanDialog.value = plan
        }
    }
    
    fun confirmEditPlan(planId: Plan.Id, name: String, description: String, isTemplate: Boolean) {
        scope.launch {
            try {
                _isSaving.value = true
                _error.value = null
                
                planManagementService.updatePlan(
                    planId = planId,
                    name = name,
                    description = description,
                    isTemplate = isTemplate
                )
                
                _showEditPlanDialog.value = null
                loadPlans()
                
                log.info("Updated plan: $planId")
            } catch (e: Exception) {
                log.warn(e) { "Failed to update plan: ${e.message}" }
                _error.value = "Failed to update plan: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    fun dismissEditPlanDialog() {
        _showEditPlanDialog.value = null
    }
    
    override fun deletePlan(planId: Plan.Id) {
        val plan = _plans.value.find { it.id == planId }
        if (plan != null) {
            _showDeletePlanDialog.value = plan
        }
    }
    
    fun confirmDeletePlan(planId: Plan.Id) {
        scope.launch {
            try {
                _error.value = null
                
                planManagementService.deletePlan(planId)
                
                _showDeletePlanDialog.value = null
                _expandedPlans.value = _expandedPlans.value - planId
                loadPlans()
                
                log.info("Deleted plan: $planId")
            } catch (e: Exception) {
                log.warn(e) { "Failed to delete plan: ${e.message}" }
                _error.value = "Failed to delete plan: ${e.message}"
            }
        }
    }
    
    fun dismissDeletePlanDialog() {
        _showDeletePlanDialog.value = null
    }
    
    override fun clonePlan(planId: Plan.Id) {
        val plan = _plans.value.find { it.id == planId }
        if (plan != null) {
            _showClonePlanDialog.value = plan
        }
    }
    
    fun confirmClonePlan(sourcePlanId: Plan.Id, newName: String) {
        scope.launch {
            try {
                _isSaving.value = true
                _error.value = null
                
                planManagementService.clonePlan(sourcePlanId, newName)
                
                _showClonePlanDialog.value = null
                loadPlans()
                
                log.info("Cloned plan: $sourcePlanId -> $newName")
            } catch (e: Exception) {
                log.warn(e) { "Failed to clone plan: ${e.message}" }
                _error.value = "Failed to clone plan: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    fun dismissClonePlanDialog() {
        _showClonePlanDialog.value = null
    }
    
    override fun togglePlanExpanded(planId: Plan.Id) {
        val currentExpanded = _expandedPlans.value
        
        if (currentExpanded.containsKey(planId)) {
            // Collapse
            _expandedPlans.value = currentExpanded - planId
        } else {
            // Expand - load steps
            scope.launch {
                try {
                    val (_, steps) = planManagementService.getPlanWithSteps(planId)
                    _expandedPlans.value = currentExpanded + (planId to steps)
                    
                    log.info("Expanded plan $planId with ${steps.size} steps")
                } catch (e: Exception) {
                    log.warn(e) { "Failed to load steps for plan $planId: ${e.message}" }
                    _error.value = "Failed to load steps: ${e.message}"
                }
            }
        }
    }
    
    // ============ Step Actions ============
    
    override fun addStep(planId: Plan.Id, parentId: PlanStep.Id?) {
        // If no parent specified, auto-select last step in the plan
        val steps = _expandedPlans.value[planId] ?: emptyList()
        val autoParentId = parentId ?: steps.lastOrNull()?.id
        
        _showCreateStepDialog.value = planId to autoParentId
    }
    
    fun confirmAddStep(planId: Plan.Id, instruction: String, parentId: PlanStep.Id?) {
        scope.launch {
            try {
                _isSaving.value = true
                _error.value = null
                
                planManagementService.addTextStep(
                    planId = planId,
                    instruction = instruction,
                    parentId = parentId
                )
                
                _showCreateStepDialog.value = null
                
                // Reload steps if plan is expanded
                if (_expandedPlans.value.containsKey(planId)) {
                    val (_, steps) = planManagementService.getPlanWithSteps(planId)
                    _expandedPlans.value = _expandedPlans.value + (planId to steps)
                }
                
                log.info("Added step to plan $planId")
            } catch (e: MultipleRootStepsException) {
                log.warn(e) { "Cannot add root step: ${e.message}" }
                _error.value = "Only one root step allowed. Please select a parent step."
            } catch (e: Exception) {
                log.warn(e) { "Failed to add step: ${e.message}" }
                _error.value = "Failed to add step: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    fun dismissCreateStepDialog() {
        _showCreateStepDialog.value = null
    }
    
    override fun editStep(stepId: PlanStep.Id) {
        // Find step in expanded plans
        val step = _expandedPlans.value.values.flatten().find { it.id == stepId }
        if (step != null) {
            _showEditStepDialog.value = step
        }
    }
    
    fun confirmEditStep(stepId: PlanStep.Id, instruction: String, parentId: PlanStep.Id?) {
        scope.launch {
            try {
                _isSaving.value = true
                _error.value = null
                
                val updatedStep = planManagementService.updateStep(
                    stepId = stepId,
                    instruction = instruction,
                    parentId = parentId
                )
                
                _showEditStepDialog.value = null
                
                // Reload steps if plan is expanded
                if (_expandedPlans.value.containsKey(updatedStep.planId)) {
                    val (_, steps) = planManagementService.getPlanWithSteps(updatedStep.planId)
                    _expandedPlans.value = _expandedPlans.value + (updatedStep.planId to steps)
                }
                
                log.info("Updated step $stepId")
            } catch (e: MultipleRootStepsException) {
                log.warn(e) { "Cannot make step root: ${e.message}" }
                _error.value = "Only one root step allowed. Please select a parent step."
            } catch (e: Exception) {
                log.warn(e) { "Failed to update step: ${e.message}" }
                _error.value = "Failed to update step: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    fun dismissEditStepDialog() {
        _showEditStepDialog.value = null
    }
    
    override fun deleteStep(stepId: PlanStep.Id) {
        val step = _expandedPlans.value.values.flatten().find { it.id == stepId }
        if (step != null) {
            _showDeleteStepDialog.value = step
        }
    }
    
    fun confirmDeleteStep(stepId: PlanStep.Id, planId: Plan.Id) {
        scope.launch {
            try {
                _error.value = null
                
                planManagementService.deleteStep(stepId)
                
                _showDeleteStepDialog.value = null
                
                // Reload steps if plan is expanded
                if (_expandedPlans.value.containsKey(planId)) {
                    val (_, steps) = planManagementService.getPlanWithSteps(planId)
                    _expandedPlans.value = _expandedPlans.value + (planId to steps)
                }
                
                log.info("Deleted step $stepId")
            } catch (e: Exception) {
                log.warn(e) { "Failed to delete step: ${e.message}" }
                _error.value = "Failed to delete step: ${e.message}"
            }
        }
    }
    
    fun dismissDeleteStepDialog() {
        _showDeleteStepDialog.value = null
    }
    
    override fun toggleStepStatus(stepId: PlanStep.Id) {
        scope.launch {
            try {
                _error.value = null
                
                // Find current step to get its status
                val currentStep = _expandedPlans.value.values.flatten().find { it.id == stepId }
                if (currentStep == null) {
                    log.warn { "Step not found: $stepId" }
                    return@launch
                }
                
                // Toggle: PENDING ↔ DONE
                val newStatus = when (currentStep.status) {
                    StepStatus.PENDING -> StepStatus.DONE
                    StepStatus.DONE -> StepStatus.PENDING
                    StepStatus.SKIPPED -> StepStatus.PENDING
                }
                
                val updatedStep = planManagementService.updateStepStatus(stepId, newStatus)
                
                // Reload steps if plan is expanded
                if (_expandedPlans.value.containsKey(updatedStep.planId)) {
                    val (_, steps) = planManagementService.getPlanWithSteps(updatedStep.planId)
                    _expandedPlans.value = _expandedPlans.value + (updatedStep.planId to steps)
                }
                
                log.info("Toggled step $stepId status: ${currentStep.status} -> $newStatus")
            } catch (e: Exception) {
                log.warn(e) { "Failed to toggle step status: ${e.message}" }
                _error.value = "Failed to toggle step status: ${e.message}"
            }
        }
    }
    
    fun moveStepUp(stepId: PlanStep.Id, planId: Plan.Id) {
        scope.launch {
            try {
                _error.value = null
                
                val steps = _expandedPlans.value[planId] ?: return@launch
                
                // Sort steps by parent chain to get correct order
                val sortedSteps = sortStepsByParentChain(steps)
                val currentIndex = sortedSteps.indexOfFirst { it.id == stepId }
                
                if (currentIndex <= 0) return@launch // Already at top or not found
                
                val currentStep = sortedSteps[currentIndex]
                val previousStep = sortedSteps[currentIndex - 1]
                
                // Move up: change current step's parent to previous step's parent
                planManagementService.updateStep(
                    stepId = currentStep.id,
                    instruction = when (currentStep) { is PlanStep.Text -> currentStep.instruction },
                    parentId = previousStep.parentId
                )
                
                // Update previous step to point to current step
                planManagementService.updateStep(
                    stepId = previousStep.id,
                    instruction = when (previousStep) { is PlanStep.Text -> previousStep.instruction },
                    parentId = currentStep.id
                )
                
                // Reload steps
                val (_, updatedSteps) = planManagementService.getPlanWithSteps(planId)
                _expandedPlans.value = _expandedPlans.value + (planId to updatedSteps)
                
                log.info("Moved step up: $stepId")
            } catch (e: Exception) {
                log.warn(e) { "Failed to move step up: ${e.message}" }
                _error.value = "Failed to move step: ${e.message}"
            }
        }
    }
    
    fun moveStepDown(stepId: PlanStep.Id, planId: Plan.Id) {
        scope.launch {
            try {
                _error.value = null
                
                val steps = _expandedPlans.value[planId] ?: return@launch
                
                // Sort steps by parent chain to get correct order
                val sortedSteps = sortStepsByParentChain(steps)
                val currentIndex = sortedSteps.indexOfFirst { it.id == stepId }
                
                if (currentIndex < 0 || currentIndex >= sortedSteps.size - 1) return@launch // Already at bottom or not found
                
                val currentStep = sortedSteps[currentIndex]
                val nextStep = sortedSteps[currentIndex + 1]
                
                // Move down: update next step to point to current step's parent
                planManagementService.updateStep(
                    stepId = nextStep.id,
                    instruction = when (nextStep) { is PlanStep.Text -> nextStep.instruction },
                    parentId = currentStep.parentId
                )
                
                // Update current step to point to next step
                planManagementService.updateStep(
                    stepId = currentStep.id,
                    instruction = when (currentStep) { is PlanStep.Text -> currentStep.instruction },
                    parentId = nextStep.id
                )
                
                // Reload steps
                val (_, updatedSteps) = planManagementService.getPlanWithSteps(planId)
                _expandedPlans.value = _expandedPlans.value + (planId to updatedSteps)
                
                log.info("Moved step down: $stepId")
            } catch (e: Exception) {
                log.warn(e) { "Failed to move step down: ${e.message}" }
                _error.value = "Failed to move step: ${e.message}"
            }
        }
    }
    
    /**
     * Sort steps by parent chain to display them in correct order.
     * 
     * Algorithm:
     * 1. Find root step (parentId == null)
     * 2. Follow the chain: each step's child is the one whose parentId points to it
     * 3. Build linear list from root to last child
     */
    private fun sortStepsByParentChain(steps: List<PlanStep>): List<PlanStep> {
        if (steps.isEmpty()) return emptyList()
        
        // Build map: stepId -> step
        val stepMap = steps.associateBy { it.id }
        
        // Build map: parentId -> list of children
        val childrenMap = steps.groupBy { it.parentId }
        
        // Find root step (no parent)
        val root = steps.firstOrNull { it.parentId == null }
            ?: return steps // Fallback: if no root found, return original order
        
        // Build ordered list by following the chain
        val result = mutableListOf<PlanStep>()
        var current: PlanStep? = root
        
        while (current != null) {
            result.add(current)
            // Find child: step whose parentId points to current step
            val children = childrenMap[current.id] ?: emptyList()
            current = children.firstOrNull() // Take first child (should be only one in linear chain)
        }
        
        return result
    }
}
