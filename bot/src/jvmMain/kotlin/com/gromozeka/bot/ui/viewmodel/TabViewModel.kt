package com.gromozeka.bot.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.services.ConversationEngineService
import com.gromozeka.bot.services.SoundNotificationService
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.utils.ChatMessageSoundDetector
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.MessageTagDefinition
import com.gromozeka.shared.domain.TokenUsageStatistics
import com.gromozeka.shared.repository.TokenUsageStatisticsRepository
import com.gromozeka.shared.services.ConversationService
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import java.util.UUID

class TabViewModel(
    val conversationId: Conversation.Id,
    val projectPath: String,
    private val conversationEngineService: ConversationEngineService,
    private val conversationService: ConversationService,
    private val soundNotificationService: SoundNotificationService,
    private val settingsFlow: StateFlow<Settings>,
    private val scope: CoroutineScope,
    initialTabUiState: UIState.Tab,
    private val screenCaptureController: ScreenCaptureController,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
) {
    private val log = KLoggers.logger(this)

    private val _uiState = MutableStateFlow(initialTabUiState)
    val uiState: StateFlow<UIState.Tab> = _uiState.asStateFlow()

    var jsonToShow by mutableStateOf<String?>(null)

    private var currentRequestJob: kotlinx.coroutines.Job? = null

    companion object {
        private val ALL_MESSAGE_TAG_DEFINITIONS = listOf(
            MessageTagDefinition(
                controls = listOf(
                    MessageTagDefinition.Control(
                        data = Conversation.Message.Instruction.UserInstruction("thinking_off", "Off", "Обычный режим работы"),
                        includeInMessage = false
                    ),
                    MessageTagDefinition.Control(
                        data = Conversation.Message.Instruction.UserInstruction(
                            "thinking_ultrathink",
                            "Ultrathink",
                            "Режим глубокого анализа с пошаговыми рассуждениями и детальной проработкой"
                        ),
                        includeInMessage = true
                    )
                ),
                selectedByDefault = 1
            ),
            MessageTagDefinition(
                controls = listOf(
                    MessageTagDefinition.Control(
                        data = Conversation.Message.Instruction.UserInstruction(
                            "mode_readonly",
                            "Readonly",
                            "Режим readonly - никаких изменений кода или команд применяющих изменения"
                        ),
                        includeInMessage = true
                    ),
                    MessageTagDefinition.Control(
                        data = Conversation.Message.Instruction.UserInstruction("mode_writable", "Writable", "Разрешено исправление файлов"),
                        includeInMessage = true
                    )
                ),
                selectedByDefault = 0
            )
        )

        fun getDefaultEnabledTags(): Set<String> {
            return ALL_MESSAGE_TAG_DEFINITIONS.map { tagDefinition ->
                (tagDefinition.controls[tagDefinition.selectedByDefault].data as Conversation.Message.Instruction.UserInstruction).id
            }.toSet()
        }
    }

    val availableMessageTags = ALL_MESSAGE_TAG_DEFINITIONS

    val activeMessageTags: Set<String> get() = _uiState.value.activeMessageTags
    val userInput: String get() = _uiState.value.userInput
    val activeMessageTagsFlow: StateFlow<Set<String>> = _uiState.map { it.activeMessageTags }.stateIn(
        scope, SharingStarted.Lazily, initialTabUiState.activeMessageTags
    )

    private val _allMessages = MutableStateFlow<List<Conversation.Message>>(emptyList())
    val allMessages: StateFlow<List<Conversation.Message>> = _allMessages.asStateFlow()

    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()

    val pendingMessagesCount: StateFlow<Int> = flowOf(0).stateIn(scope, SharingStarted.Eagerly, 0)

    private val _tokenStats = MutableStateFlow<TokenUsageStatistics.ThreadTotals?>(null)
    val tokenStats: StateFlow<TokenUsageStatistics.ThreadTotals?> = _tokenStats.asStateFlow()

    init {
        _uiState.update { it.copy(isWaitingForResponse = false) }

        scope.launch {
            loadMessages()
            loadTokenStats()
        }
    }

    private suspend fun loadMessages() {
        try {
            val messages = conversationService.loadCurrentMessages(conversationId)
            _allMessages.value = messages
            log.debug { "Loaded ${messages.size} messages for conversation $conversationId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to load messages for conversation $conversationId" }
        }
    }

    private suspend fun loadTokenStats() {
        try {
            val conversation = conversationService.findById(conversationId)
            if (conversation != null) {
                val stats = tokenUsageStatisticsRepository.getThreadTotals(conversation.currentThread)
                _tokenStats.value = stats
                log.debug { "Loaded token stats for conversation $conversationId: $stats" }
            } else {
                log.warn { "Conversation not found: $conversationId" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load token stats for conversation $conversationId" }
        }
    }

    val filteredMessages: StateFlow<List<Conversation.Message>> = combine(
        allMessages,
        settingsFlow
    ) { messages, settings ->
        messages.filter { message ->
            val containsOnlyToolResults = message.content.isNotEmpty() &&
                    message.content.all { it is Conversation.Message.ContentItem.ToolResult }

            if (containsOnlyToolResults) {
                false
            } else if (settings.showSystemMessages) {
                true
            } else {
                message.role != Conversation.Message.Role.SYSTEM ||
                        message.content.any { content ->
                            content is Conversation.Message.ContentItem.System &&
                                    content.level == Conversation.Message.ContentItem.System.SystemLevel.ERROR
                        }
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val toolResultsMap: StateFlow<Map<String, Conversation.Message.ContentItem.ToolResult>> =
        allMessages
            .map { messages ->
                messages
                    .flatMap { message ->
                        message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                    }
                    .associateBy { it.toolUseId.value }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = emptyMap()
            )

    fun toggleMessageTag(messageTag: MessageTagDefinition, controlIndex: Int) {
        _uiState.update { currentState ->
            if (controlIndex >= 0 && controlIndex < messageTag.controls.size) {
                val selectedId = (messageTag.controls[controlIndex].data as Conversation.Message.Instruction.UserInstruction).id

                val allIdsInGroup = messageTag.controls.map { (it.data as Conversation.Message.Instruction.UserInstruction).id }.toSet()

                val isAlreadyActive = selectedId in currentState.activeMessageTags

                if (isAlreadyActive) {
                    currentState
                } else {
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

    suspend fun sendMessageToSession(
        message: String,
        additionalInstructions: List<Conversation.Message.Instruction> = emptyList(),
    ) {
        val currentState = _uiState.value

        val activeTagsData = availableMessageTags.mapNotNull { messageTag ->
            val activeControlIndex = messageTag.controls.indexOfFirst { control ->
                (control.data as Conversation.Message.Instruction.UserInstruction).id in currentState.activeMessageTags
            }

            val selectedControlIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault
            val selectedControl = messageTag.controls[selectedControlIndex]

            if (selectedControl.includeInMessage) {
                selectedControl.data
            } else null
        }

        val instructions = activeTagsData + additionalInstructions

        val userMessage = Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(message)),
            createdAt = Clock.System.now(),
            instructions = instructions
        )

        _allMessages.value += userMessage
        _isWaitingForResponse.value = true
        _uiState.update { it.copy(userInput = "", isWaitingForResponse = true) }

        currentRequestJob = scope.launch {
            try {
                log.debug { "Sending message to conversation $conversationId" }

                var lastMessage: Conversation.Message? = null

                conversationEngineService.streamMessage(conversationId, userMessage)
                    .collect { update ->
                        when (update) {
                            is ConversationEngineService.StreamUpdate.Chunk -> {
                                val messages = _allMessages.value.toMutableList()

                                val existingIndex = messages.indexOfFirst { it.id == update.message.id }

                                if (existingIndex != -1) {
                                    messages[existingIndex] = update.message
                                    log.debug { "Updated existing message ${update.message.id}" }
                                } else {
                                    messages.add(update.message)
                                    log.debug { "Added new message ${update.message.id}" }
                                }

                                _allMessages.value = messages
                                lastMessage = update.message
                            }
                            is ConversationEngineService.StreamUpdate.Error -> {
                                log.error(update.exception) { "Stream error" }
                                soundNotificationService.playErrorSound()
                            }
                        }
                    }

                if (lastMessage != null) {
                    if (ChatMessageSoundDetector.shouldPlayErrorSound(lastMessage)) {
                        soundNotificationService.playErrorSound()
                    } else if (ChatMessageSoundDetector.shouldPlayMessageSound(lastMessage)) {
                        soundNotificationService.playMessageSound()
                    }
                }

                soundNotificationService.playReadySound()
                loadTokenStats()
                log.debug { "Message sent successfully" }
            } catch (e: Exception) {
                log.error(e) { "Failed to send message" }
            } finally {
                _isWaitingForResponse.value = false
                _uiState.update { it.copy(isWaitingForResponse = false) }
                currentRequestJob = null
            }
        }
    }

    fun interrupt() {
        log.debug { "Interrupting current request for conversation $conversationId" }
        currentRequestJob?.cancel()
        currentRequestJob = null
        _isWaitingForResponse.value = false
        _uiState.update { it.copy(isWaitingForResponse = false) }
    }

    suspend fun captureAndAddToInput() {
        val filePath = screenCaptureController.captureWindow()
        if (filePath != null) {
            val currentInput = _uiState.value.userInput
            val newInput = if (currentInput.isBlank()) {
                filePath
            } else {
                "$currentInput $filePath"
            }
            _uiState.update { it.copy(userInput = newInput) }
        }
    }

    fun toggleMessageSelection(messageId: Conversation.Message.Id) {
        _uiState.update { currentState ->
            val selectedIds = currentState.selectedMessageIds
            val newSelectedIds = if (messageId in selectedIds) {
                selectedIds - messageId
            } else {
                selectedIds + messageId
            }
            currentState.copy(selectedMessageIds = newSelectedIds)
        }
    }

    fun clearMessageSelection() {
        _uiState.update { it.copy(selectedMessageIds = emptySet()) }
    }

    fun startEditMessage(messageId: Conversation.Message.Id) {
        val message = _allMessages.value.find { it.id == messageId }
        val text = message?.content
            ?.filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            ?.firstOrNull()?.text
            ?: message?.content
                ?.filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                ?.firstOrNull()?.structured?.fullText
            ?: ""

        _uiState.update {
            it.copy(
                editingMessageId = messageId,
                editingMessageText = text
            )
        }
    }

    fun updateEditingMessageText(text: String) {
        _uiState.update { it.copy(editingMessageText = text) }
    }

    fun cancelEditMessage() {
        _uiState.update {
            it.copy(
                editingMessageId = null,
                editingMessageText = ""
            )
        }
    }

    suspend fun confirmEditMessage() {
        val editingId = _uiState.value.editingMessageId ?: return
        val newText = _uiState.value.editingMessageText

        try {
            val newContent = listOf(
                Conversation.Message.ContentItem.UserMessage(newText)
            )

            conversationService.editMessage(conversationId, editingId, newContent)

            cancelEditMessage()
            loadMessages()

            log.debug { "Message $editingId edited successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to edit message $editingId" }
        }
    }

    suspend fun deleteMessage(messageId: Conversation.Message.Id) {
        try {
            conversationService.deleteMessage(conversationId, messageId)
            loadMessages()
            log.debug { "Message $messageId deleted successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete message $messageId" }
        }
    }

    suspend fun squashSelectedMessages() {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.size < 2) {
            log.warn { "Need at least 2 messages to squash, got ${selectedIds.size}" }
            return
        }

        try {
            val selectedMessages = _allMessages.value.filter { it.id in selectedIds }

            val combinedText = selectedMessages.joinToString("\n\n") { message ->
                message.content
                    .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                    .firstOrNull()?.text
                    ?: message.content
                        .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                        .firstOrNull()?.structured?.fullText
                    ?: ""
            }

            val squashedContent = listOf(
                Conversation.Message.ContentItem.UserMessage(combinedText)
            )

            conversationService.squashMessages(
                conversationId,
                selectedIds.toList(),
                squashedContent
            )

            clearMessageSelection()
            loadMessages()

            log.debug { "Squashed ${selectedIds.size} messages successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to squash messages" }
        }
    }

    suspend fun deleteSelectedMessages() {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.isEmpty()) {
            log.warn { "No messages selected for deletion" }
            return
        }

        try {
            conversationService.deleteMessages(
                conversationId,
                selectedIds.toList()
            )

            clearMessageSelection()
            loadMessages()

            log.debug { "Deleted ${selectedIds.size} message(s) successfully" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete messages" }
        }
    }
}
