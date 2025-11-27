package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.filesystem.GrzWriteFileTool
import com.gromozeka.domain.tool.filesystem.WriteFileRequest
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.io.File

/**
 * Infrastructure implementation of GrzWriteFileTool.
 * 
 * Delegates to domain specification and integrates with Spring AI.
 * 
 * @see com.gromozeka.domain.tool.filesystem.GrzWriteFileTool Full specification
 */
@Service
class GrzWriteFileToolImpl : GrzWriteFileTool {
    
    private val logger = LoggerFactory.getLogger(GrzWriteFileToolImpl::class.java)
    
    override fun execute(request: WriteFileRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: error("Project path is required in tool context - this is a bug!")
            
            val file = resolveFile(request.file_path, projectPath)
            file.parentFile?.mkdirs()
            file.writeText(request.content)
            
            logger.debug("Written ${request.content.length} chars to ${file.absolutePath}")
            
            mapOf(
                "success" to true,
                "path" to file.absolutePath,
                "bytes" to request.content.toByteArray().size
            )
        } catch (e: Exception) {
            logger.error("Error writing file: ${request.file_path}", e)
            mapOf("error" to "Error writing file: ${e.message}")
        }
    }
    
    private fun resolveFile(path: String, projectPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(projectPath, path)
    }
}
