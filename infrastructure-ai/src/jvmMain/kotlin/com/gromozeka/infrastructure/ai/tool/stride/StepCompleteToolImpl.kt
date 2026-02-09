package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.tool.stride.*
import com.gromozeka.domain.model.Step
import com.gromozeka.domain.model.Plan
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of StepCompleteTool.
 * 
 * Delegates to StrideEngineService for step completion logic.
 * 
 * @see com.gromozeka.domain.tool.stride.StepCompleteTool Domain specification
 */
@Service
class StepCompleteToolImpl(
    private val strideEngineService: StrideEngineService
) : StepCompleteTool {
    
    private val logger = LoggerFactory.getLogger(StepCompleteToolImpl::class.java)
    
    override fun execute(request: StepCompleteRequest, context: ToolContext?): StepCompleteResponse {
        return try {
            runBlocking {
                // Mark step as completed
                strideEngineService.completeStep(
                    stepId = Step.Id(request.stepId),
                    result = request.result
                )
                
                logger.info("Step completed: ${request.stepId}")
                
                // Get step to find its plan
                val stepId = Step.Id(request.stepId)
                // We need to get planId from the step, but StrideEngineService doesn't expose this
                // For now, we'll need to get it from context or assume it's available
                val planId = context?.getContext()?.get("planId") as? String
                    ?: throw IllegalArgumentException("planId not found in ToolContext")
                
                // Check if there are more steps
                val hasMoreSteps = strideEngineService.hasMoreSteps(Plan.Id(planId))
                
                if (hasMoreSteps) {
                    // Get next step
                    val nextStep = strideEngineService.getNextStep(Plan.Id(planId))
                    
                    if (nextStep != null) {
                        logger.debug("Next step: ${nextStep.text}")
                        StepCompleteResponse(
                            status = "completed",
                            nextStep = NextStepInfo(
                                id = nextStep.id.value,
                                text = nextStep.text,
                                type = nextStep.type.name
                            )
                        )
                    } else {
                        // No executable steps (waiting for dependencies or all done)
                        StepCompleteResponse(
                            status = "completed",
                            nextStep = null
                        )
                    }
                } else {
                    // Plan completed
                    strideEngineService.completePlan(
                        planId = Plan.Id(planId),
                        status = Plan.Status.COMPLETED
                    )
                    
                    logger.info("Plan completed: $planId")
                    
                    StepCompleteResponse(
                        status = "plan_completed",
                        nextStep = null
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error in step_complete tool: ${e.message}", e)
            StepCompleteResponse(
                status = "error",
                nextStep = null
            )
        }
    }
}
