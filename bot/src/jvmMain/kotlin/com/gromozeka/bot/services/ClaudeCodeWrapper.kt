package com.gromozeka.bot.services

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit

@Service
class ClaudeCodeWrapper {

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * Execute a single prompt with Claude Code CLI
     */
    suspend fun executePrompt(
        prompt: String,
        options: ClaudeCodeOptions = ClaudeCodeOptions(),
    ): ClaudeCodeResult = withContext(Dispatchers.IO) {

        println("=== CLAUDE CODE WRAPPER TEST ===")
        
        try {
            // Use working pipe approach
            val command = buildPipeCommand(prompt, options)
            println("Executing: ${command.joinToString(" ")}")
            
            val process = ProcessBuilder(command)
                .directory(File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start()
            
            println("Process started, waiting for completion...")
            
            // Set timeout to prevent hanging
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            
            if (!finished) {
                println("Process timed out, destroying...")
                process.destroyForcibly()
                return@withContext ClaudeCodeResult.Error("Claude Code process timed out after 30 seconds")
            }
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()
            
            println("Exit code: $exitCode")
            println("Output length: ${output.length}")
            
            if (exitCode == 0) {
                // Parse stream-json lines
                val lines = output.split("\n").filter { it.trim().isNotEmpty() }
                println("Total JSON lines: ${lines.size}")
                
                // Find result line
                val resultLine = lines.find { it.contains("\"type\":\"result\"") }
                val sessionId = lines.find { it.contains("\"session_id\"") }
                    ?.let { Regex("\"session_id\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                
                val resultText = resultLine?.let { line ->
                    Regex("\"result\":\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                } ?: output
                
                ClaudeCodeResult.Success(
                    result = resultText,
                    sessionId = sessionId,
                    totalCostUsd = 0.0,
                    durationMs = 0,
                    durationApiMs = 0,
                    numTurns = 1
                )
            } else {
                ClaudeCodeResult.Error("Claude Code failed with exit code $exitCode. Output: $output")
            }

        } catch (e: Exception) {
            println("Exception: ${e.message}")
            e.printStackTrace()
            ClaudeCodeResult.Error("Exception: ${e.message}")
        }
    }

    /**
     * Resume a specific session by session ID
     */
    suspend fun resumeSession(
        sessionId: String,
        prompt: String,
        options: ClaudeCodeOptions = ClaudeCodeOptions(),
    ): ClaudeCodeResult {
        return executePrompt(prompt, options.copy(resumeSessionId = sessionId))
    }

    /**
     * Continue the most recent session
     */
    suspend fun continueSession(
        prompt: String,
        options: ClaudeCodeOptions = ClaudeCodeOptions(),
    ): ClaudeCodeResult {
        return executePrompt(prompt, options.copy(continueLastSession = true))
    }

    private fun buildPipeCommand(prompt: String, options: ClaudeCodeOptions): List<String> {
        // Use pipe approach: echo 'prompt' | claude [args]
        val claudeArgs = mutableListOf("--output-format", "stream-json", "--verbose")
        
        // Add max-turns (required to prevent hanging)
        val maxTurns = options.maxTurns ?: 1
        claudeArgs.addAll(listOf("--max-turns", maxTurns.toString()))

        // Session management
        if (options.resumeSessionId != null) {
            claudeArgs.addAll(listOf("--resume", options.resumeSessionId))
        }
        if (options.continueLastSession) {
            claudeArgs.add("--continue")
        }

        // Prompts and behavior
        if (options.systemPrompt != null) {
            claudeArgs.addAll(listOf("--system-prompt", options.systemPrompt))
        }
        if (options.appendSystemPrompt != null) {
            claudeArgs.addAll(listOf("--append-system-prompt", options.appendSystemPrompt))
        }

        // Tools and permissions
        if (options.allowedTools.isNotEmpty()) {
            claudeArgs.addAll(listOf("--allowedTools", options.allowedTools.joinToString(",")))
        }
        if (options.disallowedTools.isNotEmpty()) {
            claudeArgs.addAll(listOf("--disallowedTools", options.disallowedTools.joinToString(",")))
        }

        // Create bash command: echo 'prompt' | claude [args]
        val escapedPrompt = prompt.replace("'", "'\"'\"'") // Escape single quotes
        val claudeCommand = "claude ${claudeArgs.joinToString(" ")}"
        
        return listOf("bash", "-c", "echo '$escapedPrompt' | $claudeCommand")
    }
}

/**
 * Configuration options for Claude Code CLI
 */
data class ClaudeCodeOptions(
    val workingDirectory: File = File(System.getProperty("user.dir")),
    val timeoutSeconds: Long = 30,

    // Session management
    val resumeSessionId: String? = null,
    val continueLastSession: Boolean = false,

    // Prompts and behavior
    val systemPrompt: String? = null,
    val appendSystemPrompt: String? = null,
    val maxTurns: Int? = null,

    // Tools and permissions
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),

    // MCP configuration
    val mcpConfig: File? = null,
    val permissionPromptTool: String? = null,

    // Other options
    val verbose: Boolean = false,
)

/**
 * Response from Claude Code CLI in JSON format
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeCodeResponse(
    val type: String,
    val subtype: String? = null,
    val result: String? = null,
    val sessionId: String? = null,
    val totalCostUsd: Double? = null,
    val durationMs: Int? = null,
    val durationApiMs: Int? = null,
    val numTurns: Int? = null,
    val isError: Boolean? = null,
)

/**
 * Result of Claude Code execution
 */
sealed class ClaudeCodeResult {
    data class Success(
        val result: String,
        val sessionId: String?,
        val totalCostUsd: Double,
        val durationMs: Int,
        val durationApiMs: Int,
        val numTurns: Int,
    ) : ClaudeCodeResult()

    data class Error(
        val message: String,
    ) : ClaudeCodeResult()
}