package com.gromozeka.bot.services

import com.gromozeka.bot.model.StreamJsonLine
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
    )

    /**
     * Send a message to Claude
     */
    suspend fun sendMessage(message: String, sessionId: ClaudeSessionUuid)

    /**
     * Send a control message (e.g., interrupt)
     */
    suspend fun sendControlMessage(controlMessage: StreamJsonLine.ControlRequest)

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
 * Allows switching between different implementations:
 * - Direct CLI wrapper (ClaudeCodeStreamingWrapper)
 * - Python SDK wrapper (ClaudeCodePythonSdkWrapper)
 * - Node.js SDK wrapper (ClaudeCodeNodeSdkWrapper)
 * - Future wrappers (OpenAI, local LLMs, etc.)
 */
@Service
class WrapperFactory {

    enum class WrapperType {
        DIRECT_CLI,      // Direct Claude CLI invocation
        PYTHON_SDK,      // Python SDK proxy
        NODE_SDK,        // Node.js SDK proxy (native to Claude Code)
        // Future: OPENAI, LOCAL_LLM, etc.
    }

    /**
     * Creates a Claude wrapper based on the specified type.
     * Default is DIRECT_CLI for backwards compatibility.
     */
    fun createWrapper(
        settingsService: SettingsService,
        type: WrapperType,
    ): ClaudeWrapper {
        return when (type) {
            WrapperType.DIRECT_CLI -> {
                ClaudeCodeStreamingWrapper(settingsService)
            }

            WrapperType.PYTHON_SDK -> {
                ClaudeCodePythonSdkWrapper(settingsService)
            }

            WrapperType.NODE_SDK -> {
                ClaudeCodeNodeSdkWrapper(settingsService)
            }
        }
    }
}
