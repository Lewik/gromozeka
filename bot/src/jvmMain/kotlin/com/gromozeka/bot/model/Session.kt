package com.gromozeka.bot.model

import com.gromozeka.bot.services.ClaudeCodeSessionMapper
import com.gromozeka.bot.services.ClaudeCodeSessionParser
import com.gromozeka.bot.services.SessionMetadataExtractor
import com.gromozeka.bot.utils.ClaudeCodePaths
import com.gromozeka.bot.utils.isSessionFile
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsEvent
import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Represents a Claude Code session with encapsulated file operations,
 * lazy loading, and file watching capabilities.
 */
class Session private constructor(
    val sessionId: String,
    val projectPath: String,
    private val sessionFile: File
) {
    
    // Lazy-loaded metadata
    private var _metadata: ChatSession? = null
    private val metadataExtractor = SessionMetadataExtractor()
    
    // Lazy-loaded messages with caching
    private var _messages: List<ChatMessage>? = null
    private val claudeCodeSessionMapper = ClaudeCodeSessionMapper
    
    // File watching components
    private var watcher: KfsDirectoryWatcher? = null
    private var watchingJob: Job? = null
    private var watchingScope: CoroutineScope? = null
    
    // Callback for message updates
    private var onMessagesUpdated: ((List<ChatMessage>) -> Unit)? = null
    
    /**
     * Get session metadata (lazy-loaded)
     */
    suspend fun getMetadata(): ChatSession {
        return _metadata ?: loadMetadata().also { _metadata = it }
    }
    
    /**
     * Get all messages for this session (lazy-loaded)
     */
    suspend fun getMessages(): List<ChatMessage> {
        return _messages ?: loadMessages().also { _messages = it }
    }
    
    /**
     * Reload messages from file (clears cache)
     */
    suspend fun reloadMessages(): List<ChatMessage> {
        _messages = null
        return getMessages()
    }
    
    /**
     * Start file watching for this session
     */
    suspend fun startWatching(
        scope: CoroutineScope,
        onUpdate: (List<ChatMessage>) -> Unit
    ) {
        stopWatching()
        
        watchingScope = scope
        onMessagesUpdated = onUpdate
        
        val watcher = KfsDirectoryWatcher(scope)
        this.watcher = watcher
        
        // Watch the directory containing the session file
        watcher.add(sessionFile.parent)
        
        // Start watching for events
        watchingJob = scope.launch {
            watcher.onEventFlow.collect { event ->
                val eventFile = File(event.path)
                
                // Only handle events for our specific session file
                if (eventFile.toPath() == sessionFile.toPath() && eventFile.isSessionFile()) {
                    handleFileEvent(event.event, eventFile)
                }
            }
        }
        
        println("[Session] Started watching file: ${sessionFile.path}")
    }
    
    /**
     * Stop file watching and cleanup resources
     */
    fun stopWatching() {
        watchingJob?.cancel()
        watchingJob = null
        
        watcher?.let { w ->
            watchingScope?.launch {
                w.removeAll()
                w.close()
            }
        }
        watcher = null
        watchingScope = null
        onMessagesUpdated = null
        
        println("[Session] Stopped watching file: ${sessionFile.path}")
    }
    
    /**
     * Get the file path for this session
     */
    fun getFilePath(): Path = sessionFile.toPath()
    
    /**
     * Check if session file exists
     */
    fun exists(): Boolean = sessionFile.exists() && sessionFile.isSessionFile()
    
    private suspend fun loadMetadata(): ChatSession {
        return metadataExtractor.extractMetadata(sessionFile)
            ?: throw IllegalStateException("Cannot load metadata for session $sessionId")
    }
    
    private suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            sessionFile.readLines()
                .mapNotNull { line -> safeParseMessage(line) }
        } catch (e: Exception) {
            println("[Session] Error loading messages for $sessionId: ${e.message}")
            emptyList()
        }
    }
    
    private fun safeParseMessage(jsonLine: String): ChatMessage? {
        return try {
            val parser = ClaudeCodeSessionParser()
            val entries = parser.parseJsonLine(jsonLine)?.let { listOf(it) } ?: emptyList()
            
            entries.mapNotNull { entry ->
                (entry as? ClaudeCodeSessionEntryV1_0.Message)?.let { message ->
                    claudeCodeSessionMapper.run { message.toChatMessage() }
                }
            }.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun handleFileEvent(event: KfsEvent, file: File) {
        when (event) {
            KfsEvent.Create -> handleFileCreated(file)
            KfsEvent.Modify -> handleFileModified(file)
            KfsEvent.Delete -> handleFileDeleted(file)
        }
    }
    
    private suspend fun handleFileCreated(file: File) {
        delay(200) // Wait for file to be fully written
        
        if (!file.exists()) {
            println("[Session] Created file no longer exists: ${file.name}")
            return
        }
        
        println("[Session] Processing created session file: ${file.name}")
        reloadAndNotify()
    }
    
    private suspend fun handleFileModified(file: File) {
        delay(200) // Wait for file to be fully written
        
        if (!file.exists()) {
            println("[Session] Modified file no longer exists: ${file.name}")
            return
        }
        
        try {
            val newContent = Files.readAllLines(file.toPath())
            if (newContent.isEmpty()) {
                println("[Session] File ${file.name} is empty, skipping")
                return
            }
            
            val lastLine = newContent.last()
            if (!isValidJsonLine(lastLine)) {
                println("[Session] File ${file.name} has invalid last line, waiting for completion...")
                return
            }
            
            println("[Session] Processing modified session file: ${file.name}")
            reloadAndNotify()
            
        } catch (e: Exception) {
            println("[Session] Error processing modified file ${file.name}: ${e.message}")
        }
    }
    
    private suspend fun handleFileDeleted(file: File) {
        println("[Session] Session file deleted: ${file.name}")
        stopWatching()
        // Could notify UI that session was deleted
    }
    
    private suspend fun reloadAndNotify() {
        try {
            val updatedMessages = reloadMessages()
            onMessagesUpdated?.invoke(updatedMessages)
        } catch (e: Exception) {
            println("[Session] Error reloading messages: ${e.message}")
        }
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
    
    companion object {
        
        /**
         * Load all sessions grouped by project (parallel loading)
         */
        suspend fun loadAllSessions(): List<ProjectGroup> = coroutineScope {
            val metadataExtractor = SessionMetadataExtractor()
            
            // Process everything in parallel
            val metadataResults = ClaudeCodePaths.PROJECTS_DIR
                .listFiles { it.isDirectory }
                ?.flatMap { projectDir ->
                    listOf(async(Dispatchers.IO) {
                        projectDir.listFiles { it.isSessionFile() }
                            ?.mapNotNull { sessionFile ->
                                metadataExtractor.extractMetadata(sessionFile)
                            } ?: emptyList()
                    })
                }?.map { it.await() }?.flatten() ?: emptyList()
            
            // Group by project and return
            metadataResults
                .groupBy { it.projectPath }
                .map { (projectPath, sessions) ->
                    ProjectGroup(
                        projectPath = projectPath,
                        projectName = extractProjectName(projectPath),
                        sessions = sessions.sortedByDescending { it.lastTimestamp }
                    )
                }
                .sortedByDescending { group ->
                    group.lastActivity()
                }
        }
        
        /**
         * Create Session from sessionId and projectPath
         */
        fun fromSessionId(sessionId: String, projectPath: String): Session? {
            val sessionFile = findSessionFile(sessionId, projectPath)
            return sessionFile?.let { Session(sessionId, projectPath, it) }
        }
        
        /**
         * Create Session from session file
         */
        fun fromFile(sessionFile: File): Session? {
            if (!sessionFile.exists() || !sessionFile.isSessionFile()) return null
            
            val sessionId = sessionFile.name.removeSuffix(".jsonl")
            val projectPath = decodeProjectPath(sessionFile.parentFile.name)
            
            return Session(sessionId, projectPath, sessionFile)
        }
        
        private fun findSessionFile(sessionId: String, projectPath: String): File? {
            val encodedProjectPath = projectPath.replace("/", "-")
            val projectDir = File(ClaudeCodePaths.PROJECTS_DIR, encodedProjectPath)
            val sessionFile = File(projectDir, "$sessionId.jsonl")
            
            return if (sessionFile.exists() && sessionFile.isSessionFile()) {
                sessionFile
            } else {
                null
            }
        }
        
        private fun decodeProjectPath(encodedPath: String): String {
            return encodedPath.replace("-", "/")
        }
        
        private fun extractProjectName(projectPath: String): String {
            return when {
                projectPath.contains("/") -> projectPath.substringAfterLast("/")
                projectPath.isNotBlank() -> projectPath
                else -> "Unknown Project"
            }
        }
    }
}