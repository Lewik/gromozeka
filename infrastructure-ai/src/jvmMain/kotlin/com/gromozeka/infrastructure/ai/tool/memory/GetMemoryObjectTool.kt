package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.bot.domain.repository.MemoryManagementService
import com.gromozeka.domain.tool.memory.GetMemoryObjectRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of GetMemoryObjectTool.
 * 
 * Delegates to MemoryManagementService for entity retrieval.
 * 
 * @see com.gromozeka.domain.tool.memory.GetMemoryObjectTool Domain specification
 */
@Service
class GetMemoryObjectTool(
    private val memoryManagementService: MemoryManagementService
) : com.gromozeka.domain.tool.memory.GetMemoryObjectTool {
    
    private val logger = LoggerFactory.getLogger(GetMemoryObjectTool::class.java)
    
    override fun execute(request: GetMemoryObjectRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val details = runBlocking {
                memoryManagementService.getEntityDetails(request.name)
            }

            logger.debug("Retrieved entity details for: ${request.name}")

            mapOf(
                "type" to "text",
                "text" to details
            )
        } catch (e: Exception) {
            logger.error("Error getting entity details for '${request.name}': ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error getting entity details: ${e.message}"
            )
        }
    }
}
