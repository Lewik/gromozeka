package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.domain.repository.MemoryManagementService
import com.gromozeka.domain.tool.memory.UpdateMemoryObjectRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of UpdateMemoryObjectTool.
 * 
 * Delegates to MemoryManagementService for entity updates.
 * 
 * @see com.gromozeka.domain.tool.memory.UpdateMemoryObjectTool Domain specification
 */
@Service
class UpdateMemoryObjectTool(
    private val memoryManagementService: MemoryManagementService
) : com.gromozeka.domain.tool.memory.UpdateMemoryObjectTool {
    
    private val logger = LoggerFactory.getLogger(UpdateMemoryObjectTool::class.java)
    
    override fun execute(request: UpdateMemoryObjectRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val result = runBlocking {
                memoryManagementService.updateEntity(
                    name = request.name,
                    newSummary = request.newSummary,
                    newType = request.newType
                )
            }

            logger.debug("Updated entity: ${request.name}")

            mapOf(
                "type" to "text",
                "text" to result
            )
        } catch (e: Exception) {
            logger.error("Error updating entity '${request.name}': ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error updating entity: ${e.message}"
            )
        }
    }
}
