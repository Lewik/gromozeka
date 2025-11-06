package com.gromozeka.bot.config

import org.springframework.ai.claudecode.api.ClaudeCodeApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Description
import java.util.concurrent.TimeUnit
import java.util.function.Function

@Configuration
class ClaudeCodeConfig {

    @Bean
    fun claudeCodeApi(settingsService: com.gromozeka.bot.services.SettingsService): ClaudeCodeApi {
        val strictMode = settingsService.mode == com.gromozeka.bot.settings.AppMode.DEV
        return ClaudeCodeApi(null, null, strictMode)
    }

    // TODO: Temporary workaround for Jina MCP - mcp-remote has bug with Node.js 24.9.0 (Symbol(headers list) ByteString error)
    // TODO: Replace with proper MCP integration when mcp-remote is fixed or we implement direct SSE transport
    @Bean
    @Description("Extract clean, structured content from web pages as markdown via Jina Reader API. Free tier: 20 requests/minute (no API key). Use this to read and convert any URL to markdown format.")
    fun jina_read_url(): Function<JinaReadUrlRequest, JinaReadUrlResponse> {
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
    fun execute_command(): Function<ExecuteCommandRequest, ExecuteCommandResponse> {
        return Function { request ->
            try {
                val process = ProcessBuilder("bash", "-c", request.command)
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
