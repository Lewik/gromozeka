package com.gromozeka.bot.services

import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.WrapperFactory.WrapperType
import com.gromozeka.shared.domain.session.SessionUuid
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.toSessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
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
     * @return SessionUuid of the created session
     */
    suspend fun createSession(projectPath: String, resumeSessionId: ClaudeSessionUuid? = null): SessionUuid = sessionMutex.withLock {
        val sessionId = UUID.randomUUID().toString().toSessionUuid()
        val session = createSessionInternal(projectPath)
        
        session.start(scope, resumeSessionId)
        
        val updatedSessions = _activeSessions.value + (sessionId to session)
        _activeSessions.value = updatedSessions
        
        println("[SessionManager] Created session: $sessionId")
        sessionId
    }

    /**
     * Create Session instance with current settings
     */
    private fun createSessionInternal(
        projectPath: String,
        claudeModel: String = settingsService.settings.claudeModel,
    ): Session {
        // Create wrapper using factory
        val wrapperType = WrapperType.DIRECT_CLI
        val claudeWrapper = wrapperFactory.createWrapper(settingsService, wrapperType)
        
        return Session(
            projectPath = projectPath,
            sessionJsonlService = sessionJsonlService,
            soundNotificationService = soundNotificationService,
            settingsService = settingsService,
            claudeWrapper = claudeWrapper,
            claudeModel = claudeModel,
            responseFormat = settingsService.settings.responseFormat,
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