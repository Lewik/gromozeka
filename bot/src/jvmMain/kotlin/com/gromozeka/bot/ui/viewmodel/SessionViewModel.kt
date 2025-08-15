package com.gromozeka.bot.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.utils.TokenUsageCalculator
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.MessageTagDefinition
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
    companion object {
        private val ALL_MESSAGE_TAG_DEFINITIONS = listOf(
            MessageTagDefinition(
                controls = listOf(
                    MessageTagDefinition.Control(
                        data = MessageTagDefinition.Data("thinking_off", "Off", "Обычный режим работы"),
                        includeInMessage = false
                    ),
                    MessageTagDefinition.Control(
                        data = MessageTagDefinition.Data("thinking_ultrathink", "Ultrathink", "Режим глубокого анализа с пошаговыми рассуждениями и детальной проработкой"),
                        includeInMessage = true
                    )
                ),
                selectedByDefault = 1  // Ultrathink по умолчанию
            ),
            MessageTagDefinition(
                controls = listOf(
                    MessageTagDefinition.Control(
                        data = MessageTagDefinition.Data("mode_readonly", "Readonly", "Режим readonly - никаких изменений кода или команд применяющих изменения"),
                        includeInMessage = true
                    ),
                    MessageTagDefinition.Control(
                        data = MessageTagDefinition.Data("mode_writable", "Writable", "Разрешено исправление файлов"),
                        includeInMessage = true
                    )
                ),
                selectedByDefault = 0  // Readonly по умолчанию
            )
        )
        
        fun getDefaultEnabledTags(): Set<String> {
            return ALL_MESSAGE_TAG_DEFINITIONS.map { tagDefinition ->
                tagDefinition.controls[tagDefinition.selectedByDefault].data.id
            }.toSet()
        }
    }
    
    val availableMessageTags = ALL_MESSAGE_TAG_DEFINITIONS

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
    fun toggleMessageTag(messageTag: MessageTagDefinition, controlIndex: Int) {
        _uiState.update { currentState ->
            if (controlIndex >= 0 && controlIndex < messageTag.controls.size) {
                val selectedId = messageTag.controls[controlIndex].data.id
                
                // Get all IDs from this MessageTag group
                val allIdsInGroup = messageTag.controls.map { it.data.id }.toSet()
                
                // Check if clicked ID is already active in this group
                val isAlreadyActive = selectedId in currentState.activeMessageTags
                
                if (isAlreadyActive) {
                    // If already active, do nothing (ignore repeat clicks)
                    currentState
                } else {
                    // Remove all IDs from this group, then add the new one
                    val cleanedTags = currentState.activeMessageTags - allIdsInGroup
                    val newTags = cleanedTags + selectedId
                    currentState.copy(activeMessageTags = newTags)
                }
            } else {
                currentState
            }
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
        
        // Collect all active tag data that should be included in message
        val activeTagsData = availableMessageTags.mapNotNull { messageTag ->
            // Find which control should be active based on currentState.activeMessageTags
            val activeControlIndex = messageTag.controls.indexOfFirst { control ->
                control.data.id in currentState.activeMessageTags
            }
            
            val selectedControlIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault
            val selectedControl = messageTag.controls[selectedControlIndex]
            
            // Include only if this control should be sent to chat
            if (selectedControl.includeInMessage) {
                selectedControl.data
            } else null
        }

        val messageWithInstructions = if (activeTagsData.isNotEmpty()) {
            "$message\n\n<instructions>\n${activeTagsData.joinToString("\n") { it.instruction }}\n</instructions>"
        } else {
            message
        }

        // Send MessageTag.Data directly to Session
        session.sendMessage(messageWithInstructions, activeTagsData)
        
        // Clear input after sending
        _uiState.update { it.copy(userInput = "") }
    }
}