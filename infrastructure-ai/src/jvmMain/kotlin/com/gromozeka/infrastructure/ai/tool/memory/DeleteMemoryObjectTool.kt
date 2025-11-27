package com.gromozeka.infrastructure.ai.tool.memory

import com.gromozeka.bot.domain.repository.MemoryManagementService
import com.gromozeka.domain.tool.memory.DeleteMemoryObjectRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service

/**
 * Infrastructure implementation of DeleteMemoryObjectTool.
 * 
 * Delegates to MemoryManagementService for entity deletion.
 * 
 * @see com.gromozeka.domain.tool.memory.DeleteMemoryObjectTool Domain specification
 */
@Service
class DeleteMemoryObjectTool(
    private val memoryManagementService: MemoryManagementService
) : com.gromozeka.domain.tool.memory.DeleteMemoryObjectTool {
    
    private val logger = LoggerFactory.getLogger(DeleteMemoryObjectTool::class.java)
    
    override fun execute(request: DeleteMemoryObjectRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val result = runBlocking {
                memoryManagementService.hardDeleteEntity(
                    name = request.name,
                    cascade = request.cascade
                )
            }

            logger.warn("HARD DELETED entity: ${request.name} (cascade: ${request.cascade})")

            mapOf(
                "type" to "text",
                "text" to result
            )
        } catch (e: Exception) {
            logger.error("Error hard deleting entity '${request.name}': ${e.message}", e)
            mapOf(
                "type" to "text",
                "text" to "Error hard deleting entity: ${e.message}"
            )
        }
    }
}
