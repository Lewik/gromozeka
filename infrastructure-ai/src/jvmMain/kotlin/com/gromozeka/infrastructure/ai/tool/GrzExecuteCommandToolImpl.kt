package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit

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
    
    override fun execute(request: ExecuteCommandRequest, context: ToolContext?): Map<String, Any> {
        return try {
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
                
                logger.debug("Command result: exitCode=$exitCode, outputLength=${output.length}")
                
                result
            }
        } catch (e: Exception) {
            logger.error("Error executing command: ${request.command}", e)
            mapOf("error" to "Error executing command: ${e.message}")
        }
    }
}
