package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.StepInput
import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.tool.stride.CreatePlanRequest
import com.gromozeka.domain.tool.stride.CreatePlanResponse
import com.gromozeka.domain.tool.stride.CreatePlanTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of CreatePlanTool.
 * 
 * Creates execution plan from semantic step definitions.
 * Returns unified tool_result format with plan state and instruction for LLM.
 * 
 * @see com.gromozeka.domain.tool.stride.CreatePlanTool Domain specification
 */
@Service
class CreatePlanToolImpl(
    private val strideEngineService: StrideEngineService
) : CreatePlanTool {
    
    private val logger = LoggerFactory.getLogger(CreatePlanToolImpl::class.java)
    
    override fun execute(request: CreatePlanRequest, context: ToolContext?): CreatePlanResponse {
        logger.debug("CreatePlanTool called with ${request.steps.size} steps")
        
        // Get conversationId from ToolContext
        val contextMap = context?.getContext() 
            ?: throw IllegalArgumentException("ToolContext is required for create_plan tool")
        
        val conversationId = contextMap["conversationId"] as? String
            ?: throw IllegalArgumentException(
                "conversationId not found in ToolContext. " +
                "ConversationEngine must provide conversationId in ToolContext."
            )
        
        // Convert request.steps to domain StepInput
        // They should be structurally identical, but may come from different packages
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
        val planResult = runBlocking {
            strideEngineService.createPlan(
                conversationId = Conversation.Id(conversationId),
                stepInputs = stepInputs
            )
        }
        
        logger.info("Created plan ${planResult.planId} with ${planResult.steps.size} steps")
        
        // PlanResult and CreatePlanResponse have identical structure
        // Just convert directly
        return CreatePlanResponse(
            planId = planResult.planId,
            steps = planResult.steps,
            currentStepId = planResult.currentStepId ?: 0,
            instruction = planResult.instruction
        )
    }
}
