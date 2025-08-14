package com.gromozeka.bot.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.utils.TokenUsageCalculator
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.MessageTag
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class SessionViewModel(
    // Session is intentionally private to maintain clean MVVM architecture.
    // SessionViewModel is the "head" for headless Session - UI layer should only interact
    // with SessionViewModel, not with Session directly. Session lives without any UI knowledge.
    // This ensures isolation of business logic from the presentation layer.
    private val session: Session,
    private val settingsFlow: StateFlow<Settings>,
    private val scope: CoroutineScope,
    initialTabUiState: UIState.Tab,
) {

    // === Public accessors for AppViewModel ===
    val sessionId get() = session.id  // Get from Session directly
    val projectPath get() = session.projectPath
    val claudeSessionId get() = session.claudeSessionId

    // === Immutable UI State (Official Android Pattern) ===
    private val _uiState = MutableStateFlow(initialTabUiState)
    val uiState: StateFlow<UIState.Tab> = _uiState.asStateFlow()

    // === Reactive updates from Session ===
    init {
        // Update claudeSessionId when it changes in Session
        session.claudeSessionId.onEach { newSessionId ->
            _uiState.update { currentState ->
                currentState.copy(claudeSessionId = newSessionId)
            }
        }.launchIn(scope)

        // Update isWaitingForResponse when it changes in Session  
        session.isWaitingForResponse.onEach { isWaiting ->
            _uiState.update { currentState ->
                currentState.copy(isWaitingForResponse = isWaiting)
            }
        }.launchIn(scope)
    }

    // === Non-persistent UI State ===
    var jsonToShow by mutableStateOf<String?>(null)

    // === Message Tags ===
    val availableMessageTags = listOf(
        MessageTag("Ultrathink", "Режим глубокого анализа с пошаговыми рассуждениями и детальной проработкой"),
        MessageTag("Readonly", "Режим readonly - никаких изменений кода или команд применяющих изменения"),
        MessageTag("Research", "Режим исследования - приоритет поиску в интернете и документации"),
        MessageTag("Quick", "Быстрый режим - краткие ответы, минимум текста"),
        MessageTag("Explain", "Режим объяснений - детальный разбор с контекстом и примерами")
    )

    // === Convenience accessors for backward compatibility ===
    val activeMessageTags: Set<String> get() = _uiState.value.activeMessageTags
    val userInput: String get() = _uiState.value.userInput
    val activeMessageTagsFlow: StateFlow<Set<String>> = _uiState.map { it.activeMessageTags }.stateIn(
        scope, SharingStarted.Lazily, initialTabUiState.activeMessageTags
    )

    // === Messages from messageOutputStream (no duplication) ===
    private val allMessages: StateFlow<List<ChatMessage>> = session.messageOutputStream
        .scan(emptyList<ChatMessage>()) { acc, message -> acc + message }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // === Derived UI State ===
    val filteredMessages: StateFlow<List<ChatMessage>> = combine(
        allMessages,
        settingsFlow
    ) { messages, settings ->
        if (settings.showSystemMessages) {
            messages
        } else {
            messages.filter { message ->
                message.role != ChatMessage.Role.SYSTEM ||
                        message.content.any { content ->
                            content is ChatMessage.ContentItem.System &&
                                    content.level == ChatMessage.ContentItem.System.SystemLevel.ERROR
                        }
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // === Token Usage Calculation ===
    val tokenUsage: StateFlow<TokenUsageCalculator.SessionTokenUsage> = allMessages
        .map { messages -> TokenUsageCalculator.calculateSessionUsage(messages) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TokenUsageCalculator.SessionTokenUsage(0, 0, 0, 0)
        )

    // === Raw token usage data (UI will format it) ===

    // === Инкрементально аккумулируем в Map ===
    val toolResultsMap: StateFlow<Map<String, ChatMessage.ContentItem.ToolResult>> =
        session.messageOutputStream
            .scan(emptyMap<String, ChatMessage.ContentItem.ToolResult>()) { acc, message ->
                val results = message
                    .content
                    .filterIsInstance<ChatMessage.ContentItem.ToolResult>()
                    .associateBy { it.toolUseId }
                acc + results
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = emptyMap()
            )

    // === Session State Forwarding ===
    val isWaitingForResponse: StateFlow<Boolean> = session.isWaitingForResponse

    // === Commands (Immutable State Updates) ===
    fun toggleMessageTag(title: String) {
        _uiState.update { currentState ->
            val newTags = if (title in currentState.activeMessageTags) {
                currentState.activeMessageTags - title
            } else {
                currentState.activeMessageTags + title
            }
            currentState.copy(activeMessageTags = newTags)
        }
    }

    fun updateUserInput(input: String) {
        _uiState.update { currentState ->
            currentState.copy(userInput = input)
        }
    }

    fun updateCustomName(customName: String?) {
        _uiState.update { currentState ->
            currentState.copy(customName = customName)
        }
    }

    suspend fun sendMessageToSession(message: String) {
        val currentState = _uiState.value
        val activeTags = availableMessageTags.filter { it.title in currentState.activeMessageTags }

        val messageWithInstructions = if (activeTags.isNotEmpty()) {
            "$message\n\n<instructions>\n${activeTags.joinToString("\n") { it.instruction }}\n</instructions>"
        } else {
            message
        }

        session.sendMessage(messageWithInstructions, activeTags)
        
        // Clear input after sending
        _uiState.update { it.copy(userInput = "") }
    }
}