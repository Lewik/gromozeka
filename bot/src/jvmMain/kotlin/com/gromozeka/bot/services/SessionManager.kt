package com.gromozeka.bot.services

import com.gromozeka.bot.model.Session
import com.gromozeka.shared.domain.session.SessionUuid
import com.gromozeka.shared.domain.session.toSessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service for managing multiple sessions with reactive state management.
 * 
 * Replaces direct Session usage in ChatApplication with centralized session management.
 * Provides flow-based reactive state for UI consumption and handles session lifecycle.
 */
@Service
class SessionManager(
    private val sessionService: SessionService,
    private val scope: CoroutineScope,
) {

    private val sessionMutex = Mutex()

    // === Flow-based State Management ===
    private val _activeSessions = MutableStateFlow<Map<SessionUuid, Session>>(emptyMap())
    val activeSessions: StateFlow<Map<SessionUuid, Session>> = _activeSessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<SessionUuid?>(null)
    val currentSessionId: StateFlow<SessionUuid?> = _currentSessionId.asStateFlow()

    // Computed properties
    val currentSession: Flow<Session?> = combine(activeSessions, currentSessionId) { sessions, currentId ->
        currentId?.let { sessions[it] }
    }

    /**
     * Create new session and switch to it
     * @param projectPath Path to the project directory
     * @return SessionUuid of the created session
     */
    suspend fun createSession(projectPath: String): SessionUuid = sessionMutex.withLock {
        val sessionId = UUID.randomUUID().toString().toSessionUuid()
        val session = sessionService.createSession(projectPath)
        
        session.start(scope)
        
        val updatedSessions = _activeSessions.value + (sessionId to session)
        _activeSessions.value = updatedSessions
        _currentSessionId.value = sessionId
        
        println("[SessionManager] Created and switched to session: $sessionId")
        sessionId
    }

    /**
     * Switch to existing session
     * @param sessionId SessionUuid to switch to
     * @throws IllegalArgumentException if session doesn't exist
     */
    suspend fun switchToSession(sessionId: SessionUuid) = sessionMutex.withLock {
        require(_activeSessions.value.containsKey(sessionId)) {
            "Session $sessionId does not exist in active sessions"
        }
        
        _currentSessionId.value = sessionId
        println("[SessionManager] Switched to session: $sessionId")
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
            
            // If we stopped current session, clear current session ID
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
            }
            
            println("[SessionManager] Stopped session: $sessionId")
        }
    }

    /**
     * Stop all active sessions and clear state
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
        _currentSessionId.value = null
        
        println("[SessionManager] All sessions stopped")
    }

    /**
     * Get current active session (null if none)
     */
    fun getCurrentSession(): Session? {
        val currentId = _currentSessionId.value
        return currentId?.let { _activeSessions.value[it] }
    }

    /**
     * Check if there are any active sessions
     */
    fun hasActiveSessions(): Boolean = _activeSessions.value.isNotEmpty()

    /**
     * Get number of active sessions
     */
    fun getActiveSessionCount(): Int = _activeSessions.value.size
}