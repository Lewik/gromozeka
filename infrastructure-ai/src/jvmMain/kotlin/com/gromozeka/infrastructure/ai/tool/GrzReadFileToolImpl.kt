package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.service.FileSearchService
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.GrzReadFileTool
import com.gromozeka.domain.tool.filesystem.ReadFileRequest
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.io.File

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
    
    override fun execute(request: ReadFileRequest, context: ToolExecutionContext?): List<AiToolResult> {
        val workspaceRootPath = context.requiredWorkspaceRootPath()
        return try {
            val file = resolveFile(request.file_path, workspaceRootPath)
            
            when {
                !file.exists() -> {
                    val suggestions = fileSearchService.findSimilarFiles(
                        targetPath = request.file_path,
                        workspaceRootPath = workspaceRootPath,
                        limit = 5
                    )
                    
                    if (suggestions.isNotEmpty()) {
                        textResult(
                            "File not found: ${request.file_path}\n" +
                                "Suggestions:\n" + suggestions.joinToString("\n") { "- $it" }
                        )
                    } else {
                        textResult("File not found: ${request.file_path}")
                    }
                }
                !file.isFile -> textResult("Path is not a file: ${request.file_path}")
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
            textResult("Error reading file: ${e.message}")
        }
    }
    
    private fun resolveFile(path: String, workspaceRootPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(workspaceRootPath, path)
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
    
    private fun readImageFile(file: File, mimeType: String): List<AiToolResult> =
        readBinaryFile(file, mimeType)
    
    private fun readPdfFile(file: File): List<AiToolResult> =
        readBinaryFile(file, "application/pdf")
    
    private fun readBinaryFile(file: File, mimeType: String): List<AiToolResult> {
        require(file.length() <= ArtifactLimits.MAX_FILE_BYTES) {
            "Binary file exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB artifact limit: ${file.name}"
        }
        return listOf(AiToolResult.Binary(file.readBytes(), file.name, mimeType))
    }

    private fun readTextFile(file: File, limit: Int, offset: Int): List<AiToolResult> {
        require(offset >= 0) { "Offset must not be negative (got: $offset)" }
        require(limit == -1 || limit > 0) {
            "Limit must be positive or -1 for entire file (got: $limit)"
        }

        val selectedLines = mutableListOf<String>()
        var totalLines = 0
        file.useLines { lines ->
            lines.forEach { line ->
                if (totalLines >= offset && (limit == -1 || selectedLines.size < limit)) {
                    val truncated = if (line.length > 2000) line.take(2000) + "..." else line
                    selectedLines += "${totalLines + 1}\t$truncated"
                }
                totalLines++
            }
        }
        val actualLinesRead = selectedLines.size
        val content = selectedLines.joinToString("\n")
        
        // Add metadata about the file to help AI understand what was read
        val metadata = if (totalLines == 0) {
            "\n[Empty file]"
        } else if (actualLinesRead == 0) {
            "\n[Read 0 lines of $totalLines total]"
        } else {
            val firstLine = minOf(offset + 1, totalLines)
            val lastLine = offset + actualLinesRead
            "\n[Read lines $firstLine-$lastLine of $totalLines total]" +
                if (lastLine < totalLines) " (more lines available)" else ""
        }
        
        return textResult(content + metadata)
    }

    private fun textResult(content: String): List<AiToolResult> =
        listOf(AiToolResult.Text(content))
}
