package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.tool.stride.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of AskUserTool.
 * 
 * Simple adapter that formats question for user. No StrideEngineService interaction needed.
 * 
 * Tool returns question text → LLM includes in response → loop exits automatically.
 * Step remains in EXECUTING state, waiting for user answer.
 * 
 * @see com.gromozeka.domain.tool.stride.AskUserTool Domain specification
 */
@Service
class AskUserToolImpl : AskUserTool {
    
    private val logger = LoggerFactory.getLogger(AskUserToolImpl::class.java)
    
    override fun execute(request: AskUserRequest, context: ToolContext?): AskUserResponse {
        logger.info("User input requested for step ${request.stepId}: ${request.question}")
        
        // Format question with options if provided
        val formattedMessage = if (request.options.isNotEmpty()) {
            val optionsText = request.options.joinToString("\n") { "- $it" }
            "${request.question}\n\nOptions:\n$optionsText"
        } else {
            request.question
        }
        
        // Add context if provided
        val fullMessage = if (request.context != null) {
            "$formattedMessage\n\nContext: ${request.context}"
        } else {
            formattedMessage
        }
        
        return AskUserResponse(
            status = "waiting_for_user",
            message = fullMessage
        )
    }
}
