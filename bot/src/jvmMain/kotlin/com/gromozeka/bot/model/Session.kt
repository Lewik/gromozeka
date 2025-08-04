package com.gromozeka.bot.model

import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.SessionJsonlService
import com.gromozeka.bot.services.StreamToChatMessageMapper
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.LocalDateTime

/**
 * Stream-based Session class that processes messages from Claude Code CLI stdout stream.
 * 
 * Key architectural differences from SessionJsonl:
 * - Real-time streaming instead of file monitoring
 * - Direct integration with ClaudeCodeStreamingWrapper.streamOutput()
 * - In-memory message accumulation without FileWatcher
 * - No history loading initially (will be added in separate task)
 * 
 * Thread Safety: Single mutex protects all mutable operations
 * Reactive Updates: StateFlow for UI consumption with immutable updates
 * Process Lifecycle: Robust start/stop with graceful shutdown and error recovery
 */
class Session(
    val projectPath: String,
    private val claudeWrapper: ClaudeCodeStreamingWrapper
) {
    
    // === StateFlow for external consumption ===
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()
    
    private val _metadata = MutableStateFlow<StreamSessionMetadata?>(null)
    val metadata: StateFlow<StreamSessionMetadata?> = _metadata.asStateFlow()
    
    // True streaming architecture - individual messages flow
    private val _messageStream = MutableSharedFlow<ChatMessage>(
        replay = 1000,  // Keep last 1000 messages for late subscribers
        extraBufferCapacity = 1000
    )
    val messageStream: SharedFlow<ChatMessage> = _messageStream.asSharedFlow()
    
    // Legacy compatibility - keep for metadata generation
    private val messageAccumulator = mutableListOf<ChatMessage>()
    
    // For UI compatibility - derives from stream 
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // === Events & State ===
    private val _events = MutableSharedFlow<StreamSessionEvent>()
    val events: SharedFlow<StreamSessionEvent> = _events.asSharedFlow()
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    private val _sessionState = MutableStateFlow(SessionState.INACTIVE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    // === Internal State ===
    private var sessionScope: CoroutineScope? = null
    private var streamCollectionJob: Job? = null
    private val sessionMutex = Mutex()
    
    // === Services ===
    private val sessionJsonlService = SessionJsonlService()
    private var historicalMessagesLoaded = false
    
    // Message accumulator moved up to StateFlow section
    
    // === Performance optimization jobs ===
    private var metadataUpdateJob: Job? = null
    private var assistantUpdateJob: Job? = null
    private var pendingAssistantUpdate = false
    
    /**
     * Start the session: launch Claude Code CLI process and begin stream collection
     * @param scope CoroutineScope for session lifecycle
     * @param resumeSessionId Optional session ID to load historical messages from
     */
    suspend fun start(scope: CoroutineScope, resumeSessionId: String? = null) = sessionMutex.withLock {
        require(_sessionState.value == SessionState.INACTIVE) { 
            "Session is already active or starting. Current state: ${_sessionState.value}" 
        }

        sessionScope = scope
        _sessionState.value = SessionState.STARTING
        
        try {
            println("[Session] Starting session for project: $projectPath")
            
            // === Phase 0: Load historical messages if resuming ===
            resumeSessionId?.let { oldSessionId ->
                if (!historicalMessagesLoaded) {
                    println("[Session] Loading historical messages from session: $oldSessionId")
                    loadHistoricalMessages(oldSessionId)
                    historicalMessagesLoaded = true
                }
            }
            
            // === Phase 1: Stream Collection Startup (FIRST!) ===
            println("[Session] *** ABOUT TO CALL startStreamCollection()")
            startStreamCollection(scope)
            println("[Session] *** FINISHED startStreamCollection() call")
            
            // === Phase 2: Claude Process Startup (AFTER subscription!) ===
            claudeWrapper.start(
                projectPath = projectPath,
                onSessionIdCaptured = { capturedSessionId ->
                    println("[Session] Captured session ID: $capturedSessionId")
                    
                    // Session ID может прийти в любой момент после старта
                    sessionScope?.launch {
                        updateSessionIdFromStream(capturedSessionId)
                    }
                }
            )
            
            // === Phase 3: Mark as Active ===
            _sessionState.value = SessionState.ACTIVE
            _isActive.value = true
            _events.tryEmit(StreamSessionEvent.Started)
            
            println("[Session] Session started successfully")
            
        } catch (e: Exception) {
            // === Error Recovery ===
            println("[Session] Failed to start session: ${e.message}")
            _sessionState.value = SessionState.ERROR
            
            // Cleanup любые частично инициализированные ресурсы
            cleanupOnError()
            
            throw SessionStartException("Failed to start session: ${e.message}", e)
        }
    }
    
    /**
     * Send a message through the Claude Code CLI process
     */
    suspend fun sendMessage(message: String) = sessionMutex.withLock {
        require(_sessionState.value == SessionState.ACTIVE) { 
            "Session is not active. Current state: ${_sessionState.value}" 
        }
        
        try {
            println("[Session] Sending message: ${message.take(100)}${if (message.length > 100) "..." else ""}")
            
            // Create and emit user message to UI immediately
            val userMessage = ChatMessage(
                messageType = ChatMessage.MessageType.USER,
                content = listOf(ChatMessage.ContentItem.Message(message)),
                timestamp = Clock.System.now(),
                uuid = java.util.UUID.randomUUID().toString(),
                llmSpecificMetadata = null
            )
            
            println("[Session] *** EMITTING USER MESSAGE TO STREAM")
            _messageStream.emit(userMessage)
            messageAccumulator.add(userMessage)
            _messages.value = messageAccumulator.toList()
            
            claudeWrapper.sendMessage(message)
        } catch (e: Exception) {
            println("[Session] Failed to send message: ${e.message}")
            _events.tryEmit(StreamSessionEvent.Error("Send failed: ${e.message}"))
            throw e
        }
    }
    
    /**
     * Stop the session: stop Claude Code CLI process and stream collection
     */
    suspend fun stop() = sessionMutex.withLock {
        val currentState = _sessionState.value
        
        if (currentState == SessionState.INACTIVE) {
            println("[Session] Session is already inactive")
            return@withLock
        }
        
        if (currentState == SessionState.STOPPING) {
            println("[Session] Session is already stopping")
            return@withLock
        }
        
        _sessionState.value = SessionState.STOPPING
        
        try {
            println("[Session] Stopping session...")
            
            // === Phase 1: Cancel optimization jobs ===
            metadataUpdateJob?.cancel()
            assistantUpdateJob?.cancel()
            metadataUpdateJob = null
            assistantUpdateJob = null
            
            // === Phase 2: Stop Stream Collection ===
            streamCollectionJob?.let { job ->
                println("[Session] Canceling stream collection...")
                job.cancel()
                
                // Wait для graceful cancellation
                try {
                    job.join() // Wait до завершения
                } catch (e: CancellationException) {
                    // Expected при cancel
                }
                
                streamCollectionJob = null
            }
            
            // === Phase 3: Stop Claude Process ===
            try {
                claudeWrapper.stop()
            } catch (e: Exception) {
                println("[Session] Error stopping Claude process: ${e.message}")
                // Continue - не блокируем shutdown на ошибке процесса
            }
            
            // === Phase 4: Reset State ===
            _isActive.value = false
            _sessionState.value = SessionState.INACTIVE
            sessionScope = null
            
            // Note: не очищаем messageAccumulator - сохраняем для history
            
            _events.tryEmit(StreamSessionEvent.Stopped)
            println("[Session] Session stopped successfully")
            
        } catch (e: Exception) {
            println("[Session] Error during stop: ${e.message}")
            
            // Force cleanup даже при ошибках
            _sessionState.value = SessionState.ERROR
            _isActive.value = false
            sessionScope = null
            
            // Don't throw - обеспечиваем что cleanup завершается
        }
    }
    
    // === Private Implementation ===
    
    private fun startStreamCollection(scope: CoroutineScope) {
        streamCollectionJob = scope.launch {
            try {
                println("[Session] Starting stream collection...")
                println("[Session] *** SUBSCRIBING TO claudeWrapper.streamOutput()")
                
                claudeWrapper.streamOutput()
                    .flowOn(Dispatchers.IO)           // Stream parsing на IO thread
                    .catch { exception ->             // Handle stream-level errors
                        println("[Session] *** STREAM ERROR: ${exception.message}")
                        handleStreamError(exception)
                    }
                    .collect { streamMessage ->       // Collect каждое StreamMessage
                        println("[Session] *** GOT STREAM MESSAGE FROM FLOW: ${streamMessage.type}")
                        // StateFlow updates are thread-safe, no dispatcher switching needed
                        handleStreamMessage(streamMessage)
                    }
                    
            } catch (e: Exception) {
                println("[Session] Critical stream error: ${e.message}")
                sessionMutex.withLock {
                    _sessionState.value = SessionState.ERROR
                    _events.tryEmit(StreamSessionEvent.Error("Stream collection failed: ${e.message}"))
                }
            }
        }
    }
    
    private suspend fun handleStreamMessage(streamMessage: StreamMessage) = sessionMutex.withLock {
        try {
            println("[Session] *** PROCESSING STREAM MESSAGE: ${streamMessage.type}")
            
            when (streamMessage) {
                is StreamMessage.SystemStreamMessage -> {
                    handleSystemMessage(streamMessage)
                }
                is StreamMessage.UserStreamMessage -> {
                    handleUserMessage(streamMessage)
                }
                is StreamMessage.AssistantStreamMessage -> {
                    handleAssistantMessage(streamMessage)
                }
                is StreamMessage.ResultStreamMessage -> {
                    handleResultMessage(streamMessage)
                }
            }
            
            // Update metadata after каждого сообщения
            scheduleMetadataUpdate()
            
        } catch (e: Exception) {
            println("[Session] Error processing stream message: ${e.message}")
            _events.tryEmit(StreamSessionEvent.Error("Message processing error: ${e.message}"))
        }
    }
    
    private suspend fun handleSystemMessage(message: StreamMessage.SystemStreamMessage) {
        // Handle session ID updates
        if (message.subtype == "init" && message.sessionId != null) {
            updateSessionIdFromStream(message.sessionId)
        }
        
        // Convert to ChatMessage
        val chatMessage = StreamToChatMessageMapper.mapToChatMessage(message)
        addMessageToAccumulator(chatMessage)
    }
    
    private suspend fun handleUserMessage(message: StreamMessage.UserStreamMessage) {
        // Handle session ID updates if provided
        updateSessionIdFromStream(message.sessionId)
        
        val chatMessage = StreamToChatMessageMapper.mapToChatMessage(message)
        addMessageToAccumulator(chatMessage)
    }
    
    private suspend fun handleAssistantMessage(message: StreamMessage.AssistantStreamMessage) {
        // Handle session ID updates
        updateSessionIdFromStream(message.sessionId)
        
        val chatMessage = StreamToChatMessageMapper.mapToChatMessage(message)
        
        // True streaming - emit each message update
        _messageStream.emit(chatMessage)
        
        // Legacy: Assistant messages могут быть streaming (partial updates)
        val existingIndex = findExistingAssistantMessage(chatMessage)
        
        if (existingIndex != -1) {
            // Update existing message (streaming case)
            messageAccumulator[existingIndex] = chatMessage
        } else {
            // New assistant message
            messageAccumulator.add(chatMessage)
        }
        
        // Update legacy StateFlow
        _messages.value = messageAccumulator.toList()
        
        // Batch UI updates для streaming messages (legacy)
        scheduleAssistantUpdate()
    }
    
    private suspend fun handleResultMessage(message: StreamMessage.ResultStreamMessage) {
        val chatMessage = StreamToChatMessageMapper.mapToChatMessage(message)
        addMessageToAccumulator(chatMessage)
        
        // Result messages обычно означают конец conversation turn
        _events.tryEmit(StreamSessionEvent.ConversationTurnCompleted)
    }
    
    private suspend fun updateSessionIdFromStream(streamSessionId: String) {
        val currentSessionId = _sessionId.value
        
        if (currentSessionId != streamSessionId) {
            println("[Session] Session ID updated from stream: $currentSessionId -> $streamSessionId")
            
            _sessionId.value = streamSessionId
            _events.tryEmit(StreamSessionEvent.SessionIdChangedOnStart(streamSessionId))
        }
    }
    
    private suspend fun addMessageToAccumulator(chatMessage: ChatMessage) {
        // True streaming - emit individual message
        println("[Session] *** EMITTING MESSAGE TO STREAM: ${chatMessage.messageType}")
        _messageStream.emit(chatMessage)
        
        // Legacy compatibility - also maintain list
        messageAccumulator.add(chatMessage)
        _messages.value = messageAccumulator.toList() // Immutable copy
    }
    
    private fun findExistingAssistantMessage(chatMessage: ChatMessage): Int {
        // Simple heuristic: find последнее assistant message с тем же UUID (если есть)
        // или последнее assistant message для streaming updates
        return messageAccumulator.indexOfLast { existing ->
            existing.messageType == ChatMessage.MessageType.ASSISTANT &&
            (existing.uuid == chatMessage.uuid || 
             // Fallback для streaming: последнее assistant message
             messageAccumulator.lastOrNull()?.messageType == ChatMessage.MessageType.ASSISTANT)
        }
    }
    
    private suspend fun scheduleMetadataUpdate() {
        metadataUpdateJob?.cancel()
        metadataUpdateJob = sessionScope?.launch {
            delay(500) // Обновляем metadata реже чем messages
            sessionMutex.withLock {
                updateMetadata()
            }
        }
    }
    
    private suspend fun scheduleAssistantUpdate() {
        if (!pendingAssistantUpdate) {
            pendingAssistantUpdate = true
            assistantUpdateJob?.cancel()
            assistantUpdateJob = sessionScope?.launch {
                delay(50) // Batch updates каждые 50ms для smooth UI
                sessionMutex.withLock {
                    _messages.value = messageAccumulator.toList()
                    pendingAssistantUpdate = false
                }
            }
        }
    }
    
    private suspend fun updateMetadata() {
        val currentSessionId = _sessionId.value ?: "unknown"
        val messageCount = messageAccumulator.size
        
        // Генерируем title из первого user message
        val title = generateSessionTitle(messageAccumulator)
        
        // Создаем новый metadata объект
        val newMetadata = StreamSessionMetadata(
            sessionId = currentSessionId,
            projectPath = projectPath,
            title = title,
            lastModified = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            messageCount = messageCount
        )
        
        _metadata.value = newMetadata
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
            else -> "New Session"
        }
    }
    
    private suspend fun handleStreamError(exception: Throwable) {
        println("[Session] Stream error: ${exception.message}")
        
        when (exception) {
            is java.io.IOException -> {
                // Network/Process error - attempt reconnection
                _events.tryEmit(StreamSessionEvent.Error("Connection lost, attempting recovery..."))
                attemptStreamReconnection()
            }
            is kotlinx.serialization.SerializationException -> {
                // JSON parsing error - log but continue
                println("[Session] JSON parsing error: ${exception.message}")
                _events.tryEmit(StreamSessionEvent.Warning("Message parsing error"))
            }
            else -> {
                // Unknown error - transition to error state
                sessionMutex.withLock {
                    _sessionState.value = SessionState.ERROR
                    _events.tryEmit(StreamSessionEvent.Error("Fatal stream error: ${exception.message}"))
                }
            }
        }
    }
    
    private suspend fun attemptStreamReconnection() {
        try {
            delay(1000) // Wait before retry
            
            sessionMutex.withLock {
                if (_sessionState.value == SessionState.ACTIVE) {
                    // Restart stream collection
                    streamCollectionJob?.cancel()
                    sessionScope?.let { scope ->
                        startStreamCollection(scope)
                    }
                    
                    _events.tryEmit(StreamSessionEvent.StreamReconnected)
                }
            }
            
        } catch (e: Exception) {
            println("[Session] Stream reconnection failed: ${e.message}")
            sessionMutex.withLock {
                _sessionState.value = SessionState.ERROR
                _events.tryEmit(StreamSessionEvent.Error("Reconnection failed: ${e.message}"))
            }
        }
    }
    
    private suspend fun cleanupOnError() {
        try {
            // Cancel jobs
            metadataUpdateJob?.cancel()
            assistantUpdateJob?.cancel()
            streamCollectionJob?.cancel()
            
            // Stop Claude process if started
            claudeWrapper.stop()
            
            // Reset state
            _isActive.value = false
            sessionScope = null
            
        } catch (cleanupException: Exception) {
            println("[Session] Error during cleanup: ${cleanupException.message}")
            // Don't throw - we're already in error recovery
        }
    }
    
    /**
     * Load historical messages from a previous session when resuming.
     * This is called before starting the Claude process to populate the UI with context.
     */
    private suspend fun loadHistoricalMessages(oldSessionId: String) {
        try {
            val historicalMessages = sessionJsonlService.loadMessagesFromSession(oldSessionId, projectPath)
            
            if (historicalMessages.isNotEmpty()) {
                println("[Session] Loaded ${historicalMessages.size} historical messages")
                
                // Add to accumulator and emit to streams
                messageAccumulator.addAll(historicalMessages)
                _messages.value = messageAccumulator.toList()
                
                // Emit each historical message to stream
                historicalMessages.forEach { message ->
                    _messageStream.emit(message)
                }
                
                // Update metadata based on historical messages
                scheduleMetadataUpdate()
                
                _events.tryEmit(StreamSessionEvent.HistoricalMessagesLoaded(historicalMessages.size))
            } else {
                println("[Session] No historical messages found for session: $oldSessionId")
            }
            
        } catch (e: Exception) {
            println("[Session] Failed to load historical messages: ${e.message}")
            _events.tryEmit(StreamSessionEvent.Warning("Failed to load history: ${e.message}"))
            // Don't throw - continue with session start
        }
    }
}

