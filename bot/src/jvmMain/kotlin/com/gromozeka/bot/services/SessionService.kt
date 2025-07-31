package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ProjectGroup
import com.gromozeka.bot.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

/**
 * Simple service for session management.
 * Thin wrapper over Session class with Spring integration.
 */
@Service
class SessionService {
    
    /**
     * Load all sessions grouped by project
     */
    suspend fun loadAllSessions(): List<ProjectGroup> {
        return Session.loadAllSessions()
    }
    
    /**
     * Get session by ID and project path
     */
    fun getSession(sessionId: String, projectPath: String): Session? {
        return Session.fromSessionId(sessionId, projectPath)
    }
    
    /**
     * Load messages for a session
     */
    suspend fun getSessionMessages(sessionId: String, projectPath: String): List<ChatMessage> {
        return getSession(sessionId, projectPath)?.getMessages() ?: emptyList()
    }
    
    /**
     * Start watching a session for file changes
     */
    fun startWatchingSession(
        session: Session,
        scope: CoroutineScope,
        onUpdate: (List<ChatMessage>) -> Unit
    ) {
        scope.launch {
            session.startWatching(scope, onUpdate)
        }
    }
    
    /**
     * Stop watching a session
     */
    fun stopWatchingSession(session: Session) {
        session.stopWatching()
    }
}