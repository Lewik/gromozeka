package com.gromozeka.bot.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.settings.Settings
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.message.MessageTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

class SessionViewModel(
    // Session is intentionally private to maintain clean MVVM architecture.
    // UI layer should only interact with SessionViewModel, not with Session directly.
    // This ensures isolation of business logic from the presentation layer.
    private val session: Session,
    private val settingsFlow: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {

    // === UI State (из SessionScreen) ===
    var userInput by mutableStateOf("")
    var jsonToShow by mutableStateOf<String?>(null)
    
    // === Message Tags ===
    val availableMessageTags = listOf(
        MessageTag("Ultrathink", "Режим глубокого анализа с пошаговыми рассуждениями и детальной проработкой"),
        MessageTag("Readonly", "Режим readonly - никаких изменений кода или команд применяющих изменения"),
        MessageTag("Research", "Режим исследования - приоритет поиску в интернете и документации"),
        MessageTag("Quick", "Быстрый режим - краткие ответы, минимум текста"),
        MessageTag("Explain", "Режим объяснений - детальный разбор с контекстом и примерами")
    )
    
    var activeMessageTags by mutableStateOf(setOf<String>())

    // === Messages from messageOutputStream (no duplication) ===
    private val allMessages: StateFlow<List<ChatMessage>> = session.messageOutputStream
        .scan(emptyList<ChatMessage>()) { acc, message -> acc + message }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
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

    // === Commands ===
    fun toggleMessageTag(title: String) {
        activeMessageTags = if (title in activeMessageTags) {
            activeMessageTags - title
        } else {
            activeMessageTags + title
        }
    }
    
    suspend fun sendMessage(message: String) {
        val activeTags = availableMessageTags.filter { it.title in activeMessageTags }
        
        val messageWithInstructions = if (activeTags.isNotEmpty()) {
            "$message\n\n<instructions>\n${activeTags.joinToString("\n") { it.instruction }}\n</instructions>"
        } else {
            message
        }
        
        session.sendMessage(messageWithInstructions, activeTags)
        userInput = "" // Clear input after sending
    }
}