package com.gromozeka.bot.config.tools

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Description
import java.io.File
import java.util.Base64
import java.util.function.Function

@Configuration
class ReadFileTool {

    private val logger = LoggerFactory.getLogger(ReadFileTool::class.java)

    @Bean
    @Description("Read file contents. Supports text files, images (PNG, JPEG, GIF, WebP), and PDF documents. Returns file content in appropriate format.")
    fun read_file(): Function<ReadFileRequest, Any> {
        return Function { request ->
            try {
                val file = File(request.file_path)

                if (!file.exists()) {
                    return@Function ReadFileError("File not found: ${request.file_path}")
                }

                if (!file.isFile) {
                    return@Function ReadFileError("Path is not a file: ${request.file_path}")
                }

                val mimeType = detectMimeType(file)
                logger.debug("Reading file: ${file.name}, detected type: $mimeType")

                when {
                    mimeType.startsWith("image/") -> readImageFile(file, mimeType)
                    mimeType == "application/pdf" -> readPdfFile(file)
                    else -> readTextFile(file, request.limit, request.offset)
                }
            } catch (e: Exception) {
                logger.error("Error reading file: ${request.file_path}", e)
                ReadFileError("Error reading file: ${e.message}")
            }
        }
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
            "type" to "image",
            "source" to mapOf(
                "type" to "base64",
                "media_type" to mimeType,
                "data" to base64
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

    private fun readTextFile(file: File, limit: Int?, offset: Int?): Map<String, String> {
        val lines = file.readLines()
        val startLine = offset ?: 0
        val maxLines = limit ?: 2000

        val selectedLines = lines.drop(startLine).take(maxLines)
        val content = selectedLines.mapIndexed { index, line ->
            val lineNumber = startLine + index + 1
            val truncated = if (line.length > 2000) line.take(2000) + "..." else line
            "$lineNumber\t$truncated"
        }.joinToString("\n")

        return mapOf("content" to content)
    }
}

data class ReadFileRequest(
    val file_path: String,
    val offset: Int? = null,
    val limit: Int? = null
)

data class ReadFileError(val error: String)
