package com.gromozeka.bot.model

import com.gromozeka.bot.services.ClaudeLogEntryMapper
import com.gromozeka.bot.utils.SessionDeduplicator
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.utils.ClaudeCodePaths
import com.gromozeka.bot.utils.decodeProjectPath
import com.gromozeka.bot.utils.encodeProjectPath
import com.gromozeka.bot.utils.isSessionFile
import com.gromozeka.shared.domain.message.ChatMessage
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

@OptIn(kotlinx.coroutines.FlowPreview::class)
class Session(
    initialSessionId: String,
    val projectPath: String,
) {
    // SessionId as StateFlow for reactive updates
    private val _sessionId = MutableStateFlow(initialSessionId)
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()
    
    // Dynamic sessionFile computation (not lazy!)
    private val sessionFile: File get() {
        val encodedProjectPath = projectPath.encodeProjectPath()
        val projectDir = File(ClaudeCodePaths.PROJECTS_DIR, encodedProjectPath)
        return projectDir.resolve("${_sessionId.value}.jsonl")
    }

    // StateFlow for external consumption
    private val _metadata = MutableStateFlow<SessionMetadata?>(null)
    val metadata: StateFlow<SessionMetadata?> = _metadata.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // SharedFlow for events
    private val _events = MutableSharedFlow<SessionEvent>()
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Claude Code process management
    private var claudeStreamingWrapper: ClaudeCodeStreamingWrapper? = null

    // File watching
    private var watcher: KfsDirectoryWatcher? = null
    private var watchingJob: Job? = null

    // Session state
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var sessionScope: CoroutineScope? = null

    // Thread safety
    private val sessionMutex = Mutex()
    
    // Track last sent message to prevent duplicates during session ID transitions
    private var lastSentMessage: String? = null
    private var lastSentTimestamp: Long = 0

    suspend fun loadInitialData() = sessionMutex.withLock {
        _metadata.value = loadMetadata()
        _messages.value = loadMessages()
    }

    /**
     * Update session ID and restart FileWatcher on new file
     */
    suspend fun updateSessionId(newSessionId: String) = sessionMutex.withLock {
        if (_sessionId.value != newSessionId) {
            val oldId = _sessionId.value
            println("[Session] Updating session ID from $oldId to $newSessionId")
            
            // Stop current FileWatcher
            stopWatchingInternal()
            
            // Update sessionId (triggers sessionFile recomputation)
            _sessionId.value = newSessionId
            
            // Emit event for external consumers
            _events.tryEmit(SessionEvent.SessionIdChangedOnStart(newSessionId))
            
            // Restart FileWatcher with new file
            sessionScope?.launch {
                try {
                    startWatching(this)
                    println("[Session] Successfully switched to new session file: ${sessionFile.name}")
                } catch (e: Exception) {
                    println("[Session] Failed to start watching after session ID update: ${e.message}")
                    _events.tryEmit(SessionEvent.Error("Failed to restart file watching: ${e.message}"))
                }
            }
        }
    }

    /**
     * Start the session: launch Claude Code CLI process and begin file watching
     */
    suspend fun start(scope: CoroutineScope) = sessionMutex.withLock {
        require(!_isActive.value) { "Session is already active" }

        sessionScope = scope
        _isActive.value = true

        try {
            println("[Session] Starting session ${_sessionId.value}")

            // Create and start Claude Code CLI process
            claudeStreamingWrapper = ClaudeCodeStreamingWrapper()
            claudeStreamingWrapper!!.start(
                projectPath = projectPath,
                onSessionIdCaptured = { capturedSessionId ->
                    println("[Session] Captured session ID: $capturedSessionId")

                    // Only update if sessionId actually changed
                    if (capturedSessionId != _sessionId.value) {
                        sessionScope?.launch {
                            updateSessionId(capturedSessionId)
                        }
                    } else {
                        println("[Session] Session ID unchanged, ignoring duplicate event")
                    }
                }
            )

            // For existing sessions, start watching immediately
            if (sessionFile.exists()) {
                startWatching(scope)
            }

            _events.tryEmit(SessionEvent.Started)
            println("[Session] Session ${_sessionId.value} started successfully")

        } catch (e: Exception) {
            _isActive.value = false
            sessionScope = null
            
            // Cleanup: stop process if it was created
            try {
                claudeStreamingWrapper?.stop()
            } catch (cleanupException: Exception) {
                println("[Session] Error during cleanup: ${cleanupException.message}")
            }
            claudeStreamingWrapper = null
            
            println("[Session] Failed to start session ${_sessionId.value}: ${e.message}")
            throw e
        }
    }

    /**
     * Send a message through the Claude Code CLI process
     */
    suspend fun sendMessage(message: String) = sessionMutex.withLock {
        require(_isActive.value) { "Session is not active - call start() first" }
        require(claudeStreamingWrapper != null) { "Claude Code process is not running" }

        try {
            println("[Session] Sending message to Claude Code: ${message.take(100)}${if (message.length > 100) "..." else ""}")
            claudeStreamingWrapper!!.sendMessage(message)
        } catch (e: Exception) {
            println("[Session] Failed to send message in session ${_sessionId.value}: ${e.message}")
            throw e
        }
    }

    /**
     * Stop the session: stop Claude Code CLI process and file watching
     */
    suspend fun stop() = sessionMutex.withLock {
        if (!_isActive.value) {
            println("[Session] Session ${_sessionId.value} is already stopped")
            return@withLock
        }

        try {
            println("[Session] Stopping session ${_sessionId.value}")

            // Stop file watching first
            stopWatchingInternal()

            // Stop Claude Code CLI process
            claudeStreamingWrapper?.stop()
            claudeStreamingWrapper = null

            _isActive.value = false
            sessionScope = null

            _events.tryEmit(SessionEvent.Stopped)
            println("[Session] Session ${_sessionId.value} stopped successfully")

        } catch (e: Exception) {
            println("[Session] Error stopping session ${_sessionId.value}: ${e.message}")
            // Don't throw - we want to ensure cleanup happens
        }
    }


    suspend fun startWatching(scope: CoroutineScope) {
        stopWatching()

        // Wait for file to be created by Claude Code CLI (if needed)
        if (!sessionFile.exists()) {
            println("[Session] File doesn't exist yet, waiting for it to be created: ${sessionFile.path}")
            if (!waitForFileToExist()) {
                println("[Session] Failed to find session file, cannot start watching")
                return
            }
        }

        val directoryWatcher = KfsDirectoryWatcher(scope)
        watcher = directoryWatcher

        // Add watching directory (synchronously as per docs)
        directoryWatcher.add(sessionFile.parentFile.absolutePath)

        // Collect content change events (Create/Modify) with debounce and conflate
        val contentEvents = directoryWatcher.onEventFlow
            .filter { event -> File(event.path).name == "${_sessionId.value}.jsonl" }
            .filter { event -> event.event in setOf(KfsEvent.Create, KfsEvent.Modify) }
            .debounce(300) // Wait for 300ms of silence after last event
            .conflate()    // Take only the latest if multiple events are queued

        // Collect delete events immediately
        val deleteEvents = directoryWatcher.onEventFlow
            .filter { event -> File(event.path).name == "${_sessionId.value}.jsonl" }
            .filter { event -> event.event == KfsEvent.Delete }

        // Start watching job with proper error isolation
        watchingJob = scope.launch {
            supervisorScope {
                // Handle content changes
                launch {
                    contentEvents.collect { event ->
                        try {
                            println("[Session] Content changed for session file: ${_sessionId.value}.jsonl")
                            handleContentChange()
                        } catch (e: Exception) {
                            println("[Session] Error in content change handler: ${e.message}")
                            _events.tryEmit(SessionEvent.Error("Content handler error: ${e.message}"))
                        }
                    }
                }
                
                // Handle file deletion
                launch {
                    deleteEvents.collect { event ->
                        try {
                            println("[Session] Session file deleted: ${_sessionId.value}.jsonl")
                            handleFileDeleted()
                        } catch (e: Exception) {
                            println("[Session] Error in delete handler: ${e.message}")
                            _events.tryEmit(SessionEvent.Error("Delete handler error: ${e.message}"))
                        }
                    }
                }
            }
        }

        println("[Session] Started watching file: ${sessionFile.path}")
        
        // Immediately load messages to catch any changes that happened before FileWatcher started
        scope.launch {
            try {
                handleContentChange()
                println("[Session] Loaded initial messages after starting FileWatcher")
            } catch (e: Exception) {
                println("[Session] Error loading initial messages: ${e.message}")
            }
        }
    }

    suspend fun stopWatching() = sessionMutex.withLock {
        stopWatchingInternal()
    }

    private fun stopWatchingInternal() {
        watchingJob?.cancel()
        watchingJob = null

        watcher?.let { w ->
            sessionScope?.launch {
                w.removeAll()
                w.close()
            }
        }
        watcher = null

        println("[Session] Stopped watching file: ${sessionFile.path}")
    }

    private suspend fun handleContentChange() = sessionMutex.withLock {
        if (!sessionFile.exists()) {
            println("[Session] File no longer exists: ${sessionFile.name}")
            return@withLock
        }

        try {
            val newContent = Files.readAllLines(sessionFile.toPath())
            if (newContent.isEmpty()) {
                println("[Session] File ${sessionFile.name} is empty, skipping")
                return@withLock
            }

            val lastLine = newContent.last()
            if (!isValidJsonLine(lastLine)) {
                println("[Session] File ${sessionFile.name} has invalid last line, waiting for completion...")
                return@withLock
            }

            // Reload messages and update StateFlow
            val updatedMessages = loadMessages()
            _messages.value = updatedMessages
            _events.tryEmit(SessionEvent.MessagesUpdated(updatedMessages.size))

            println("[Session] Updated messages for session ${_sessionId.value}: ${updatedMessages.size} messages")

        } catch (e: Exception) {
            println("[Session] Error processing content change for ${sessionFile.name}: ${e.message}")
            _events.tryEmit(SessionEvent.Error("Content processing error: ${e.message}"))
        }
    }

    private suspend fun handleFileDeleted() = sessionMutex.withLock {
        println("[Session] Session file deleted: ${sessionFile.name}")
        _events.tryEmit(SessionEvent.FileDeleted)
        stopWatchingInternal()
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

    private suspend fun loadMetadata(): SessionMetadata = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext SessionMetadata(
                sessionId = _sessionId.value,
                projectPath = projectPath,
                title = "Empty Session",
                lastModified = LocalDateTime.now(),
                messageCount = 0
            )
        }

        try {
            val lines = sessionFile.readLines()
            if (lines.isEmpty()) {
                return@withContext SessionMetadata(
                    sessionId = _sessionId.value,
                    projectPath = projectPath,
                    title = "Empty Session",
                    lastModified = LocalDateTime.now(),
                    messageCount = 0
                )
            }

            // Parse first few messages to generate title
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = false
            }

            val messages = lines.take(3).mapIndexedNotNull { index, line ->
                try {
                    val claudeEntry = json.decodeFromString<ClaudeLogEntry>(line.trim())
                    ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
                } catch (e: SerializationException) {
                    println("Error parsing line ${index + 1} for metadata in session ${_sessionId.value}: ${e.message}")
                    null
                } catch (e: Exception) {
                    println("Unexpected error parsing line ${index + 1} for metadata in session ${_sessionId.value}: ${e.message}")
                    println("  Exception type: ${e.javaClass.simpleName}")
                    println("  Problematic line: ${line.take(200)}${if (line.length > 200) "..." else ""}")
                    null
                }
            }

            val title = generateSessionTitle(messages)
            val lastModified = LocalDateTime.ofEpochSecond(
                sessionFile.lastModified() / 1000,
                0,
                java.time.ZoneOffset.systemDefault().rules.getOffset(java.time.Instant.now())
            )

            return@withContext SessionMetadata(
                sessionId = _sessionId.value,
                projectPath = projectPath,
                title = title,
                lastModified = lastModified,
                messageCount = lines.size
            )

        } catch (e: Exception) {
            println("Error loading metadata for session ${_sessionId.value}: ${e.message}")
            return@withContext SessionMetadata(
                sessionId = _sessionId.value,
                projectPath = projectPath,
                title = "Error loading session",
                lastModified = LocalDateTime.now(),
                messageCount = 0
            )
        }
    }

    private suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext emptyList()
        }

        try {
            val json = Json {
                ignoreUnknownKeys = true
                coerceInputValues = false
            }

            val lines = sessionFile.readLines()
            
            // Parse all Claude log entries first
            val claudeEntries = lines.mapIndexedNotNull { index, line ->
                try {
                    json.decodeFromString<ClaudeLogEntry>(line.trim())
                } catch (e: SerializationException) {
                    println("Error parsing line ${index + 1} in session ${_sessionId.value}: ${e.message}")
                    println("  Problematic line: ${line.take(200)}${if (line.length > 200) "..." else ""}")
                    null
                } catch (e: Exception) {
                    println("Unexpected error parsing line ${index + 1} in session ${_sessionId.value}: ${e.message}")
                    println("  Exception type: ${e::class.simpleName}")
                    println("  Problematic line: ${line.take(200)}${if (line.length > 200) "..." else ""}")
                    null
                }
            }
            
            // Apply deduplication to fix Claude Code CLI bug with stream-json
            val deduplicatedEntries = SessionDeduplicator.deduplicate(claudeEntries)
            
            // Log deduplication stats if duplicates were found
            if (deduplicatedEntries.size < claudeEntries.size) {
                val stats = SessionDeduplicator.getDeduplicationStats(claudeEntries, deduplicatedEntries)
                println("[Session] Deduplicated ${stats.duplicatesRemoved} duplicate entries (${stats.userDuplicates} user, ${stats.assistantDuplicates} assistant)")
            }
            
            // Convert deduplicated entries to chat messages
            return@withContext deduplicatedEntries.mapNotNull { claudeEntry ->
                try {
                    ClaudeLogEntryMapper.mapToChatMessage(claudeEntry)
                } catch (e: Exception) {
                    println("Error mapping Claude entry to chat message: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            println("Error loading messages for session ${_sessionId.value}: ${e.message}")
            println("  Session file: ${sessionFile.absolutePath}")
            println("  File exists: ${sessionFile.exists()}")
            println("  File size: ${sessionFile.length()} bytes")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun generateSessionTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.messageType == ChatMessage.MessageType.USER }

        return when {
            firstUserMessage != null -> {
                val contentText = firstUserMessage.content
                    .filterIsInstance<ChatMessage.ContentItem.Message>()
                    .joinToString(" ") { it.text }
                val content = contentText.take(50)
                if (contentText.length > 50) "$content..." else content
            }

            messages.isNotEmpty() -> {
                val contentText = messages.first().content
                    .filterIsInstance<ChatMessage.ContentItem.Message>()
                    .joinToString(" ") { it.text }
                val content = contentText.take(50)
                if (contentText.length > 50) "$content..." else content
            }

            else -> "Empty Session"
        }
    }

    /**
     * Wait for session file to be created by Claude Code CLI
     */
    suspend fun waitForFileToExist(maxWaitMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()

        while (!sessionFile.exists()) {
            if (System.currentTimeMillis() - startTime > maxWaitMs) {
                println("[Session] Timeout waiting for file: ${sessionFile.path}")
                return false
            }
            delay(100) // Check every 100ms
        }

        println("[Session] File appeared: ${sessionFile.path}")
        return true
    }

    companion object {
        suspend fun loadAllSessions(): List<Session> = withContext(Dispatchers.IO) {
            val projectsDir = ClaudeCodePaths.PROJECTS_DIR
            if (!projectsDir.exists()) {
                return@withContext emptyList()
            }

            // Find all session files across all projects
            val sessionFiles = projectsDir.walkTopDown()
                .filter { it.isFile && it.extension == "jsonl" && it.isSessionFile() }
                .toList()

            // Load sessions in parallel
            sessionFiles.map { file ->
                async {
                    try {
                        val sessionId = file.nameWithoutExtension
                        val projectPath = file.parentFile?.decodeProjectPath()
                            ?: throw IllegalArgumentException("Cannot determine project path for file: ${file.path}")
                        val session = Session(sessionId, projectPath)
                        session.loadInitialData() // Load metadata and messages
                        session
                    } catch (e: Exception) {
                        println("Error loading session from file ${file.path}: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

data class SessionMetadata(
    val sessionId: String,
    val projectPath: String,
    val title: String,
    val lastModified: LocalDateTime,
    val messageCount: Int,
)

sealed class SessionEvent {
    data class MessagesUpdated(val messageCount: Int) : SessionEvent()
    data class Error(val message: String) : SessionEvent()
    data object FileDeleted : SessionEvent()
    data object Started : SessionEvent()
    data object Stopped : SessionEvent()
    data class SessionIdChangedOnStart(val newSessionId: String) : SessionEvent()
}