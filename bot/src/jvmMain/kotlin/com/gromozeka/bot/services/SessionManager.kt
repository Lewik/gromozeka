package com.gromozeka.bot.services

import com.gromozeka.bot.model.AgentDefinition
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.WrapperFactory.WrapperType
import com.gromozeka.bot.services.llm.claudecode.converter.ClaudeMessageConverter
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.SessionUuid
import com.gromozeka.shared.domain.session.toSessionUuid
import jdk.internal.agent.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service for managing multiple sessions - pure business logic.
 *
 * Manages Session lifecycle: create, start, stop. Does not know about UI or ViewModels.
 * UI logic is handled by SessionUiManager.
 */
@Service
class SessionManager(
    private val sessionJsonlService: SessionJsonlService,
    private val soundNotificationService: SoundNotificationService,
    private val settingsService: SettingsService,
    private val wrapperFactory: WrapperFactory,
    private val claudeMessageConverter: ClaudeMessageConverter,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {

    private val sessionMutex = Mutex()

    // === Business State Management ===
    private val _activeSessions = MutableStateFlow<Map<SessionUuid, Session>>(emptyMap())
    val activeSessions: StateFlow<Map<SessionUuid, Session>> = _activeSessions.asStateFlow()

    /**
     * Create and start new session
     * @param projectPath Path to the project directory
     * @param resumeSessionId Optional Claude session ID to resume from (for loading history)
     * @param tabId Optional tab ID for generating tab-specific MCP config
     * @return SessionUuid of the created session
     */
    suspend fun createSession(
        agentDefinition: AgentDefinition,
        projectPath: String,
        resumeSessionId: ClaudeSessionUuid? = null,
        tabId: String,
    ): Session = sessionMutex.withLock {

        val session = createSessionInternal(
            agentDefinition = agentDefinition,
            projectPath = projectPath,
            tabId = tabId,
            initialClaudeSessionId = resumeSessionId ?: ClaudeSessionUuid.DEFAULT
        )

        session.start(scope)

        val updatedSessions = _activeSessions.value + (session.id to session)
        _activeSessions.value = updatedSessions

        println("[SessionManager] Created session: ${session.id}")
        session
    }

    /**
     * Create Session instance with current settings
     */
    private fun createSessionInternal(
        agentDefinition: AgentDefinition,
        projectPath: String,
        claudeModel: String = settingsService.settings.claudeModel,
        tabId: String? = null,
        initialClaudeSessionId: ClaudeSessionUuid = ClaudeSessionUuid.DEFAULT,
    ): Session {
        // Create wrapper using factory
        val wrapperType = WrapperType.DIRECT_CLI
        val claudeWrapper = wrapperFactory.createWrapper(settingsService, wrapperType)

        val appendSystemPrompt = if (tabId != null) "This tab ID: $tabId" else ""

        return Session(
            id = UUID.randomUUID().toString().toSessionUuid(),
            projectPath = projectPath,
            sessionJsonlService = sessionJsonlService,
            soundNotificationService = soundNotificationService,
            claudeWrapper = claudeWrapper,
            claudeMessageConverter = claudeMessageConverter,
            // Added for MCP support
            mcpConfigPath = settingsService.mcpConfigFile.absolutePath,
            claudeModel = claudeModel,
            responseFormat = settingsService.settings.responseFormat,
            appendSystemPrompt = appendSystemPrompt,
            initialClaudeSessionId = initialClaudeSessionId,
            agentDefinition = agentDefinition,
        )
    }


    /**
     * Stop and remove specific session
     * @param sessionId SessionUuid to stop
     */
    suspend fun stopSession(sessionId: SessionUuid) = sessionMutex.withLock {
        val session = _activeSessions.value[sessionId]
        if (session != null) {
            session.stop()

            val updatedSessions = _activeSessions.value - sessionId
            _activeSessions.value = updatedSessions

            println("[SessionManager] Stopped session: $sessionId")
        }
    }

    /**
     * Stop all active sessions
     */
    suspend fun stopAllSessions() = sessionMutex.withLock {
        val sessions = _activeSessions.value
        println("[SessionManager] Stopping ${sessions.size} active sessions...")

        sessions.values.forEach { session ->
            try {
                session.stop()
            } catch (e: Exception) {
                println("[SessionManager] Error stopping session: ${e.message}")
            }
        }

        _activeSessions.value = emptyMap()

        println("[SessionManager] All sessions stopped")
    }

}