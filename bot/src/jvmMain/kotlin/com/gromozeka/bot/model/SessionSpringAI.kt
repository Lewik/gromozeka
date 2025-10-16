package com.gromozeka.bot.model

import com.gromozeka.bot.services.SoundNotificationService
import com.gromozeka.bot.utils.ChatMessageSoundDetector
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.SessionUuid
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import kotlin.time.Clock

class SessionSpringAI(
    val id: SessionUuid,
    val projectPath: String,
    private val chatClient: ChatClient,
    private val soundNotificationService: SoundNotificationService,
    private val agentDefinition: AgentDefinition,
) {
    private val log = KLoggers.logger(this)

    val claudeSessionId = MutableStateFlow(ClaudeSessionUuid(id.value))

    private val messageHistory = mutableListOf<Message>()

    private val _messageOutputStream = MutableSharedFlow<ChatMessage>(
        replay = 1000,
        extraBufferCapacity = 1000
    )
    val messageOutputStream: SharedFlow<ChatMessage> = _messageOutputStream.asSharedFlow()

    private val _events = MutableSharedFlow<StreamSessionEvent>()
    val events: SharedFlow<StreamSessionEvent> = _events.asSharedFlow()

    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()

    private val _pendingMessagesCount = MutableStateFlow(0)
    val pendingMessagesCount: StateFlow<Int> = _pendingMessagesCount.asStateFlow()

    private var sessionScope: CoroutineScope? = null
    private var isActive = false

    suspend fun start(scope: CoroutineScope) {
        log.info { "Starting Spring AI session for project: $projectPath" }
        sessionScope = scope
        isActive = true
        _events.emit(StreamSessionEvent.Started)
    }

    suspend fun stop() {
        log.info { "Stopping Spring AI session" }
        isActive = false
        sessionScope = null
        _isWaitingForResponse.value = false
        _events.emit(StreamSessionEvent.Stopped)
    }

    suspend fun sendMessage(chatMessage: ChatMessage) {
        if (!isActive) {
            log.warn { "Cannot send message - session not active" }
            _events.emit(StreamSessionEvent.Warning("Session not active"))
            return
        }

        val textContent = chatMessage.content
            .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
            .joinToString(" ") { it.text }

        if (textContent.isBlank()) {
            log.debug { "Skipping empty message" }
            return
        }

        try {
            log.debug { "Sending message to Spring AI session: '$textContent'" }
            _isWaitingForResponse.value = true
            _messageOutputStream.emit(chatMessage)

            val userMessage = UserMessage(textContent)
            messageHistory.add(userMessage)

            // User-controlled mode: Claude Code CLI built-in tools are disabled
            // Spring AI intercepts tool_use blocks and executes tools via ToolCallingManager
            // Then recursively calls Claude with tool results until we get final text response
            val response: ChatResponse = withContext(Dispatchers.IO) {
                chatClient.prompt()
                    .messages(messageHistory)
                    .call()
                    .chatResponse()
            }

            log.debug { "Spring AI response received" }

            val assistantMessage = response.result.output

            messageHistory.add(assistantMessage)

            convertAndEmitAssistantMessage(assistantMessage)

            _isWaitingForResponse.value = false
            soundNotificationService.playReadySound()

        } catch (e: Exception) {
            log.error(e) { "Failed to send message" }
            _isWaitingForResponse.value = false
            _events.emit(StreamSessionEvent.Error("Send failed: ${e.message}"))
        }
    }

    suspend fun sendInterrupt() {
        log.debug { "Interrupt not supported in Spring AI mode" }
        _events.emit(StreamSessionEvent.Warning("Interrupt not supported"))
    }

    private suspend fun convertAndEmitAssistantMessage(assistantMessage: AssistantMessage) {
        // User-controlled mode: Spring AI handles tool execution
        // After all tool calls are executed, we receive the final text response
        // We only emit the final text to UI (tool execution happens transparently)

        val hasText = !assistantMessage.text.isNullOrBlank()
        val hasToolCalls = assistantMessage.toolCalls?.isNotEmpty() == true

        when {
            hasText -> {
                // Final text response after tool execution
                val chatMessage = ChatMessage(
                    uuid = java.util.UUID.randomUUID().toString(),
                    timestamp = Clock.System.now(),
                    role = ChatMessage.Role.ASSISTANT,
                    content = listOf(
                        ChatMessage.ContentItem.AssistantMessage(
                            structured = ChatMessage.StructuredText(
                                fullText = assistantMessage.text
                            )
                        )
                    ),
                    llmSpecificMetadata = null
                )

                _messageOutputStream.emit(chatMessage)
                playMessageSound(chatMessage)
            }
            hasToolCalls -> {
                // Tool calls present - Spring AI will execute them and make recursive call
                // We don't emit anything to UI here, waiting for final response
                log.debug { "  Tool calls detected (${assistantMessage.toolCalls?.size}), waiting for final response after tool execution" }
            }
            else -> {
                // Neither text nor tool calls - unexpected but log it
                log.warn { "  No text and no tool calls - empty response from Claude" }
            }
        }
    }

    private suspend fun playMessageSound(message: ChatMessage) {
        try {
            when {
                ChatMessageSoundDetector.shouldPlayErrorSound(message) -> {
                    soundNotificationService.playErrorSound()
                }
                ChatMessageSoundDetector.shouldPlayMessageSound(message) -> {
                    soundNotificationService.playMessageSound()
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Sound notification error" }
        }
    }
}
