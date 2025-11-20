package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.chat.model.ToolContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Description
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

@Configuration
class ToolsConfig {

    @Bean
    @Description("Execute bash command and return output. Use for running shell commands, listing files, checking system state.")
    fun execute_command(): BiFunction<ExecuteCommandRequest, ToolContext?, ExecuteCommandResponse> {
        return BiFunction { request, context ->
            try {
                val projectPath = context?.getContext()?.get("projectPath") as? String
                    ?: error("Project path is required in tool context - this is a bug!")
                val workingDir = java.io.File(projectPath)
                
                val process = ProcessBuilder("bash", "-c", request.command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(30, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly()
                    ExecuteCommandResponse(
                        exitCode = -1,
                        output = "Command timeout after 30 seconds"
                    )
                } else {
                    ExecuteCommandResponse(
                        exitCode = process.exitValue(),
                        output = output.take(10000) // Limit to 10KB
                    )
                }
            } catch (e: Exception) {
                ExecuteCommandResponse(
                    exitCode = -1,
                    output = "Error executing command: ${e.message}"
                )
            }
        }
    }
}

// Tool request/response data classes
data class ExecuteCommandRequest(
    val command: String,
    val requires_approval: Boolean? = false
)
data class ExecuteCommandResponse(val exitCode: Int, val output: String)
