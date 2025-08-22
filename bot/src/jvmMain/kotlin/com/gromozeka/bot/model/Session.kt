package com.gromozeka.bot.model

import com.gromozeka.bot.services.ClaudeWrapper
import com.gromozeka.bot.services.SessionJsonlService
import com.gromozeka.bot.services.SoundNotificationService
import com.gromozeka.bot.services.llm.claudecode.converter.ClaudeMessageConverter
import com.gromozeka.bot.utils.ChatMessageSoundDetector
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.SessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Clock


/**
 * Stream-based Session class that processes messages from Claude Code CLI stdout stream.
 *
 * ARCHITECTURE: Session as Communication Hub
 * ==========================================
 * Session acts as a Message Router + State Machine that receives commands and streams,
 * aggregates them, and distributes via typed channels for different UI consumers.
 *
 * INCOMING CHANNELS (→ Session Actor) by priority:
 * 1. Priority Channel: interrupt, forceSend - critical control commands
 *    Nature: Bypass normal flow for immediate handling
 * 2. Claude Stream Channel: claudeWrapper.streamOutput() → StreamJsonLine flow
 *    Nature: Continuous reactive stream from Claude CLI (processed before new user input)
 * 3. User Command Channel: start(), stop(), sendMessage() from UI/user
 *    Nature: Imperative commands (waits for Claude responses to complete)
 * - Historical Data: sessionJsonlService.loadMessagesFromSession()
 *   Nature: One-time bulk load during session resume (not a channel)
 *
 * OUTGOING CHANNELS (Session →):
 * - State Channels (StateFlow): claudeSessionId, sessionState, isWaitingForResponse
 *   Nature: Hot observable state for UI binding and lifecycle tracking
 * - Message Stream Channel (SharedFlow): messageOutputStream - unified chat messages
 *   Nature: Event stream with replay buffer (1000 messages) for late subscribers
 * - Event Channel (SharedFlow): events - lifecycle/error event notifications
 *   Nature: Discrete event notifications for UI reactions
 * - Side Effect Channels: Sound notifications, buffered message processing
 *   Nature: Fire-and-forget side effects
 *
 * INTERNAL CHANNELS:
 * - _messageInputBuffer: Rate limiting/buffering for session ID race conditions
 * - Session ID synchronization logic for handling Claude CLI session switches
 *
 * Key architectural differences from SessionJsonl:
 * - Real-time streaming instead of file monitoring
 * - Direct integration with ClaudeCodeStreamingWrapper.streamOutput()
 * - In-memory message accumulation with real-time streaming
 * - Historical message loading support for session resume
 *
 * Thread Safety: Single mutex protects all mutable operations
 * Reactive Updates: StateFlow for UI consumption with immutable updates
 * Process Lifecycle: Robust start/stop with graceful shutdown and error recovery
 */
