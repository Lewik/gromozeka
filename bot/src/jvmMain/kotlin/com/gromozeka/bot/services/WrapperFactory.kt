package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeCodeStreamJsonLine
import com.gromozeka.bot.model.StreamJsonLinePacket
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

/**
 * Common interface for all Claude wrappers.
 * This allows different implementations while maintaining the same API.
 */
interface ClaudeWrapper {
    /**
     * Start the wrapper with optional configuration
     */
    suspend fun start(
        projectPath: String? = null,
        model: String? = null,
        responseFormat: ResponseFormat = ResponseFormat.JSON,
        resumeSessionId: ClaudeSessionUuid? = null,
        appendSystemPrompt: String = "",
        mcpConfigPath: String? = null,  // Added for MCP session support
        tabId: String? = null,  // Added for tab identification in MCP tools
    )

    /**
     * Send a message to Claude
     * @param streamMessage Complete Claude Code stream message (User, Assistant, etc.)
     */
    suspend fun sendMessage(streamMessage: ClaudeCodeStreamJsonLine)

    /**
     * Send a control message (e.g., interrupt)
     */
    suspend fun sendControlMessage(controlMessage: ClaudeCodeStreamJsonLine.ControlRequest)

    /**
     * Get the stream of output messages
     */
    fun streamOutput(): Flow<StreamJsonLinePacket>

    /**
     * Stop the wrapper and clean up resources
     */
    suspend fun stop()
}

/**
 * Service for creating Claude wrappers based on configuration.
 * Currently supports:
 * - Direct CLI wrapper (ClaudeCodeStreamingWrapper)
 * - Future wrappers (OpenAI, local LLMs, etc.)
 */
@Service
class WrapperFactory {

    enum class WrapperType {
        DIRECT_CLI,      // Direct Claude CLI invocation
        // Future: OPENAI, LOCAL_LLM, etc.
    }

    /**
     * Creates a Claude wrapper based on the specified type.
     * Currently only supports DIRECT_CLI.
     */
    fun createWrapper(
        settingsService: SettingsService,
        type: WrapperType,
    ): ClaudeWrapper {
        return when (type) {
            WrapperType.DIRECT_CLI -> {
                ClaudeCodeStreamingWrapper(settingsService)
            }
        }
    }
}
