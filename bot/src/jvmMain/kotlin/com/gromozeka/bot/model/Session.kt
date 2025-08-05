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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stream-based Session class that processes messages from Claude Code CLI stdout stream.
 *
 * Key architectural differences from SessionJsonl:
 * - Real-time streaming instead of file monitoring
 * - Direct integration with ClaudeCodeStreamingWrapper.streamOutput()
 * - In-memory message accumulation with real-time streaming
 * - No history loading initially (will be added in separate task)
 *
 * Thread Safety: Single mutex protects all mutable operations
 * Reactive Updates: StateFlow for UI consumption with immutable updates
 * Process Lifecycle: Robust start/stop with graceful shutdown and error recovery
 */
class Session(
    val projectPath: String,
    private val claudeWrapper: ClaudeCodeStreamingWrapper,
) {

    // === StateFlow for external consumption ===
    private val _sessionId = MutableStateFlow("default")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    // True streaming architecture - individual messages flow
    private val _messageOutputStream = MutableSharedFlow<ChatMessage>(
        replay = 1000,  // Keep last 1000 messages for late subscribers
        extraBufferCapacity = 1000
    )
    val messageOutputStream: SharedFlow<ChatMessage> = _messageOutputStream.asSharedFlow()

    // === Events & State ===
    private val _events = MutableSharedFlow<StreamSessionEvent>()
    val events: SharedFlow<StreamSessionEvent> = _events.asSharedFlow()

    private val _sessionState = MutableStateFlow(SessionState.INACTIVE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Waiting for response indicator
    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()

    // === Internal State ===
    private var sessionScope: CoroutineScope? = null
    private var streamCollectionJob: Job? = null
    private val sessionMutex = Mutex()
    private var sessionInitialized = false

    // === Message Buffering for Session ID Race Condition ===
    private var firstMessageSent = false
    private val _messageInputBuffer = MutableSharedFlow<String>(extraBufferCapacity = 1000)

    // === Services ===
    private val sessionJsonlService = SessionJsonlService()
    private var historicalMessagesLoaded = false

    // Message accumulator moved up to StateFlow section

    // === Performance optimization jobs ===
    private var metadataUpdateJob: Job? = null
    private var assistantUpdateJob: Job? = null

    /**
     * Start the session: launch Claude Code CLI process and begin stream collection
     * @param scope CoroutineScope for session lifecycle
     * @param resumeSessionId Optional session ID to load historical messages from
     */
    suspend fun start(scope: CoroutineScope, resumeSessionId: String? = null) = sessionMutex.withLock {
        println("[Session] Starting session for project: $projectPath")

        require(_sessionState.value == SessionState.INACTIVE) {
            "Session is already active or starting. Current state: ${_sessionState.value}"
        }

        sessionScope = scope
        _sessionState.value = SessionState.STARTING

        try {
            if (resumeSessionId != null && !historicalMessagesLoaded) {
                println("[Session] Loading historical messages from session: $resumeSessionId")
                loadHistoricalMessages(resumeSessionId)
                historicalMessagesLoaded = true
            }
            scope.launchOutputStreamCollection()

            claudeWrapper.start(projectPath = projectPath)

//            // === Phase 3: Start message buffer processing ===
//            messageInputBufferJob = scope.launch {
//                messageInputBuffer.collect { message ->
//                    if (sessionInitialized) {
//                        try {
//                            println("[Session] Processing buffered message: ${message.take(50)}...")
//                            claudeWrapper.sendMessage(message, _sessionId.value)
//                        } catch (e: Exception) {
//                            println("[Session] Failed to send buffered message: ${e.message}")
//                            // Re-emit to buffer for retry
//                            _messageInputBuffer.emit(message)
//                        }
//                    }
//                }
//            }

            // === Phase 4: Mark as Active ===
            _sessionState.value = SessionState.ACTIVE
            _events.emit(StreamSessionEvent.Started)

            println("[Session] Session started successfully")

        } catch (e: Exception) {
            // === Error Recovery ===
            println("[Session] Failed to start session: ${e.message}")
            _sessionState.value = SessionState.ERROR
            cleanup()
            throw SessionStartException("Failed to start session: ${e.message}", e)
        }
    }

    /**
     * Send a message through the Claude Code CLI process with buffering for session ID management
     */
    suspend fun sendMessage(message: String) = sessionMutex.withLock {
        require(_sessionState.value == SessionState.ACTIVE) {
            "Session is not active. Current state: ${_sessionState.value}"
        }

        try {
            println("[Session] Sending message: ${message.take(100)}${if (message.length > 100) "..." else ""}")

            println("[Session] *** EMITTING USER MESSAGE TO STREAM")
            _messageOutputStream.emit(
                ChatMessage(
                    messageType = ChatMessage.MessageType.USER,
                    content = listOf(ChatMessage.ContentItem.Message(message)),
                    timestamp = Clock.System.now(),
                    uuid = java.util.UUID.randomUUID().toString(),
                    llmSpecificMetadata = null
                )
            )

            // Set waiting for response flag
            _isWaitingForResponse.value = true

            if (firstMessageSent) {
                println("[Session] Buffering message")
                _messageInputBuffer.emit(message)
            } else {
                println("[Session] Sending first message directly")
                claudeWrapper.sendMessage(message, _sessionId.value)
                firstMessageSent = true
            }

        } catch (e: Exception) {
            println("[Session] Failed to send message: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Send failed: ${e.message}"))
            throw e
        }
    }

    /**
     * Send interrupt signal to Claude Code CLI
     */
    suspend fun sendInterrupt(): Boolean = sessionMutex.withLock {
        require(_sessionState.value == SessionState.ACTIVE) {
            "Cannot interrupt inactive session. Current state: ${_sessionState.value}"
        }
        
        return try {
            println("[Session] Sending interrupt request...")
            
            // Generate unique request ID
            val requestId = "req_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(10000)}"
            
            val controlRequest = StreamMessage.ControlRequestMessage(
                requestId = requestId,
                request = ControlRequest(subtype = "interrupt")
            )
            
            claudeWrapper.sendControlMessage(controlRequest)
            
            _events.emit(StreamSessionEvent.InterruptSent)
            println("[Session] Interrupt sent successfully")
            
            true
        } catch (e: Exception) {
            println("[Session] Interrupt failed: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Interrupt failed: ${e.message}"))
            false
        }
    }

    // processBufferedMessages is now handled automatically by messageBufferJob

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
            _sessionState.value = SessionState.INACTIVE
            _isWaitingForResponse.value = false
            sessionScope = null

            // Note: не очищаем messageAccumulator - сохраняем для history

            _events.emit(StreamSessionEvent.Stopped)
            println("[Session] Session stopped successfully")

        } catch (e: Exception) {
            println("[Session] Error during stop: ${e.message}")

            // Force cleanup даже при ошибках
            _sessionState.value = SessionState.ERROR
            _isWaitingForResponse.value = false
            sessionScope = null

            // Don't throw - обеспечиваем что cleanup завершается
        }
    }

    // === Private Implementation ===

    private fun CoroutineScope.launchOutputStreamCollection() {
        println("[Session] Starting output stream collection...")
        streamCollectionJob = claudeWrapper.streamOutput()
            .flowOn(Dispatchers.IO)
            .catch { exception ->
                println("[Session] *** STREAM ERROR: ${exception.message}")
                this@Session.handleStreamError(exception)
            }
            .onEach { streamMessage ->
                println("[Session] *** GOT STREAM MESSAGE FROM FLOW: ${streamMessage.type}")
                this@Session.handleOutputStreamMessage(streamMessage)
            }
            .catch { e ->
                println("[Session] Critical stream error: ${e.message}")
                sessionMutex.withLock {
                    _sessionState.value = SessionState.ERROR
                    _events.emit(StreamSessionEvent.Error("Stream collection failed: ${e.message}"))
                }
            }
            .launchIn(this)
    }

    private fun CoroutineScope.launchInoutStreamCollection() {
        println("[Session] Starting input stream collection...")
        _messageInputBuffer
            .onEach { message ->
                repeat(3) { attempt ->
                    try {
                        println("[Session] Processing buffered message (attempt ${attempt + 1}): ${message.take(50)}...")
                        claudeWrapper.sendMessage(message, _sessionId.value)
                        return@onEach
                    } catch (e: Exception) {
                        println("[Session] Failed to send buffered message (attempt ${attempt + 1}): ${e.message}")
                        if (attempt < 2) {
                            delay(500.milliseconds)
                        }
                    }
                }
                println("[Session] Giving up on message after 3 attempts: ${message.take(50)}...")
            }
            .launchIn(this)
    }

    private suspend fun handleOutputStreamMessage(streamMessage: StreamMessage) = sessionMutex.withLock {
        try {
            println("[Session] *** PROCESSING STREAM MESSAGE: ${streamMessage.type}")

            // Reset waiting flag only when we receive the final result
            if (streamMessage is StreamMessage.ResultStreamMessage) {
                _isWaitingForResponse.value = false
            }

            when (streamMessage) {
                is StreamMessage.SystemStreamMessage -> handleSystemMessage(streamMessage)
                is StreamMessage.UserStreamMessage -> Unit
                is StreamMessage.AssistantStreamMessage -> Unit
                is StreamMessage.ResultStreamMessage -> Unit
                is StreamMessage.ControlResponseMessage -> handleControlResponse(streamMessage)
                is StreamMessage.ControlRequestMessage -> Unit // We don't expect to receive control requests
            }

            val chatMessage = StreamToChatMessageMapper.mapToChatMessage(streamMessage)
            _messageOutputStream.emit(chatMessage)

        } catch (e: Exception) {
            println("[Session] Error processing stream message: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Message processing error: ${e.message}"))
        }
    }

    private suspend fun handleSystemMessage(message: StreamMessage.SystemStreamMessage) {
        // Handle session ID updates and initialization
        if (message.subtype == "init") {
            require(!sessionInitialized)
            val currentSessionId = _sessionId.value
            val newSessionId = message.sessionId!!
            if (currentSessionId != newSessionId) {
                println("[Session] Session ID updated from stream: $currentSessionId -> $newSessionId")

                _sessionId.value = newSessionId
                _events.emit(StreamSessionEvent.SessionIdChangedOnStart(newSessionId))
            }

            sessionInitialized = true
            println("[Session] Session initialized with ID: ${message.sessionId}")
            println("[Session] Buffered messages will now be processed automatically")

            sessionScope!!.launchInoutStreamCollection()

        }
    }

    private suspend fun handleControlResponse(response: StreamMessage.ControlResponseMessage) {
        println("[Session] Control response received: request_id=${response.response.requestId}, subtype=${response.response.subtype}")
        
        when (response.response.subtype) {
            "success" -> {
                // Reset waiting flag for interrupt acknowledgment
                _isWaitingForResponse.value = false
                _events.emit(StreamSessionEvent.InterruptAcknowledged)
                println("[Session] Interrupt acknowledged successfully")
            }
            "error" -> {
                _events.emit(StreamSessionEvent.Error("Interrupt error: ${response.response.error}"))
                println("[Session] Interrupt error: ${response.response.error}")
            }
            else -> {
                println("[Session] Unknown control response subtype: ${response.response.subtype}")
            }
        }
    }


    private suspend fun handleStreamError(exception: Throwable) {
        println("[Session] Stream error: ${exception.message}")

        when (exception) {
            is java.io.IOException -> {
                // Network/Process error - attempt reconnection
                _events.emit(StreamSessionEvent.Error("Connection lost, attempting recovery..."))
                attemptStreamReconnection()
            }

            is kotlinx.serialization.SerializationException -> {
                // JSON parsing error - log but continue
                println("[Session] JSON parsing error: ${exception.message}")
                _events.emit(StreamSessionEvent.Warning("Message parsing error"))
            }

            else -> {
                // Unknown error - transition to error state
                sessionMutex.withLock {
                    _sessionState.value = SessionState.ERROR
                    _events.emit(StreamSessionEvent.Error("Fatal stream error: ${exception.message}"))
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
                        scope.launchOutputStreamCollection()
                    }

                    _events.emit(StreamSessionEvent.StreamReconnected)
                }
            }

        } catch (e: Exception) {
            println("[Session] Stream reconnection failed: ${e.message}")
            sessionMutex.withLock {
                _sessionState.value = SessionState.ERROR
                _events.emit(StreamSessionEvent.Error("Reconnection failed: ${e.message}"))
            }
        }
    }

    private suspend fun cleanup() {
        try {
            // Cancel jobs
            metadataUpdateJob?.cancel()
            assistantUpdateJob?.cancel()
            streamCollectionJob?.cancel()

            // Stop Claude process if started
            claudeWrapper.stop()

            // Reset state
            sessionScope = null

        } catch (cleanupException: Exception) {
            println("[Session] Error during cleanup: ${cleanupException.message}")
            // Don't throw - we're already in error recovery
        }
    }


    private suspend fun loadHistoricalMessages(oldSessionId: String) {
        try {
            val historicalMessages = sessionJsonlService.loadMessagesFromSession(oldSessionId, projectPath)

            if (historicalMessages.isNotEmpty()) {
                println("[Session] Loaded ${historicalMessages.size} historical messages")

                historicalMessages
                    .forEach { _messageOutputStream.emit(it.copy(isHistorical = true)) }

                _events.emit(StreamSessionEvent.HistoricalMessagesLoaded(historicalMessages.size))
            } else {
                println("[Session] No historical messages found for session: $oldSessionId")
            }

        } catch (e: Exception) {
            println("[Session] Failed to load historical messages: ${e.message}")
            _events.emit(StreamSessionEvent.Warning("Failed to load history: ${e.message}"))
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
    data object InterruptSent : StreamSessionEvent()
    data object InterruptAcknowledged : StreamSessionEvent()
}

/**
 * Exception thrown when session fails to start
 */
class SessionStartException(message: String, cause: Throwable? = null) : Exception(message, cause)