class Session(
    val id: SessionUuid,  // Added to track session identity
    val projectPath: String,
    private val sessionJsonlService: SessionJsonlService,
    private val soundNotificationService: SoundNotificationService,
    private val claudeWrapper: ClaudeWrapper,
    private val claudeMessageConverter: ClaudeMessageConverter,
    private val mcpConfigPath: String,
    private val claudeModel: String? = null,
    private val responseFormat: com.gromozeka.bot.settings.ResponseFormat = com.gromozeka.bot.settings.ResponseFormat.JSON,
    private val appendSystemPrompt: String = "",
    private val initialClaudeSessionId: ClaudeSessionUuid = ClaudeSessionUuid.DEFAULT,
) {

    // === ACTOR CHANNELS ===
    private val userCommandChannel = Channel<Command>(capacity = Channel.UNLIMITED)
    private val priorityChannel = Channel<PriorityCommand>(capacity = Channel.UNLIMITED)
    private val claudeStreamChannel = Channel<StreamJsonLinePacket>(capacity = Channel.UNLIMITED)

    // === ACTOR STATE ===  
    private var actorState: ActorState = ActorState.Inactive
    private var actorJob: Job? = null
    private var currentSessionId = initialClaudeSessionId
    private var actorScope: CoroutineScope? = null
    
    private fun incrementPendingCount() {
        _pendingMessagesCount.value++
    }
    
    private fun decrementPendingCount() {
        if (_pendingMessagesCount.value > 0) {
            _pendingMessagesCount.value--
        }
    }

    // === OUTGOING CHANNELS (StateFlow/SharedFlow for UI consumption) ===
    private val _claudeSessionId = MutableStateFlow(initialClaudeSessionId)
    val claudeSessionId: StateFlow<ClaudeSessionUuid> = _claudeSessionId.asStateFlow()

    private val _messageOutputStream = MutableSharedFlow<ChatMessage>(
        replay = 1000,  // Keep last 1000 messages for late subscribers
        extraBufferCapacity = 1000
    )
    val messageOutputStream: SharedFlow<ChatMessage> = _messageOutputStream.asSharedFlow()

    private val _events = MutableSharedFlow<StreamSessionEvent>()
    val events: SharedFlow<StreamSessionEvent> = _events.asSharedFlow()


    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()

    private val _pendingMessagesCount = MutableStateFlow(0)
    val pendingMessagesCount: StateFlow<Int> = _pendingMessagesCount.asStateFlow()

    // === ACTOR COMMAND DEFINITIONS ===

    /**
     * Commands for the Session actor - main lifecycle and message operations
     */
    sealed class Command {
        data class Start(
            val scope: CoroutineScope,
        ) : Command()

        data object Stop : Command()

        data class SendMessage(
            val content: String,
            val instructions: List<ChatMessage.Instruction> = emptyList(),
            val sender: ChatMessage.Sender? = null,
        ) : Command()
    }

    /**
     * Priority commands - processed before regular commands in select
     */
    sealed class PriorityCommand {
        data object Interrupt : PriorityCommand()

        data object ForceSend : PriorityCommand()
    }

    /**
     * Internal actor states for lifecycle management using sealed class hierarchy
     *
     * State transition graph:
     * ```
     * Inactive → Starting → WaitingForInit → Active.Ready ⇄ Active.WaitingForResponse
     *     ↑           ↓            ↓               ↓                 ↓
     *     └───────────────────── Stopping ←─────────────────────────┘
     *                               ↓
     *                            (Error can occur from any state except Inactive)
     * ```
     *
     * Key transitions:
     * - Start: only from Inactive
     * - Stop: from any state except Inactive/Stopping
     * - SendMessage: only when Active.Ready
     * - Interrupt: from any Active state (Ready or WaitingForResponse)
     * - Error: can occur from any state during operations
     */
    sealed class ActorState {
        object Inactive : ActorState()           // Initial state, no Claude process
        object Starting : ActorState()           // Starting Claude process  
        object WaitingForInit : ActorState()     // Process started, waiting for init message

        // Active states - can be Ready or WaitingForResponse
        sealed class Active : ActorState() {
            object Ready : Active()               // Ready to accept new commands
            object WaitingForResponse : Active()  // Waiting for Claude to respond
        }

        object Stopping : ActorState()           // Graceful shutdown in progress
        object Error : ActorState()              // Error state, requires cleanup
    }

    // === ACTOR LOOP ===

    /**
     * Main actor loop with select-based channel prioritization.
     * Priority order: 1) Priority, 2) Claude stream, 3) User commands
     * Rationale: Interrupts are critical, Claude responses should be processed before new user input
     *
     * Key mechanisms:
     * - Natural backpressure: User commands wait in channel when not registered
     * - Conditional registration: Channels only registered based on current state
     * - State evaluated at select entry: Each loop iteration checks state anew
     * - No mutex needed: Single-threaded actor eliminates race conditions
     */
    private suspend fun actorLoop() {
        println("[Actor] Starting actor loop")

        while (true) {
            select<Unit> {
                // PRIORITY 1: Priority commands - always processed
                priorityChannel.onReceive { command ->
                    println("[Actor] Processing priority command: $command")
                    handlePriorityCommand(command)
                }

                // PRIORITY 2: Claude stream - only when waiting for init or active
                if (actorState == ActorState.WaitingForInit || actorState is ActorState.Active) {
                    claudeStreamChannel.onReceive { streamPacket ->
                        println("[Actor] Processing Claude stream message: ${streamPacket.streamMessage.type}")
                        handleClaudeStreamMessage(streamPacket)
                    }
                }

                // PRIORITY 3: User commands - only when ready to process them
                if (actorState != ActorState.Active.WaitingForResponse) {
                    userCommandChannel.onReceive { command ->
                        println("[Actor] Processing user command: $command")
                        decrementPendingCount()
                        handleUserCommand(command)
                    }
                }
            }
        }
    }

    /**
     * Handle user commands - called only when state allows processing
     * Thanks to conditional registration in select, we know the state is appropriate
     */
    private suspend fun handleUserCommand(command: Command) {
        when (command) {
            is Command.SendMessage -> {
                // We only get here when NOT in WaitingForResponse
                when (actorState) {
                    ActorState.WaitingForInit -> {
                        // First message during init - send immediately to trigger Claude
                        println("[Actor] First message during init - sending immediately")
                        handleFirstMessageDuringInit(command)
                    }

                    ActorState.Active.Ready -> {
                        // Normal message sending
                        performSendMessage(command)
                    }

                    else -> {
                        // Inactive, Starting, Stopping, Error states
                        println("[Actor] Cannot send message in state: $actorState")
                        _events.emit(StreamSessionEvent.Warning("Cannot send message: session not active"))
                    }
                }
            }

            is Command.Start -> {
                if (actorState == ActorState.Inactive) {
                    performStart(command.scope)
                } else {
                    println("[Actor] Cannot start - already in state: $actorState")
                    _events.emit(StreamSessionEvent.Warning("Session already active or starting"))
                }
            }

            is Command.Stop -> {
                if (actorState != ActorState.Inactive && actorState != ActorState.Stopping) {
                    performStop()
                } else {
                    println("[Actor] Already stopping or inactive")
                }
            }
        }
    }

    /**
     * Handle priority commands with special forceSend logic
     */
    private suspend fun handlePriorityCommand(command: PriorityCommand) {
        when (command) {
            is PriorityCommand.Interrupt -> {
                if (actorState is ActorState.Active) {
                    performInterrupt()
                } else {
                    println("[Actor] Cannot interrupt - session not active (state: $actorState)")
                    _events.emit(StreamSessionEvent.Warning("Cannot interrupt inactive session"))
                }
            }

            is PriorityCommand.ForceSend -> {
                when {
                    actorState !is ActorState.Active -> {
                        println("[Actor] Cannot force send - session not active (state: $actorState)")
                        _events.emit(StreamSessionEvent.Warning("Cannot force send: session inactive"))
                    }

                    else -> {
                        // Try to extract a SendMessage command from the user channel
                        val pendingCommand = userCommandChannel.tryReceive().getOrNull()
                        when {
                            pendingCommand is Command.SendMessage -> {
                                decrementPendingCount() // Successfully extracted command
                                println("[Actor] Force sending extracted message: ${pendingCommand.content.take(50)}...")
                                try {
                                    // Directly send the message bypassing normal flow
                                    performSendMessage(pendingCommand)
                                } catch (e: Exception) {
                                    println("[Actor] Force send failed: ${e.message}")
                                    _events.emit(StreamSessionEvent.Error("Force send failed: ${e.message}"))
                                }
                            }

                            pendingCommand != null -> {
                                // Put non-SendMessage command back (count stays the same)
                                userCommandChannel.send(pendingCommand)
                                println("[Actor] No SendMessage to force send, returned command to channel")
                                _events.emit(StreamSessionEvent.Warning("No pending message to force send"))
                            }

                            else -> {
                                println("[Actor] No commands in channel to force send")
                                _events.emit(StreamSessionEvent.Warning("No pending commands to force send"))
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Handle first message during WaitingForInit state
     * This allows the first message to be sent immediately to trigger Claude's response
     */
    private suspend fun handleFirstMessageDuringInit(sendMessageCommand: Command.SendMessage) {

        if (sendMessageCommand.content.isBlank()) {
            println("[Actor] Skipping empty first message")
            return
        }

        try {
            val messageWithInstructions = claudeMessageConverter.serializeMessageWithTags(
                content = sendMessageCommand.content,
                instructions = sendMessageCommand.instructions,
                sender = sendMessageCommand.sender
            )

            println("[Actor] Sending first message during init: ${messageWithInstructions.take(50)}...")

            // Create the ChatMessage
            val chatMessage = ChatMessage(
                role = ChatMessage.Role.USER,
                content = listOf(ChatMessage.ContentItem.UserMessage(messageWithInstructions)),
                timestamp = Clock.System.now(),
                uuid = java.util.UUID.randomUUID().toString(),
                llmSpecificMetadata = null,
            )


            // Emit user message to UI
            _messageOutputStream.emit(chatMessage)


            // Mark as waiting for response (but stay in WaitingForInit state)
            _isWaitingForResponse.value = true

            // Send message to Claude - this will trigger init response
            claudeWrapper.sendMessage(messageWithInstructions, currentSessionId)

        } catch (e: Exception) {
            println("[Actor] Failed to send first message: ${e.message}")
            _events.emit(StreamSessionEvent.Error("First message failed: ${e.message}"))
        }
    }

    /**
     * Handle stream messages from Claude CLI
     */
    private suspend fun handleClaudeStreamMessage(streamPacket: StreamJsonLinePacket) {
        // Same logic as current handleOutputStreamJsonLine but without mutex
        try {
            val streamMessage = streamPacket.streamMessage

            // When we receive final result, transition back to Ready
            if (streamMessage is ClaudeCodeStreamJsonLine.Result) {
                if (actorState == ActorState.Active.WaitingForResponse) {
                    actorState = ActorState.Active.Ready
                    println("[Actor] Response complete - back to Ready state")
                    soundNotificationService.playReadySound()
                }
                _isWaitingForResponse.value = false
            }

            when (streamMessage) {
                is ClaudeCodeStreamJsonLine.System -> handleSystemMessage(streamMessage)
                is ClaudeCodeStreamJsonLine.Assistant -> Unit // Expected - Claude's responses
                is ClaudeCodeStreamJsonLine.Result -> Unit // Expected - final result with usage
                is ClaudeCodeStreamJsonLine.ControlResponse -> handleControlResponse(streamMessage)

                // These should NEVER come from Claude CLI - log as warnings
                is ClaudeCodeStreamJsonLine.User -> {
                    println("[Actor] WARNING: Received User message from Claude stream - this shouldn't happen!")
                    _events.emit(StreamSessionEvent.Warning("Unexpected User message from Claude"))
                }

                is ClaudeCodeStreamJsonLine.ControlRequest -> {
                    println("[Actor] WARNING: Received ControlRequest from Claude stream - this shouldn't happen!")
                    _events.emit(StreamSessionEvent.Warning("Unexpected ControlRequest from Claude"))
                }
            }

            val chatMessage = claudeMessageConverter
                .toMessage(streamPacket.streamMessage)
                .copy(originalJson = streamPacket.originalJson)
            _messageOutputStream.emit(chatMessage)

        } catch (e: Exception) {
            println("[Actor] Error processing stream message: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Message processing error: ${e.message}"))
        }
    }

    // === ACTOR COMMAND HANDLERS ===

    /**
     * Actor implementation of start command
     */
    private suspend fun performStart(scope: CoroutineScope) {
        println("[Actor] Starting session for project: $projectPath")

        require(actorState == ActorState.Inactive) {
            "Session is already active or starting. Current state: $actorState"
        }

        actorScope = scope
        actorState = ActorState.Starting

        try {
            // Load historical messages if resuming (skip if default/new session)
            if (currentSessionId != ClaudeSessionUuid.DEFAULT) {
                println("[Actor] Loading historical messages from session: $currentSessionId")
                loadHistoricalMessages(currentSessionId)
            }

            // Start stream collector coroutine
            launchStreamCollector()

            // Start sound notification collection  
            launchSoundNotificationCollection()

            // 2. THEN: Start Claude CLI process with MCP config
            claudeWrapper.start(
                projectPath = projectPath,
                model = claudeModel,
                responseFormat = responseFormat,
                resumeSessionId = currentSessionId.takeIf { it != ClaudeSessionUuid.DEFAULT },
                appendSystemPrompt = appendSystemPrompt,
                mcpConfigPath = mcpConfigPath,
                tabId = id.value
            )

            // Move to waiting for init state
            actorState = ActorState.WaitingForInit
            _events.emit(StreamSessionEvent.Started)

            println("[Actor] Session started successfully")

        } catch (e: Exception) {
            println("[Actor] Failed to start session: ${e.message}")
            actorState = ActorState.Error

            performCleanup()
            _events.emit(StreamSessionEvent.Error("Failed to start session: ${e.message}"))
        }
    }

    /**
     * Actor implementation of stop command
     */
    private suspend fun performStop() {
        val currentState = actorState

        if (currentState == ActorState.Inactive) {
            println("[Actor] Session is already inactive")
            return
        }

        if (currentState == ActorState.Stopping) {
            println("[Actor] Session is already stopping")
            return
        }

        actorState = ActorState.Stopping

        try {
            println("[Actor] Stopping session...")

            // 1. FIRST: Stop Claude process (kills mcp-proxy automatically)
            try {
                claudeWrapper.stop()
            } catch (e: Exception) {
                println("[Actor] Error stopping Claude process: ${e.message}")
            }

            // Reset state
            actorState = ActorState.Inactive
            _isWaitingForResponse.value = false
            actorScope = null

            _events.emit(StreamSessionEvent.Stopped)
            println("[Actor] Session stopped successfully")

        } catch (e: Exception) {
            println("[Actor] Error during stop: ${e.message}")
            actorState = ActorState.Error
            _isWaitingForResponse.value = false
            actorScope = null

        }
    }

    /**
     * Actor implementation of sendMessage command
     */
    private suspend fun performSendMessage(sendMessageCommand: Command.SendMessage) {
        // This should only be called when Active.Ready
        require(actorState == ActorState.Active.Ready) {
            "Can only send messages when Ready. Current state: $actorState"
        }

        // Skip empty or blank messages
        if (sendMessageCommand.content.isBlank()) {
            println("[Actor] Skipping empty/blank message")
            return
        }

        try {
            val messageWithInstructions = claudeMessageConverter.serializeMessageWithTags(
                content = sendMessageCommand.content,
                instructions = sendMessageCommand.instructions,
                sender = sendMessageCommand.sender
            )

            println("[Actor] Sending message: ${messageWithInstructions.take(100)}${if (messageWithInstructions.length > 100) "..." else ""}")
            // Emit user message to stream
            _messageOutputStream.emit(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = listOf(ChatMessage.ContentItem.UserMessage(messageWithInstructions)),
                    timestamp = Clock.System.now(),
                    uuid = java.util.UUID.randomUUID().toString(),
                    llmSpecificMetadata = null,
                )
            )

            // Transition to WaitingForResponse state
            actorState = ActorState.Active.WaitingForResponse
            _isWaitingForResponse.value = true

            // Send message to Claude
            claudeWrapper.sendMessage(messageWithInstructions, currentSessionId)

        } catch (e: Exception) {
            println("[Actor] Failed to send message: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Send failed: ${e.message}"))
        }
    }

    /**
     * Actor implementation of interrupt command
     */
    private suspend fun performInterrupt() {
        try {
            println("[Actor] Sending interrupt request...")

            // Generate unique request ID
            val requestId = "req_${System.currentTimeMillis()}_${kotlin.random.Random.nextInt(10000)}"

            val controlRequest = ClaudeCodeStreamJsonLine.ControlRequest(
                requestId = requestId,
                request = ControlRequest(subtype = "interrupt")
            )

            claudeWrapper.sendControlMessage(controlRequest)

            _events.emit(StreamSessionEvent.InterruptSent)
            println("[Actor] Interrupt sent successfully")

        } catch (e: Exception) {
            println("[Actor] Interrupt failed: ${e.message}")
            _events.emit(StreamSessionEvent.Error("Interrupt failed: ${e.message}"))
        }
    }

    // === HELPER METHODS ===

    /**
     * Launch coroutine that collects Claude CLI stream and forwards to streamChannel
     */
    private fun launchStreamCollector() {
        actorScope?.launch {
            try {
                claudeWrapper.streamOutput()
                    .flowOn(Dispatchers.IO)
                    .collect { streamPacket ->
                        claudeStreamChannel.send(streamPacket)
                    }
            } catch (e: Exception) {
                println("[Actor] Stream collector error: ${e.message}")
                _events.emit(StreamSessionEvent.Error("Stream collection failed: ${e.message}"))
            }
        }
    }

    /**
     * Launch sound notification collection coroutine
     */
    private fun launchSoundNotificationCollection() {
        actorScope?.launch {
            messageOutputStream
                .filter { message -> !message.isHistorical }
                .collect { chatMessage ->
                    try {
                        when {
                            ChatMessageSoundDetector.shouldPlayErrorSound(chatMessage) -> {
                                println("[Actor] Playing error sound for message type: ${chatMessage.role}")
                                soundNotificationService.playErrorSound()
                            }

                            ChatMessageSoundDetector.shouldPlayMessageSound(chatMessage) -> {
                                println("[Actor] Playing message sound for message type: ${chatMessage.role}")
                                soundNotificationService.playMessageSound()
                            }
                        }
                    } catch (e: Exception) {
                        println("[Actor] Sound notification error: ${e.message}")
                    }
                }
        }
    }

    /**
     * Cleanup resources and reset state
     */
    private suspend fun performCleanup() {
        try {
            claudeWrapper.stop()
            actorScope = null
        } catch (e: Exception) {
            println("[Actor] Error during cleanup: ${e.message}")
        }
    }

    // === ADDITIONAL HELPER METHODS FOR ACTOR ===

    /**
     * Load historical messages from a previous session (used in performStart)
     */
    private suspend fun loadHistoricalMessages(oldSessionId: ClaudeSessionUuid) {
        try {
            val historicalMessages = sessionJsonlService.loadMessagesFromSession(oldSessionId, projectPath)

            if (historicalMessages.isNotEmpty()) {
                println("[Actor] Loaded ${historicalMessages.size} historical messages")
                historicalMessages.forEach { _messageOutputStream.emit(it.copy(isHistorical = true)) }
                _events.emit(StreamSessionEvent.HistoricalMessagesLoaded(historicalMessages.size))
            } else {
                println("[Actor] No historical messages found for session: $oldSessionId")
            }

        } catch (e: Exception) {
            println("[Actor] Failed to load historical messages: ${e.message}")
            _events.emit(StreamSessionEvent.Warning("Failed to load history: ${e.message}"))
        }
    }

    /**
     * Handle system messages from Claude CLI (session initialization, etc.)
     */
    private suspend fun handleSystemMessage(message: ClaudeCodeStreamJsonLine.System) {
        if (message.subtype == "init") {
            val newSessionId = message.sessionId!!

            // Update session ID if changed
            if (currentSessionId != newSessionId) {
                println("[Actor] Session ID updated: $currentSessionId -> $newSessionId")
                currentSessionId = newSessionId
                _claudeSessionId.value = newSessionId
                _events.emit(StreamSessionEvent.SessionIdChangedOnStart(newSessionId))

                // Reset to waiting state on session change (like after /compact)
                if (actorState is ActorState.Active) {
                    actorState = ActorState.WaitingForInit
                    println("[Actor] Session ID changed - back to WaitingForInit")
                }
            }

            // Move from WaitingForInit to Active.Ready on init message
            if (actorState == ActorState.WaitingForInit) {
                actorState = ActorState.Active.Ready
                println("[Actor] Session initialized with ID: $newSessionId")
                println("[Actor] Session now Active.Ready - user commands unblocked")

                // No need to process buffered messages - they're waiting in channel!
            }
        }
    }

    /**
     * Handle control responses from Claude CLI
     */
    private suspend fun handleControlResponse(response: ClaudeCodeStreamJsonLine.ControlResponse) {
        println("[Actor] Control response: ${response.response.subtype}")

        when (response.response.subtype) {
            "success" -> {
                _isWaitingForResponse.value = false
                _events.emit(StreamSessionEvent.InterruptAcknowledged)
            }

            "error" -> {
                _events.emit(StreamSessionEvent.Error("Interrupt error: ${response.response.error}"))
            }
        }
    }


    // === BACKWARDS COMPATIBILITY API (Channel Proxies) ===
    // These methods maintain the existing public API by forwarding calls to actor channels

    /**
     * Initialize and start the actor system
     */
    fun initializeActor(scope: CoroutineScope) {
        if (actorJob == null) {
            actorJob = scope.launch { actorLoop() }
            println("[Session] Actor initialized")
        }
    }

    /**
     * Start the session: launch Claude Code CLI process and begin stream collection
     * @param scope CoroutineScope for session lifecycle
     * @param resumeSessionId Optional session ID to load historical messages from
     */
    suspend fun start(scope: CoroutineScope) {
        initializeActor(scope)
        incrementPendingCount()
        userCommandChannel.send(Command.Start(scope))
    }

    /**
     * Stop the session: stop Claude Code CLI process and stream collection
     */
    suspend fun stop() {
        incrementPendingCount()
        userCommandChannel.send(Command.Stop)
    }

    /**
     * Send a message through the Claude Code CLI process
     */
    suspend fun sendMessage(
        content: String, 
        instructions: List<ChatMessage.Instruction> = emptyList(),
        sender: ChatMessage.Sender? = null
    ) {
        incrementPendingCount()
        userCommandChannel.send(Command.SendMessage(content, instructions, sender))
    }

    /**
     * Send interrupt signal to Claude Code CLI
     */
    suspend fun sendInterrupt() {
        priorityChannel.send(PriorityCommand.Interrupt)
    }

    /**
     * Force send one buffered message (bypass normal flow control)
     * Useful for recovering from "stuck" states
     */
    suspend fun forceSend() {
        priorityChannel.send(PriorityCommand.ForceSend)
    }
}

/**
 * Session metadata for UI display
 */
data class StreamSessionMetadata(
    val sessionId: SessionUuid,
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
    data class SessionIdChangedOnStart(val newSessionId: ClaudeSessionUuid) : StreamSessionEvent()
    data class HistoricalMessagesLoaded(val messageCount: Int) : StreamSessionEvent()
    data object InterruptSent : StreamSessionEvent()
    data object InterruptAcknowledged : StreamSessionEvent()
}

/**
 * Exception thrown when session fails to start
 */
class SessionStartException(message: String, cause: Throwable? = null) : Exception(message, cause)