/**
 * Session states for lifecycle management
 */
enum class SessionState {
    INACTIVE,       // Начальное состояние
    STARTING,       // Процесс запуска (создание process, подключение stream)
    ACTIVE,         // Активная сессия (stream collecting, можно отправлять сообщения)
    STOPPING,       // Процесс остановки (graceful shutdown)
    ERROR          // Ошибка (требует cleanup и возможно restart)
}

/**
 * Session metadata for UI display  
 */
data class StreamSessionMetadata(
    val sessionId: String,
    val projectPath: String,
    val title: String,
    val lastModified: kotlinx.datetime.LocalDateTime,
    val messageCount: Int,
)

/**
 * Session events for reactive UI updates
 */
sealed class StreamSessionEvent {
    data class MessagesUpdated(val messageCount: Int) : StreamSessionEvent()
    data class Error(val message: String) : StreamSessionEvent()
    data class Warning(val message: String) : StreamSessionEvent()
    data object Started : StreamSessionEvent()
    data object Stopped : StreamSessionEvent()
    data object ConversationTurnCompleted : StreamSessionEvent()
    data object StreamReconnected : StreamSessionEvent()
    data object AutoRestarted : StreamSessionEvent()
    data class SessionIdChangedOnStart(val newSessionId: String) : StreamSessionEvent()
    data class HistoricalMessagesLoaded(val messageCount: Int) : StreamSessionEvent()
}

/**
 * Exception thrown when session fails to start
 */
class SessionStartException(message: String, cause: Throwable? = null) : Exception(message, cause)