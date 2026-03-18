package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.tool.stride.StepCompleteRequest
import com.gromozeka.domain.tool.stride.StepCompleteResponse
import com.gromozeka.domain.tool.stride.StepCompleteTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of StepCompleteTool.
 * 
 * Handles both successful completion and failure cases via status parameter.
 * Returns unified tool_result format with next step or completion instruction.
 * 
 * @see com.gromozeka.domain.tool.stride.StepCompleteTool Domain specification
 */
@Service
class StepCompleteToolImpl(
    private val strideEngineService: StrideEngineService
) : StepCompleteTool {
    
    private val logger = LoggerFactory.getLogger(StepCompleteToolImpl::class.java)
    
    override fun execute(request: StepCompleteRequest, context: ToolContext?): StepCompleteResponse {
        logger.debug("StepCompleteTool called: status=${request.status}, result=${request.result.take(100)}")
        
        // Get planId from ToolContext
        val contextMap = context?.getContext()
            ?: throw IllegalArgumentException("ToolContext is required for step_complete tool")
        
        val planId = contextMap["planId"] as? String
            ?: throw IllegalArgumentException(
                "planId not found in ToolContext. " +
                "ConversationEngine must provide planId in ToolContext when plan is active."
            )
        
        // Validate status parameter
        if (request.status !in setOf("success", "fail")) {
            throw IllegalArgumentException(
                "Invalid status: ${request.status}. Must be 'success' or 'fail'"
            )
        }
        
        // Call StrideEngineService
        val planResult = runBlocking {
            strideEngineService.completeStep(
                planId = Plan.Id(planId),
                status = request.status,
                result = request.result
            )
        }
        
        logger.info(
            "Step completed with status=${request.status}. " +
            "Current step: ${planResult.currentStepId ?: "none (plan finished)"}"
        )
        
        // PlanResult and StepCompleteResponse have identical structure
        return StepCompleteResponse(
            planId = planResult.planId,
            steps = planResult.steps,
            currentStepId = planResult.currentStepId,
            instruction = planResult.instruction
        )
    }
}
