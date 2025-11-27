package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.filesystem.GrzEditFileTool
import com.gromozeka.domain.tool.filesystem.EditFileRequest
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.io.File

/**
 * Infrastructure implementation of GrzEditFileTool.
 * 
 * Delegates to domain specification and integrates with Spring AI.
 * 
 * @see com.gromozeka.domain.tool.filesystem.GrzEditFileTool Full specification
 */
@Service
class GrzEditFileToolImpl : GrzEditFileTool {
    
    private val logger = LoggerFactory.getLogger(GrzEditFileToolImpl::class.java)
    
    override fun execute(request: EditFileRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: error("Project path is required in tool context - this is a bug!")
            
            val file = resolveFile(request.file_path, projectPath)
            
            when {
                !file.exists() -> mapOf("error" to "File not found: ${request.file_path}")
                !file.isFile -> mapOf("error" to "Path is not a file: ${request.file_path}")
                request.old_string == request.new_string -> mapOf("error" to "old_string and new_string must be different")
                request.old_string.isEmpty() -> mapOf("error" to "old_string cannot be empty")
                else -> {
                    val content = file.readText()
                    val occurrences = content.split(request.old_string).size - 1
                    
                    when {
                        occurrences == 0 -> mapOf("error" to "old_string not found in file")
                        occurrences > 1 && !request.replace_all -> mapOf(
                            "error" to "old_string appears $occurrences times in file. Use replace_all=true to replace all occurrences, or provide more context to make old_string unique."
                        )
                        else -> {
                            val newContent = if (request.replace_all) {
                                content.replace(request.old_string, request.new_string)
                            } else {
                                content.replaceFirst(request.old_string, request.new_string)
                            }
                            
                            file.writeText(newContent)
                            val replacements = if (request.replace_all) occurrences else 1
                            logger.debug("Replaced $replacements occurrence(s) in ${file.absolutePath}")
                            
                            mapOf(
                                "success" to true,
                                "path" to file.absolutePath,
                                "replacements" to replacements,
                                "bytes" to newContent.toByteArray().size
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error editing file: ${request.file_path}", e)
            mapOf("error" to "Error editing file: ${e.message}")
        }
    }
    
    private fun resolveFile(path: String, projectPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(projectPath, path)
    }
}
