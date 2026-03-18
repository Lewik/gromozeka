package com.gromozeka.infrastructure.ai.tool.stride

import com.gromozeka.domain.tool.stride.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of RequestUserInputTool.
 * 
 * Simple adapter that formats question for user. No StrideEngineService interaction needed.
 * 
 * Tool returns question text → LLM includes in response → loop exits automatically.
 * Step remains in IN_PROGRESS state, waiting for user answer.
 * 
 * @see com.gromozeka.domain.tool.stride.RequestUserInputTool Domain specification
 */
@Service
class RequestUserInputToolImpl : RequestUserInputTool {
    
    private val logger = LoggerFactory.getLogger(RequestUserInputToolImpl::class.java)
    
    override fun execute(request: RequestUserInputRequest, context: ToolContext?): RequestUserInputResponse {
        logger.info("User input requested (reason: ${request.reason}): ${request.question}")
        
        // Validate reason parameter
        val validReasons = setOf("clarification", "approval", "missing_info", "blocked")
        if (request.reason !in validReasons) {
            logger.warn("Invalid reason: ${request.reason}. Expected one of: $validReasons")
        }
        
        // Return unified tool_result format
        return RequestUserInputResponse(
            status = "waiting_for_user",
            question = request.question,
            options = request.options
        )
    }
}
