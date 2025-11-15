package com.gromozeka.bot.config

import org.springframework.ai.chat.model.ToolContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Description
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import java.util.function.Function

@Configuration
class ToolsConfig {


    // TODO: Disabled - replaced by MCP Jina SSE server (read_url tool)
    // TODO: Add settings UI to enable/disable built-in tools vs MCP tools
    // @Bean
    // @Description("Extract clean, structured content from web pages as markdown via Jina Reader API. Free tier: 20 requests/minute (no API key). Use this to read and convert any URL to markdown format.")
    fun jina_read_url_DISABLED(): Function<JinaReadUrlRequest, JinaReadUrlResponse> {
        return Function { request ->
            try {
                val process = ProcessBuilder(
                    "curl", "-s", "-L",
                    "--max-time", "30",
                    "https://r.jina.ai/${request.url}"
                )
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(35, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly()
                    JinaReadUrlResponse(
                        success = false,
                        content = "",
                        error = "Request timeout after 30 seconds"
                    )
                } else if (process.exitValue() != 0) {
                    JinaReadUrlResponse(
                        success = false,
                        content = "",
                        error = "HTTP request failed: $output"
                    )
                } else {
                    JinaReadUrlResponse(
                        success = true,
                        content = output,
                        error = null
                    )
                }
            } catch (e: Exception) {
                JinaReadUrlResponse(
                    success = false,
                    content = "",
                    error = "Error reading URL: ${e.message}"
                )
            }
        }
    }

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

data class JinaReadUrlRequest(val url: String)
data class JinaReadUrlResponse(
    val success: Boolean,
    val content: String,
    val error: String?
)
