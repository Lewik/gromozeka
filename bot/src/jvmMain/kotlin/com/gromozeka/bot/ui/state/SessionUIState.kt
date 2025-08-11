package com.gromozeka.bot.ui.state

import androidx.compose.runtime.*
import com.gromozeka.bot.model.ChatSessionMetadata
import com.gromozeka.shared.domain.message.ChatMessage

@Stable
class SessionUIState {
    var initialized by mutableStateOf(false)
    var userInput by mutableStateOf("")
    var showSessionList by mutableStateOf(true)
    var selectedSessionMetadata by mutableStateOf<ChatSessionMetadata?>(null)
    var showSettingsPanel by mutableStateOf(false)
    var chatHistory by mutableStateOf<List<ChatMessage>>(emptyList())
    
    val canSendMessage: Boolean
        get() = userInput.isNotBlank() && initialized

    fun clearUserInput() {
        userInput = ""
    }

    fun addMessageToHistory(message: ChatMessage) {
        chatHistory = chatHistory + message
    }

    fun updateChatHistory(messages: List<ChatMessage>) {
        chatHistory = messages
    }

    fun reset() {
        userInput = ""
        chatHistory = emptyList()
        selectedSessionMetadata = null
        showSessionList = true
        showSettingsPanel = false
    }
}

@Composable
fun rememberSessionUIState(): SessionUIState {
    return remember { SessionUIState() }
}