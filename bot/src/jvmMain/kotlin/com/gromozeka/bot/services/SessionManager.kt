package com.gromozeka.bot.services

import com.gromozeka.bot.model.AgentDefinition
import com.gromozeka.bot.model.SessionSpringAI
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.SessionUuid
import com.gromozeka.shared.domain.session.toSessionUuid
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.ai.chat.client.ChatClient
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
    private val soundNotificationService: SoundNotificationService,
    private val chatClient: ChatClient,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private val sessionMutex = Mutex()

    // === Business State Management ===
    private val _activeSessions = MutableStateFlow<Map<SessionUuid, SessionSpringAI>>(emptyMap())
    val activeSessions: StateFlow<Map<SessionUuid, SessionSpringAI>> = _activeSessions.asStateFlow()

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
        initiator: ConversationInitiator = ConversationInitiator.User,
    ): SessionSpringAI = sessionMutex.withLock {

        val session = createSessionInternal(
            agentDefinition = agentDefinition,
            projectPath = projectPath,
            tabId = tabId,
            initiator = initiator,
        )

        session.start(scope)

        val updatedSessions = _activeSessions.value + (session.id to session)
        _activeSessions.value = updatedSessions

        log.info("Created session: ${session.id}")
        session
    }

    /**
     * Create Session instance with Spring AI
     */
    private fun createSessionInternal(
        agentDefinition: AgentDefinition,
        projectPath: String,
        tabId: String? = null,
        initiator: ConversationInitiator = ConversationInitiator.User,
    ): SessionSpringAI {
        return SessionSpringAI(
            id = UUID.randomUUID().toString().toSessionUuid(),
            projectPath = projectPath,
            chatClient = chatClient,
            soundNotificationService = soundNotificationService,
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

            log.info("Stopped session: $sessionId")
        }
    }

    /**
     * Stop all active sessions
     */
    suspend fun stopAllSessions() = sessionMutex.withLock {
        val sessions = _activeSessions.value
        log.info("Stopping ${sessions.size} active sessions...")

        sessions.values.forEach { session ->
            try {
                session.stop()
            } catch (e: Exception) {
                log.warn(e, "Error stopping session: ${e.message}")
            }
        }

        _activeSessions.value = emptyMap()

        log.info("All sessions stopped")
    }

}