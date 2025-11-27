package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.repository.MemoryManagementService
import com.gromozeka.domain.tool.memory.InvalidateMemoryLinkRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of InvalidateMemoryLinkTool.
 * 
 * Delegates to MemoryManagementService for fact invalidation.
 * 
 * @see com.gromozeka.domain.tool.memory.InvalidateMemoryLinkTool Domain specification
 */
@Service
class InvalidateMemoryLinkTool(
    private val memoryManagementService: MemoryManagementService
) : com.gromozeka.domain.tool.memory.InvalidateMemoryLinkTool {
    
    private val logger = LoggerFactory.getLogger(InvalidateMemoryLinkTool::class.java)
    
    override fun execute(request: InvalidateMemoryLinkRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val result = runBlocking {
                memoryManagementService.invalidateFact(
                    from = request.from,
                    relation = request.relation,
                    to = request.to
                )
            }

            logger.debug("Invalidated fact: ${request.from} -[${request.relation}]-> ${request.to}")

            mapOf(
                "type" to "text",
                "text" to result
            )
        } catch (e: Exception) {
            logger.error("Error invalidating fact: ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error invalidating fact: ${e.message}"
            )
        }
    }
}
