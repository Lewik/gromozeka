package com.gromozeka.bot.services

import com.gromozeka.bot.model.ChatMessage
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import java.nio.file.Files

@Service
class SessionFileCoordinator(
    private val fileWatcherService: FileWatcherService,
    private val sessionCacheService: SessionCacheService,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @PostConstruct
    fun initialize() {
        scope.launch {
            println("Loading all session files into cache...")
            sessionCacheService.refreshAll()
            println("Session cache initialized")

            fileWatcherService.startWatching()

            fileWatcherService.fileEvents.collect { event ->
                handleFileChange(event)
            }
        }
    }

    @PreDestroy
    fun cleanup() {
        fileWatcherService.stopWatching()
        scope.cancel()
    }

    private suspend fun handleFileChange(event: FileChangeEvent) {
        try {
            when (event.type) {
                FileChangeType.CREATED -> handleFileCreated(event)
                FileChangeType.MODIFIED -> handleFileModified(event)
                FileChangeType.DELETED -> handleFileDeleted(event)
            }
        } catch (e: Exception) {
            println("Error handling file change ${event.file.name}: ${e.message}")
        }
    }

    private suspend fun handleFileCreated(event: FileChangeEvent) {
        delay(200)

        if (!event.file.exists()) {
            println("Created file no longer exists: ${event.file.name}")
            return
        }

        println("Processing new session file: ${event.file.name}")
        sessionCacheService.updateFile(event.file)
        
        // Update current chat history if this is the active session
        val sessionId = event.file.name.removeSuffix(".jsonl")
        updateCurrentChatHistory(sessionId)
    }

    private suspend fun handleFileModified(event: FileChangeEvent) {
        delay(200)

        if (!event.file.exists()) {
            println("Modified file no longer exists: ${event.file.name}")
            return
        }

        try {
            val newContent = Files.readAllLines(event.file.toPath())
            if (newContent.isEmpty()) {
                println("File ${event.file.name} is empty, skipping")
                return
            }

            val lastLine = newContent.last()
            if (!isValidJsonLine(lastLine)) {
                println("File ${event.file.name} has invalid last line, waiting for completion...")
                return
            }

            val comparison = sessionCacheService.compareFile(event.file, newContent)

            if (comparison.newLines.isNotEmpty() || comparison.modifiedLines.isNotEmpty()) {
                println("Detected ${comparison.newLines.size} new lines and ${comparison.modifiedLines.size} modified lines in ${event.file.name}")
                sessionCacheService.updateFile(event.file)
                
                // Update current chat history if this is the active session
                val sessionId = event.file.name.removeSuffix(".jsonl")
                updateCurrentChatHistory(sessionId)
            }

        } catch (e: Exception) {
            println("Error processing modified file ${event.file.name}: ${e.message}")
        }
    }

    private suspend fun handleFileDeleted(event: FileChangeEvent) {
        println("Session file deleted: ${event.file.name}")
        sessionCacheService.removeSession(event.file)
    }

    private fun isValidJsonLine(line: String): Boolean {
        if (line.isBlank()) return false

        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false
        }

        var braceCount = 0
        var inString = false
        var escaped = false

        for (char in trimmed) {
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> braceCount++
                !inString && char == '}' -> braceCount--
            }
        }

        return braceCount == 0
    }

    fun getSessionsFlow() = sessionCacheService.sessionsFlow

    fun getSessionMessages(sessionId: String) = sessionCacheService.getSessionMessages(sessionId)

    private var currentChatHistory: MutableList<ChatMessage>? = null
    private var currentSessionId: String? = null
    
    fun setChatHistory(sessionId: String, chatHistory: MutableList<ChatMessage>) {
        println("[SessionFileCoordinator] setChatHistory called for session: $sessionId")
        println("[SessionFileCoordinator] Chat history size: ${chatHistory.size}")
        currentSessionId = sessionId
        currentChatHistory = chatHistory
    }
    
    private fun updateCurrentChatHistory(sessionId: String) {
        println("[SessionFileCoordinator] updateCurrentChatHistory called for session: $sessionId")
        println("[SessionFileCoordinator] Current session ID: $currentSessionId")
        println("[SessionFileCoordinator] Chat history is null? ${currentChatHistory == null}")
        
        // Check if this is the file we're monitoring (by filename, not internal sessionId)
        if (sessionId == currentSessionId && currentChatHistory != null) {
            val newMessages = getSessionMessages(sessionId)
            println("[SessionFileCoordinator] Found ${newMessages.size} messages for session $sessionId")
            
            currentChatHistory?.let { history ->
                val oldSize = history.size
                history.clear()
                history.addAll(newMessages)
                println("[SessionFileCoordinator] Updated chat history: was $oldSize messages, now ${history.size} messages")
            }
        } else {
            println("[SessionFileCoordinator] Skipping update - session mismatch (expected: $currentSessionId, got: $sessionId) or null history")
        }
    }
}