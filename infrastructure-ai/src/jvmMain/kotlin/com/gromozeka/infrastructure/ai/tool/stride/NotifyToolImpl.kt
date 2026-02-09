package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.tool.stride.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of NotifyTool.
 * 
 * Simple adapter that formats notification message. No StrideEngineService interaction needed.
 * 
 * Tool returns acknowledgment → execution continues immediately.
 * 
 * @see com.gromozeka.domain.tool.stride.NotifyTool Domain specification
 */
@Service
class NotifyToolImpl : NotifyTool {
    
    private val logger = LoggerFactory.getLogger(NotifyToolImpl::class.java)
    
    override fun execute(request: NotifyRequest, context: ToolContext?): NotifyResponse {
        // Log notification based on level
        when (request.level) {
            "info" -> logger.info("Step ${request.stepId} notification: ${request.message}")
            "success" -> logger.info("Step ${request.stepId} success: ${request.message}")
            "warning" -> logger.warn("Step ${request.stepId} warning: ${request.message}")
            else -> logger.debug("Step ${request.stepId} notification (${request.level}): ${request.message}")
        }
        
        // In future, this could trigger UI notification via event bus
        // For now, just return acknowledgment
        
        return NotifyResponse(
            status = "notified",
            message = "Notification sent to user"
        )
    }
}
