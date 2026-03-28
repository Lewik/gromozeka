package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.service.StepInput
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.tool.stride.UpdatePlanRequest
import com.gromozeka.domain.tool.stride.UpdatePlanResponse
import com.gromozeka.domain.tool.stride.UpdatePlanTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of UpdatePlanTool.
 * 
 * Modifies execution plan on the fly based on new information or user input.
 * Returns unified tool_result format with updated plan state and instruction.
 * 
 * @see com.gromozeka.domain.tool.stride.UpdatePlanTool Domain specification
 */
@Service
class UpdatePlanToolImpl(
    private val strideEngineService: StrideEngineService
) : UpdatePlanTool {
    
    private val logger = LoggerFactory.getLogger(UpdatePlanToolImpl::class.java)
    
    override fun execute(request: UpdatePlanRequest, context: ToolExecutionContext?): UpdatePlanResponse {
        logger.debug("UpdatePlanTool called with ${request.steps.size} steps")
        
        // Get planId from ToolExecutionContext
        val contextMap = context?.getContext()
            ?: throw IllegalArgumentException("ToolExecutionContext is required for update_plan tool")
        
        val planId = contextMap["planId"] as? String
            ?: throw IllegalArgumentException(
                "planId not found in ToolExecutionContext. " +
                "ConversationEngine must provide planId in ToolExecutionContext when plan is active."
            )
        
        // Convert request.steps to domain StepInput
        val stepInputs = request.steps.map { step ->
            StepInput(
                text = step.text,
                type = step.type,
                certainty = step.certainty,
                entities = step.entities,
                dependsOn = step.dependsOn
            )
        }
        
        // Call StrideEngineService
        val updateResult = runBlocking {
            strideEngineService.updatePlan(
                planId = Plan.Id(planId),
                stepInputs = stepInputs
            )
        }
        
        if (updateResult.ok) {
            logger.info("Plan ${updateResult.planId} updated with ${updateResult.steps?.size ?: 0} steps")
        } else {
            logger.warn("Plan update failed: ${updateResult.error}")
        }
        
        // UpdatePlanResult and UpdatePlanResponse have identical structure
        return UpdatePlanResponse(
            ok = updateResult.ok,
            error = updateResult.error,
            planId = updateResult.planId,
            steps = updateResult.steps,
            currentStepId = updateResult.currentStepId,
            instruction = updateResult.instruction
        )
    }
}
