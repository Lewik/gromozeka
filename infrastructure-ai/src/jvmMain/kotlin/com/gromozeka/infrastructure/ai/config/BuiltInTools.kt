package com.gromozeka.infrastructure.ai.config

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

@Configuration
class BuiltInTools {

    private val logger = LoggerFactory.getLogger(BuiltInTools::class.java)

    private fun resolveFile(path: String, projectPath: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(projectPath, path)
    }

    @Bean
    fun readFileTool(): ToolCallback {
        val function = object : BiFunction<ReadFileParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: ReadFileParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val projectPath = context?.getContext()?.get("projectPath") as? String
                        ?: error("Project path is required in tool context - this is a bug!")
                    val file = resolveFile(request.file_path, projectPath)

                    when {
                        !file.exists() -> mapOf("error" to "File not found: ${request.file_path}")
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
        }

        return FunctionToolCallback.builder("grz_read_file", function)
            .description("""
                Read file contents with safety limits and metadata.
                
                Parameters:
                - file_path: Path to the file (required)
                - limit: Max lines to read (optional, default: 1000 for safety, use -1 for entire file)
                - offset: Skip first N lines (optional, default: 0)
                
                Safety & Control:
                - Default reads first 1000 lines (prevents accidental large file loads)
                - Use limit=-1 to explicitly read entire file (when you know it's safe)
                - Returns metadata showing total lines and what was read
                
                Common patterns:
                - Preview file safely: grz_read_file("file.txt")  // First 1000 lines
                - Read entire small file: grz_read_file("config.json", limit=-1)
                - Check file size: grz_read_file("huge.log", limit=1)  // See total lines in metadata
                - Paginate large file: grz_read_file("data.csv", limit=100, offset=1000)
                
                Returns:
                - Content with line numbers (e.g., "1\tline content")
                - Metadata: [Read lines X-Y of Z total] (more lines available)
                - Images/PDFs: Base64 encoded
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<ReadFileParams>() {})
            .build()
    }

    @Bean
    fun writeFileTool(): ToolCallback {
        val function = object : BiFunction<WriteFileParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: WriteFileParams, context: ToolContext?): Map<String, Any> {
                val result = try {
                    val projectPath = context?.getContext()?.get("projectPath") as? String
                        ?: error("Project path is required in tool context - this is a bug!")
                    val file = resolveFile(request.file_path, projectPath)
                    file.parentFile?.mkdirs()
                    file.writeText(request.content)
                    logger.debug("Written ${request.content.length} chars to ${file.absolutePath}")
                    mapOf("success" to true, "path" to file.absolutePath, "bytes" to request.content.toByteArray().size)
                } catch (e: Exception) {
                    logger.error("Error writing file: ${request.file_path}", e)
                    mapOf("error" to "Error writing file: ${e.message}")
                }

                return mapOf(
                    "type" to "text",
                    "text" to result.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                )
            }
        }

        return FunctionToolCallback.builder("grz_write_file", function)
            .description("Write content to a file. Creates parent directories if needed. Overwrites existing files.")
            .inputType(object : ParameterizedTypeReference<WriteFileParams>() {})
            .build()
    }

    @Bean
    fun editFileTool(): ToolCallback {
        val function = object : BiFunction<EditFileParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: EditFileParams, context: ToolContext?): Map<String, Any> {
                val result = try {
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

                return mapOf(
                    "type" to "text",
                    "text" to result.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                )
            }
        }

        return FunctionToolCallback.builder("grz_edit_file", function)
            .description("""
                Performs exact string replacements in files. 
                
                Key features:
                - Requires exact match of old_string (preserves indentation, whitespace)
                - Fails if old_string is not unique (use replace_all or provide more context)
                - Use replace_all for renaming variables/strings throughout file
                - More efficient than rewriting entire file with grz_write_file
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<EditFileParams>() {})
            .build()
    }

    @Bean
    fun executeCommandTool(): ToolCallback {
        val function = object : BiFunction<ExecuteCommandParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: ExecuteCommandParams, context: ToolContext?): Map<String, Any> {
                val result = try {
                    val projectPath = context?.getContext()?.get("projectPath") as? String
                        ?: error("Project path is required in tool context - this is a bug!")
                    val workingDir = request.working_directory?.let { File(it) } ?: File(projectPath)

                    logger.debug("Executing: ${request.command} in ${workingDir.absolutePath}")

                    val process = ProcessBuilder()
                        .command("sh", "-c", request.command)
                        .directory(workingDir)
                        .redirectErrorStream(true)
                        .start()

                    val timeout = request.timeout_seconds ?: 30L
                    val completed = process.waitFor(timeout, TimeUnit.SECONDS)

                    if (!completed) {
                        process.destroyForcibly()
                        mapOf(
                            "error" to "Command timed out after $timeout seconds",
                            "command" to request.command
                        )
                    } else {
                        val output = process.inputStream.bufferedReader().readText()
                        val exitCode = process.exitValue()

                        val result = mapOf(
                            "exit_code" to exitCode,
                            "output" to output,
                            "success" to (exitCode == 0)
                        )

                        logger.debug("Command result: exitCode=$exitCode, outputLength=${output.length}, output preview: ${output.take(200)}")

                        result
                    }
                } catch (e: Exception) {
                    logger.error("Error executing command: ${request.command}", e)
                    mapOf("error" to "Error executing command: ${e.message}")
                }

                return mapOf(
                    "type" to "text",
                    "text" to result.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                )
            }
        }

        return FunctionToolCallback.builder("grz_execute_command", function)
            .description("""
                Execute shell command and return output. Use with caution - has full system access.
                
                Common use cases:
                - Search file contents: rg "pattern" --type kt -C 3
                - Find files by name: find . -name "*.kt" -type f
                - List directory: ls -la /path/to/dir
                - Background processes: command & (use jobs, fg, bg, kill for management)
                - Git operations: git status, git diff, git log
            """.trimIndent())
            .inputType(object : ParameterizedTypeReference<ExecuteCommandParams>() {})
            .build()
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

    private fun readTextFile(file: File, limit: Int?, offset: Int?): Map<String, Any> {
        val lines = file.readLines()
        val totalLines = lines.size
        val startLine = offset ?: 0
        
        // Safe default (1000 lines) with -1 for full file (AI-native pattern)
        val maxLines = when {
            limit == null -> 1000  // Safe default to prevent accidental huge file loads
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

data class ReadFileParams(
    val file_path: String,
    val offset: Int? = null,
    val limit: Int? = null
)

data class WriteFileParams(
    val file_path: String,
    val content: String
)

data class EditFileParams(
    val file_path: String,
    val old_string: String,
    val new_string: String,
    val replace_all: Boolean = false
)

data class ExecuteCommandParams(
    val command: String,
    val working_directory: String? = null,
    val timeout_seconds: Long? = null
)
