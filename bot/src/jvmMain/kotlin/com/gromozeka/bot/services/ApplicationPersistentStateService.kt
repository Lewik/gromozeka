package com.gromozeka.bot.services

import com.gromozeka.bot.state.ApplicationPersistentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.File

@Service
class ApplicationPersistentStateService(
    private val settingsService: SettingsService,
    private val sessionManager: SessionManager,
    private val sessionUiManager: SessionUiManager,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {
    
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    
    private val stateFile: File by lazy { 
        File(settingsService.gromozekaHome, "sessions.json")
    }
    
    suspend fun initialize() {
        val savedState = loadState()
        
        if (savedState.tabs.isNotEmpty()) {
            println("[ApplicationPersistentStateService] Restoring ${savedState.tabs.size} saved tabs")
            savedState.tabs.forEach { tab ->
                // Don't pass "default" as resumeSessionId - it's not a valid Claude Session ID
                val resumeSessionId = if (tab.claudeSessionId.value == "default") {
                    null
                } else {
                    tab.claudeSessionId
                }
                
                val sessionId = sessionManager.createSession(tab.projectPath, resumeSessionId)
                sessionUiManager.createViewModel(sessionId)
            }
        }
        
        // Subscribe to activeSessions changes
        sessionManager.activeSessions.onEach { activeSessions ->
            dumpAppState()
            
            // Subscribe to claudeSessionId changes for each active session
            activeSessions.values.forEach { session ->
                session.claudeSessionId.onEach { claudeSessionId ->
                    dumpAppState()
                }.launchIn(scope)
            }
        }.launchIn(scope)
    }
    
    private fun dumpAppState() {
        val currentSessions = sessionManager.activeSessions.value
        val tabs = currentSessions.values.map { session ->
            ApplicationPersistentState.Tab(
                claudeSessionId = session.claudeSessionId.value,
                projectPath = session.projectPath
            )
        }
        
        val state = ApplicationPersistentState(tabs = tabs)
        saveState(state)
    }
    
    private fun loadState(): ApplicationPersistentState {
        return try {
            val content = stateFile.readText()
            json.decodeFromString<ApplicationPersistentState>(content)
        } catch (e: Exception) {
            println("[ApplicationPersistentStateService] Failed to load application state: ${e.message}")
            ApplicationPersistentState()
        }
    }
    
    private fun saveState(state: ApplicationPersistentState) {
        try {
            val jsonContent = json.encodeToString(state)
            stateFile.writeText(jsonContent)
        } catch (e: Exception) {
            println("[ApplicationPersistentStateService] Failed to save application state: ${e.message}")
        }
    }
}