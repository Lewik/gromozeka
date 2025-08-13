package com.gromozeka.bot.services

import com.gromozeka.bot.model.Session
import com.gromozeka.bot.viewmodel.TabViewModel
import com.gromozeka.shared.domain.session.SessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * UI управление для Session'ов - какая сессия показывается, ViewModel'ы для UI
 * Разделяет UI логику от бизнес-логики SessionManager'а
 * 
 * Использует explicit управление ViewModels вместо auto-sync для большей гибкости:
 * - SessionManager может создавать headless сессии без UI
 * - ViewModel остается при падении сессии для показа ошибки
 * - Явное управление lifecycle ViewModels
 */
@Service
class SessionUiManager(
    private val sessionManager: SessionManager,
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {

    private val uiMutex = Mutex()

    // === UI State Management ===
    private val _sessionViewModels = MutableStateFlow<Map<SessionUuid, TabViewModel>>(emptyMap())
    val sessionViewModels: StateFlow<Map<SessionUuid, TabViewModel>> = _sessionViewModels.asStateFlow()

    private val _currentSessionId = MutableStateFlow<SessionUuid?>(null)
    val currentSessionId: StateFlow<SessionUuid?> = _currentSessionId.asStateFlow()

    // No auto-sync - explicit ViewModel management for better control

    // === Computed UI Properties ===
    val currentSession: Flow<Session?> = combine(
        sessionManager.activeSessions, 
        currentSessionId
    ) { sessions, currentId ->
        currentId?.let { sessions[it] }
    }
    
    val currentSessionViewModel: Flow<TabViewModel?> = combine(
        sessionViewModels, 
        currentSessionId
    ) { viewModels, currentId ->
        currentId?.let { viewModels[it] }
    }

    /**
     * Set current session for UI display
     * @param sessionId SessionUuid to set as current, or null for SessionListScreen  
     * @throws IllegalArgumentException if session doesn't exist
     */
    suspend fun setCurrentSession(sessionId: SessionUuid?) = uiMutex.withLock {
        if (sessionId != null) {
            val activeSessions = sessionManager.activeSessions.value
            require(activeSessions.containsKey(sessionId)) {
                "Session $sessionId does not exist in active sessions"
            }
        }
        
        _currentSessionId.value = sessionId
        println("[SessionUiManager] Set current session: $sessionId")
    }

    /**
     * Create ViewModel for specific session
     * @param sessionId SessionUuid to create ViewModel for
     * @return Created TabViewModel 
     * @throws IllegalArgumentException if session doesn't exist
     */
    suspend fun createViewModel(sessionId: SessionUuid): TabViewModel = uiMutex.withLock {
        val activeSessions = sessionManager.activeSessions.value
        require(activeSessions.containsKey(sessionId)) {
            "Cannot create ViewModel for non-existent session: $sessionId"
        }
        
        val currentViewModels = _sessionViewModels.value
        require(!currentViewModels.containsKey(sessionId)) {
            "ViewModel for session $sessionId already exists"
        }
        
        val session = activeSessions[sessionId]!!
        val viewModel = TabViewModel(
            session = session,
            settingsFlow = settingsService.settingsFlow,
            scope = scope
        )
        
        _sessionViewModels.value = currentViewModels + (sessionId to viewModel)
        println("[SessionUiManager] Created ViewModel for session: $sessionId")
        
        return@withLock viewModel
    }

    /**
     * Remove ViewModel for specific session 
     * @param sessionId SessionUuid to remove ViewModel for
     */
    suspend fun removeViewModel(sessionId: SessionUuid) = uiMutex.withLock {
        val currentViewModels = _sessionViewModels.value
        if (!currentViewModels.containsKey(sessionId)) {
            println("[SessionUiManager] ViewModel for session $sessionId already removed")
            return@withLock
        }
        
        // Clear current session if it's being removed
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
            println("[SessionUiManager] Cleared current session - ViewModel removed")
        }
        
        _sessionViewModels.value = currentViewModels - sessionId
        println("[SessionUiManager] Removed ViewModel for session: $sessionId")
    }
}