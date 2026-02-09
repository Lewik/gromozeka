package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.service.StrideEngineService
import com.gromozeka.domain.tool.stride.*
import com.gromozeka.domain.model.Step
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of StepFailedTool.
 * 
 * Delegates to StrideEngineService for step failure and cascading invalidation logic.
 * 
 * @see com.gromozeka.domain.tool.stride.StepFailedTool Domain specification
 */
@Service
class StepFailedToolImpl(
    private val strideEngineService: StrideEngineService
) : StepFailedTool {
    
    private val logger = LoggerFactory.getLogger(StepFailedToolImpl::class.java)
    
    override fun execute(request: StepFailedRequest, context: ToolContext?): StepFailedResponse {
        return try {
            runBlocking {
                // Mark step as failed and get invalidated dependents
                val invalidatedSteps = strideEngineService.failStep(
                    stepId = Step.Id(request.stepId),
                    error = request.error
                )
                
                logger.info("Step failed: ${request.stepId}, invalidated ${invalidatedSteps.size} dependent steps")
                
                // Convert to response format
                val invalidatedInfo = invalidatedSteps.map { step ->
                    InvalidatedStepInfo(
                        id = step.id.value,
                        text = step.text,
                        reason = "Depends on failed step ${request.stepId}"
                    )
                }
                
                StepFailedResponse(
                    status = "failed",
                    invalidatedSteps = invalidatedInfo,
                    retryable = request.retryable
                )
            }
        } catch (e: Exception) {
            logger.error("Error in step_failed tool: ${e.message}", e)
            StepFailedResponse(
                status = "error",
                invalidatedSteps = emptyList(),
                retryable = false
            )
        }
    }
}
