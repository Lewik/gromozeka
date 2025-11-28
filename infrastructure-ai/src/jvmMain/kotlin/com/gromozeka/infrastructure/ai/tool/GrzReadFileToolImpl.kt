package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.FileSearchService
import com.gromozeka.domain.tool.filesystem.GrzReadFileTool
import com.gromozeka.domain.tool.filesystem.ReadFileRequest
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.io.File
import java.util.Base64

/**
 * Infrastructure implementation of GrzReadFileTool.
 * 
 * Delegates to domain specification and integrates with Spring AI.
 * 
 * @see com.gromozeka.domain.tool.filesystem.GrzReadFileTool Full specification
 */
@Service
class GrzReadFileToolImpl(
    private val fileSearchService: FileSearchService
) : GrzReadFileTool {
    
    private val logger = LoggerFactory.getLogger(GrzReadFileToolImpl::class.java)
    
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: error("Project path is required in tool context - this is a bug!")
            
            val file = resolveFile(request.file_path, projectPath)
            
            when {
                !file.exists() -> {
                    val suggestions = fileSearchService.findSimilarFiles(
                        targetPath = request.file_path,
                        projectPath = projectPath,
                        limit = 5
                    )
                    
                    if (suggestions.isNotEmpty()) {
                        mapOf(
                            "error" to "File not found: ${request.file_path}",
                            "suggestions" to suggestions
                        )
                    } else {
                        mapOf("error" to "File not found: ${request.file_path}")
                    }
                }
                !file.isFile -> mapOf("error" to "Path is not a file: ${request.file_path}")
                else -> {
                    val mimeType = detectMimeType(file)
                    logger.debug("Reading file: ${file.name}, type: $mimeType")
                    
                    when {
                        mimeType.startsWith("image/") -> readImageFile(file, mimeType)
                        mimeType == "application/pdf" -> readPdfFile(file)
                        else -> readTextFile(file, request.limit, request.offset)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error reading file: ${request.file_path}", e)
            mapOf("error" to "Error reading file: ${e.message}")
        }
    }
    
    private fun resolveFile(path: String, projectPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(projectPath, path)
    }
    
    private fun detectMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> "text/plain"
        }
    }
    
    private fun readImageFile(file: File, mimeType: String): Map<String, Any> {
        val bytes = file.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        
        return mapOf(
            "type" to "text",
            "text" to "Successfully read image: ${file.name} (${bytes.size} bytes, $mimeType)",
            "additionalContent" to listOf(
                mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to mimeType,
                        "data" to base64
                    )
                )
            )
        )
    }
    
    private fun readPdfFile(file: File): Map<String, Any> {
        val bytes = file.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        
        return mapOf(
            "type" to "document",
            "source" to mapOf(
                "type" to "base64",
                "media_type" to "application/pdf",
                "data" to base64
            )
        )
    }
    
    private fun readTextFile(file: File, limit: Int, offset: Int): Map<String, Any> {
        val lines = file.readLines()
        val totalLines = lines.size
        val startLine = offset
        
        // Safe default (1000 lines) with -1 for full file (AI-native pattern)
        val maxLines = when {
            limit == -1 -> lines.size - startLine  // Explicit intent to read entire file
            limit <= 0 -> throw IllegalArgumentException("Limit must be positive or -1 for entire file (got: $limit)")
            else -> limit
        }
        
        val selectedLines = lines.drop(startLine).take(maxLines)
        val actualLinesRead = selectedLines.size
        
        val content = selectedLines.mapIndexed { index, line ->
            val lineNumber = startLine + index + 1
            val truncated = if (line.length > 2000) line.take(2000) + "..." else line
            "$lineNumber\t$truncated"
        }.joinToString("\n")
        
        // Add metadata about the file to help AI understand what was read
        val metadata = if (totalLines > 0) {
            "\n[Read lines ${startLine + 1}-${startLine + actualLinesRead} of $totalLines total]" +
            if (startLine + actualLinesRead < totalLines) " (more lines available)" else ""
        } else {
            "\n[Empty file]"
        }
        
        return mapOf(
            "type" to "text",
            "text" to content + metadata
        )
    }
}
