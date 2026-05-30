package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import org.slf4j.LoggerFactory
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Service
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Infrastructure implementation of GrzExecuteCommandTool.
 * 
 * Delegates to domain specification and integrates with Spring AI.
 * 
 * @see com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool Full specification
 */
@Service
class GrzExecuteCommandToolImpl : GrzExecuteCommandTool {
    
    private val logger = LoggerFactory.getLogger(GrzExecuteCommandToolImpl::class.java)
    
    override fun execute(request: ExecuteCommandRequest, context: ToolExecutionContext?): Map<String, Any> {
        val outputFile = File.createTempFile("gromozeka-command-", ".log")
        var process: Process? = null
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: error("Project path is required in tool context - this is a bug!")
            
            val workingDir = request.working_directory?.let { File(it) } ?: File(projectPath)
            
            logger.debug("Executing: ${request.command} in ${workingDir.absolutePath}")
            
            process = ProcessBuilder()
                .command("sh", "-c", request.command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            
            val timeout = request.timeout_seconds ?: 30L
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
            val completed = waitForCompletion(process, context, deadlineNanos)
            
            if (!completed) {
                process.destroyForcibly()
                val outputArtifact = persistOutput(request.command, outputFile)
                mapOf(
                    "success" to false,
                    "error" to "Command timed out after $timeout seconds",
                    "command" to request.command,
                ) + outputArtifact.toResultFields()
            } else {
                val exitCode = process.exitValue()
                val outputArtifact = persistOutput(request.command, outputFile)
                
                val result = mapOf(
                    "exit_code" to exitCode,
                    "output" to outputArtifact.preview,
                    "success" to (exitCode == 0)
                ) + outputArtifact.toResultFields()
                
                logger.debug(
                    "Command result: exitCode=$exitCode, outputBytes=${outputArtifact.bytes}, " +
                        "outputTruncated=${outputArtifact.truncated}, outputFile=${outputArtifact.file.absolutePath}"
                )
                
                result
            }
        } catch (e: CancellationException) {
            process?.destroyForcibly()
            logger.warn("Command cancelled: ${request.command}")
            throw e
        } catch (e: Exception) {
            logger.error("Error executing command: ${request.command}", e)
            mapOf("error" to "Error executing command: ${e.message}")
        } finally {
            outputFile.delete()
        }
    }

    private fun persistOutput(command: String, tempOutputFile: File): CommandOutputArtifact {
        val outputDirectory = File(gromozekaHome(), "tool-outputs").apply { mkdirs() }
        val outputFile = File(
            outputDirectory,
            "command-${System.currentTimeMillis()}-${command.sha256Prefix()}-${UUID.randomUUID().toString().take(8)}.log"
        )
        tempOutputFile.copyTo(outputFile, overwrite = true)
        return outputFile.toCommandOutputArtifact()
    }

    private fun File.toCommandOutputArtifact(): CommandOutputArtifact {
        val bytes = length()
        val preview = readPreview(bytes)
        return CommandOutputArtifact(
            file = absoluteFile,
            bytes = bytes,
            preview = preview.text,
            truncated = preview.truncated,
        )
    }

    private fun File.readPreview(bytes: Long): CommandOutputPreview {
        if (bytes <= MaxOutputPreviewBytes.toLong()) {
            return CommandOutputPreview(readText(), truncated = false)
        }

        val halfLimit = MaxOutputPreviewBytes / 2
        val headBytes = ByteArray(halfLimit)
        val tailBytes = ByteArray(halfLimit)

        RandomAccessFile(this, "r").use { file ->
            file.readFully(headBytes)
            file.seek(bytes - halfLimit.toLong())
            file.readFully(tailBytes)
        }

        val omittedBytes = bytes - MaxOutputPreviewBytes.toLong()
        val head = String(headBytes, StandardCharsets.UTF_8)
        val tail = String(tailBytes, StandardCharsets.UTF_8)
        val marker = "\n\n...[output truncated: $omittedBytes bytes omitted; full output saved to ${absolutePath}]...\n\n"
        return CommandOutputPreview(head + marker + tail, truncated = true)
    }

    private fun CommandOutputArtifact.toResultFields(): Map<String, Any> =
        mapOf(
            "output_file" to file.absolutePath,
            "output_bytes" to bytes,
            "output_truncated" to truncated,
            "output_preview_chars" to preview.length,
        )

    private fun gromozekaHome(): File {
        val configuredHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
        return configuredHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gromozeka")
    }

    private fun waitForCompletion(
        process: Process,
        context: ToolExecutionContext?,
        deadlineNanos: Long,
    ): Boolean {
        while (true) {
            context?.cancellationSignal?.throwIfCancellationRequested()
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                return true
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false
            }
        }
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private data class CommandOutputArtifact(
        val file: File,
        val bytes: Long,
        val preview: String,
        val truncated: Boolean,
    )

    private data class CommandOutputPreview(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        private const val MaxOutputPreviewBytes = 64 * 1024
    }
}
