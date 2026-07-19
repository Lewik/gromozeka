package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.filesystem.GrzWriteFileTool
import com.gromozeka.domain.tool.filesystem.WriteFileRequest
import org.slf4j.LoggerFactory
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
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
    
    override fun execute(request: WriteFileRequest, context: ToolExecutionContext?): Map<String, Any> {
        val workspaceRootPath = context.requiredWorkspaceRootPath()
        return try {
            val file = resolveFile(request.file_path, workspaceRootPath)
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
    
    private fun resolveFile(path: String, workspaceRootPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(workspaceRootPath, path)
    }
}
