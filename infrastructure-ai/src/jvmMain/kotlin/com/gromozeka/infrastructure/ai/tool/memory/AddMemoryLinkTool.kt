package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.repository.MemoryManagementService
import com.gromozeka.domain.tool.memory.AddMemoryLinkRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of AddMemoryLinkTool.
 * 
 * Delegates to MemoryManagementService for business logic.
 * 
 * @see com.gromozeka.domain.tool.memory.AddMemoryLinkTool Domain specification
 */
@Service
class AddMemoryLinkTool(
    private val memoryManagementService: MemoryManagementService
) : com.gromozeka.domain.tool.memory.AddMemoryLinkTool {
    
    private val logger = LoggerFactory.getLogger(AddMemoryLinkTool::class.java)
    
    override fun execute(request: AddMemoryLinkRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val result = runBlocking {
                memoryManagementService.addFactDirectly(
                    from = request.from,
                    relation = request.relation,
                    to = request.to,
                    summary = request.summary
                )
            }

            logger.debug("Added fact directly: ${request.from} -[${request.relation}]-> ${request.to}")

            mapOf(
                "type" to "text",
                "text" to result
            )
        } catch (e: Exception) {
            logger.error("Error adding fact: ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error adding fact to knowledge graph: ${e.message}"
            )
        }
    }
}
