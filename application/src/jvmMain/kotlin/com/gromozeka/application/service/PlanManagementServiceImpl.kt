package com.gromozeka.application.service

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.domain.repository.MultipleRootStepsException
import com.gromozeka.domain.repository.PlanNotFoundException
import com.gromozeka.domain.repository.PlanRepository
import com.gromozeka.domain.repository.StepNotFoundException
import com.gromozeka.domain.service.plan.PlanManagementService
import com.gromozeka.domain.service.plan.PlanEventBus
import com.gromozeka.domain.service.plan.PlanEvent
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implementation of PlanManagementService.
 *
 * Coordinates between PlanRepository and provides high-level operations
 * for plan and step management.
 *
 * Key responsibilities:
 * - Generate IDs and timestamps automatically
 * - Handle partial updates (only non-null parameters)
 * - Implement clonePlan logic with status reset
 * - Default naming for cloned plans
 */
@Service
class PlanManagementServiceImpl(
    private val planRepository: PlanRepository,
    private val planEventBus: PlanEventBus
) : PlanManagementService {

    // ============ Plan Operations ============

    @Transactional
    override suspend fun createPlan(
        name: String,
        description: String,
        isTemplate: Boolean
    ): Plan {
        val plan = Plan(
            id = Plan.Id.generate(),
            name = name,
            description = description,
            isTemplate = isTemplate,
            createdAt = Clock.System.now()
        )
        val result = planRepository.createPlan(plan)
        planEventBus.emit(PlanEvent.PlansChanged)
        return result
    }

    @Transactional
    override suspend fun updatePlan(
        planId: Plan.Id,
        name: String?,
        description: String?,
        isTemplate: Boolean?
    ): Plan {
        val existing = planRepository.findPlanById(planId)
            ?: throw PlanNotFoundException(planId)

        val updated = Plan(
            id = existing.id,
            name = name ?: existing.name,
            description = description ?: existing.description,
            isTemplate = isTemplate ?: existing.isTemplate,
            createdAt = existing.createdAt
        )

        val result = planRepository.updatePlan(updated)
        planEventBus.emit(PlanEvent.PlansChanged)
        return result
    }

    @Transactional
    override suspend fun deletePlan(planId: Plan.Id) {
        planRepository.deletePlan(planId)
        planEventBus.emit(PlanEvent.PlansChanged)
    }

    override suspend fun getPlanWithSteps(planId: Plan.Id): Pair<Plan, List<PlanStep>> {
        val plan = planRepository.findPlanById(planId)
            ?: throw PlanNotFoundException(planId)
        
        val steps = planRepository.findStepsByPlanId(planId)
        
        return Pair(plan, steps)
    }

    @Transactional
    override suspend fun clonePlan(sourcePlanId: Plan.Id, newName: String?): Plan {
        val sourcePlan = planRepository.findPlanById(sourcePlanId)
            ?: throw PlanNotFoundException(sourcePlanId)

        val clonedName = newName ?: "Copy of ${sourcePlan.name}"
        
        val result = planRepository.clonePlan(sourcePlanId, clonedName)
        planEventBus.emit(PlanEvent.PlansChanged)
        return result
    }

    override suspend fun searchPlans(query: String, limit: Int): List<Plan> {
        return planRepository.searchPlans(query, limit)
    }

    override suspend fun getAllPlans(includeTemplates: Boolean): List<Plan> {
        return planRepository.findAllPlans(includeTemplates)
    }

    // ============ Step Operations ============

    @Transactional
    override suspend fun addTextStep(
        planId: Plan.Id,
        instruction: String,
        parentId: PlanStep.Id?
    ): PlanStep.Text {
        // Verify plan exists
        planRepository.findPlanById(planId)
            ?: throw PlanNotFoundException(planId)

        // Verify parent exists if specified
        if (parentId != null) {
            planRepository.findStepById(parentId)
                ?: throw StepNotFoundException(parentId)
        }
        
        // Validate: only one root step allowed
        if (parentId == null) {
            val existingSteps = planRepository.findStepsByPlanId(planId)
            val hasRootStep = existingSteps.any { it.parentId == null }
            if (hasRootStep) {
                throw MultipleRootStepsException(planId)
            }
        }

        val step = PlanStep.Text(
            id = PlanStep.Id.generate(),
            planId = planId,
            parentId = parentId,
            status = StepStatus.PENDING,
            instruction = instruction,
            result = null
        )

        val result = planRepository.addStep(step) as PlanStep.Text
        planEventBus.emit(PlanEvent.StepsChanged(result.planId))
        return result
    }

    @Transactional
    override suspend fun updateStep(
        stepId: PlanStep.Id,
        instruction: String?,
        result: String?,
        status: StepStatus?,
        parentId: PlanStep.Id?
    ): PlanStep {
        val existing = planRepository.findStepById(stepId)
            ?: throw StepNotFoundException(stepId)

        // Determine new parentId (use provided or keep existing)
        val newParentId = parentId ?: existing.parentId
        
        // Verify parent exists if specified
        if (newParentId != null) {
            planRepository.findStepById(newParentId)
                ?: throw StepNotFoundException(newParentId)
        }
        
        // Validate: only one root step allowed
        // Check if we're trying to make this step a root when another root already exists
        if (newParentId == null && existing.parentId != null) {
            // Changing from non-root to root
            val existingSteps = planRepository.findStepsByPlanId(existing.planId)
            val otherRootSteps = existingSteps.filter { it.parentId == null && it.id != stepId }
            if (otherRootSteps.isNotEmpty()) {
                throw MultipleRootStepsException(existing.planId)
            }
        }

        val updated = when (existing) {
            is PlanStep.Text -> existing.copy(
                instruction = instruction ?: existing.instruction,
                result = result ?: existing.result,
                status = status ?: existing.status,
                parentId = newParentId
            )
        }

        val updatedResult = planRepository.updateStep(updated)
        planEventBus.emit(PlanEvent.StepsChanged(updatedResult.planId))
        return updatedResult
    }

    @Transactional
    override suspend fun deleteStep(stepId: PlanStep.Id) {
        planRepository.deleteStep(stepId)
        planEventBus.emit(PlanEvent.PlansChanged)
    }

    @Transactional
    override suspend fun updateStepStatus(stepId: PlanStep.Id, status: StepStatus): PlanStep {
        val existing = planRepository.findStepById(stepId)
            ?: throw StepNotFoundException(stepId)

        val updated = when (existing) {
            is PlanStep.Text -> existing.copy(status = status)
        }

        val result = planRepository.updateStep(updated)
        planEventBus.emit(PlanEvent.StepsChanged(result.planId))
        return result
    }
}
