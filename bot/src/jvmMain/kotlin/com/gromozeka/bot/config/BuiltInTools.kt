package com.gromozeka.bot.config

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

    @Bean
    fun readFileTool(): ToolCallback {
        val function = object : BiFunction<ReadFileParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: ReadFileParams, context: ToolContext?): Map<String, Any> {
                return try {
                    val file = File(request.file_path)

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
            .description("Read file contents. Supports text files, images (PNG, JPEG, GIF, WebP), and PDF documents.")
            .inputType(object : ParameterizedTypeReference<ReadFileParams>() {})
            .build()
    }

    @Bean
    fun writeFileTool(): ToolCallback {
        val function = object : BiFunction<WriteFileParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: WriteFileParams, context: ToolContext?): Map<String, Any> {
                val result = try {
                    val file = File(request.file_path)
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
                    val file = File(request.file_path)

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

    @Bean
    fun listDirectoryTool(): ToolCallback {
        val function = object : BiFunction<ListDirectoryParams, ToolContext?, Map<String, Any>> {
            override fun apply(request: ListDirectoryParams, context: ToolContext?): Map<String, Any> {
                val result = try {
                    val dir = File(request.path)

                    when {
                        !dir.exists() -> mapOf("error" to "Directory not found: ${request.path}")
                        !dir.isDirectory -> mapOf("error" to "Path is not a directory: ${request.path}")
                        else -> {
                            val files = dir.listFiles()?.map { file ->
                                mapOf(
                                    "name" to file.name,
                                    "type" to if (file.isDirectory) "directory" else "file",
                                    "size" to if (file.isFile) file.length() else null,
                                    "modified" to file.lastModified()
                                )
                            }?.sortedWith(compareBy({ it["type"] != "directory" }, { it["name"] as String }))
                                ?: emptyList()

                            logger.debug("Listed ${files.size} items in ${dir.absolutePath}")

                            mapOf(
                                "path" to dir.absolutePath,
                                "files" to files
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error listing directory: ${request.path}", e)
                    mapOf("error" to "Error listing directory: ${e.message}")
                }

                val text = when {
                    result.containsKey("error") -> result["error"].toString()
                    else -> {
                        val path = result["path"]
                        @Suppress("UNCHECKED_CAST")
                        val files = result["files"] as List<Map<String, Any>>
                        buildString {
                            appendLine("path: $path")
                            appendLine("files:")
                            files.forEach { file ->
                                appendLine("  - ${file["name"]} (${file["type"]}, size: ${file["size"]}, modified: ${file["modified"]})")
                            }
                        }
                    }
                }

                return mapOf(
                    "type" to "text",
                    "text" to text
                )
            }
        }

        return FunctionToolCallback.builder("grz_list_directory", function)
            .description("List files and directories in a given path. Returns file metadata including size and modification time.")
            .inputType(object : ParameterizedTypeReference<ListDirectoryParams>() {})
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
        val startLine = offset ?: 0
        val maxLines = limit ?: 2000

        val selectedLines = lines.drop(startLine).take(maxLines)
        val content = selectedLines.mapIndexed { index, line ->
            val lineNumber = startLine + index + 1
            val truncated = if (line.length > 2000) line.take(2000) + "..." else line
            "$lineNumber\t$truncated"
        }.joinToString("\n")

        return mapOf(
            "type" to "text",
            "text" to content
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

data class ListDirectoryParams(
    val path: String
)
