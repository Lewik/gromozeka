package com.gromozeka.presentation.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.application.service.ConversationEngineService
import com.gromozeka.application.service.MessageSquashService
import com.gromozeka.presentation.services.SoundNotificationService
import com.gromozeka.domain.model.AIProvider
import com.gromozeka.presentation.model.Settings
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.presentation.utils.ChatMessageSoundDetector
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTagDefinition
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.repository.ConversationDomainService
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.UUID

class TabViewModel(
    val conversationId: Conversation.Id,
    val projectPath: String,
    private val conversationEngineService: ConversationEngineService,
    private val conversationService: ConversationDomainService,
    private val messageSquashService: MessageSquashService,
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
            
            val thinkingMessageIds = messages
                .filter { message -> message.content.any { it is Conversation.Message.ContentItem.Thinking } }
                .map { it.id }
                .toSet()
            
            if (thinkingMessageIds.isNotEmpty()) {
                _uiState.update { currentState ->
                    currentState.copy(collapsedMessageIds = currentState.collapsedMessageIds + thinkingMessageIds)
                }
            }
            
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

    fun updateAgent(agent: Agent) {
        _uiState.update { currentState ->
            currentState.copy(agent = agent)
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

                conversationEngineService.streamMessage(conversationId, userMessage, currentState.agent)
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

                                val hasThinking = update.message.content.any { it is Conversation.Message.ContentItem.Thinking }
                                if (hasThinking) {
                                    _uiState.update { currentState ->
                                        if (update.message.id !in currentState.collapsedMessageIds) {
                                            currentState.copy(collapsedMessageIds = currentState.collapsedMessageIds + update.message.id)
                                        } else {
                                            currentState
                                        }
                                    }
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
            val wasSelected = messageId in selectedIds
            val newSelectedIds = if (wasSelected) {
                selectedIds - messageId
            } else {
                selectedIds + messageId
            }
            val action = !wasSelected
            
            currentState.copy(
                selectedMessageIds = newSelectedIds,
                lastToggledMessageId = messageId,
                lastToggleAction = action
            )
        }
    }
    
    fun toggleEditMode() {
        _uiState.update { currentState ->
            currentState.copy(editMode = !currentState.editMode)
        }
    }

    fun toggleMessageSelectionRange(messageId: Conversation.Message.Id, isShiftPressed: Boolean) {
        if (!isShiftPressed || _uiState.value.lastToggledMessageId == null) {
            toggleMessageSelection(messageId)
            return
        }

        val lastId = _uiState.value.lastToggledMessageId ?: return
        val allMessages = _allMessages.value
        
        val lastIndex = allMessages.indexOfFirst { it.id == lastId }
        val currentIndex = allMessages.indexOfFirst { it.id == messageId }
        
        if (lastIndex == -1 || currentIndex == -1) {
            toggleMessageSelection(messageId)
            return
        }

        val startIndex = minOf(lastIndex, currentIndex)
        val endIndex = maxOf(lastIndex, currentIndex)
        val rangeIds = allMessages.subList(startIndex, endIndex + 1).map { it.id }.toSet()

        _uiState.update { currentState ->
            val action = currentState.lastToggleAction ?: true
            
            val newSelectedIds = if (action) {
                currentState.selectedMessageIds + rangeIds
            } else {
                currentState.selectedMessageIds - rangeIds
            }
            
            currentState.copy(
                selectedMessageIds = newSelectedIds,
                lastToggledMessageId = messageId,
                lastToggleAction = action
            )
        }
    }

    fun toggleMessageCollapse(messageId: Conversation.Message.Id) {
        _uiState.update { currentState ->
            val collapsedIds = currentState.collapsedMessageIds
            val newCollapsedIds = if (messageId in collapsedIds) {
                collapsedIds - messageId
            } else {
                collapsedIds + messageId
            }
            currentState.copy(collapsedMessageIds = newCollapsedIds)
        }
    }

    fun clearMessageSelection() {
        _uiState.update { it.copy(selectedMessageIds = emptySet()) }
    }

    fun toggleSelectAll(allMessageIds: Set<Conversation.Message.Id>) {
        _uiState.update { currentState ->
            val allSelected = currentState.selectedMessageIds.size == allMessageIds.size && 
                             allMessageIds.isNotEmpty()
            if (allSelected) {
                currentState.copy(selectedMessageIds = emptySet())
            } else {
                currentState.copy(selectedMessageIds = allMessageIds)
            }
        }
    }

    fun toggleSelectUserMessages() {
        val filteredHistory = filteredMessages.value
        val userMessageIds = filteredHistory
            .filter { it.role == Conversation.Message.Role.USER }
            .map { it.id }
            .toSet()
        
        if (userMessageIds.isEmpty()) return
        
        _uiState.update { currentState ->
            val allSelected = userMessageIds.all { it in currentState.selectedMessageIds }
            if (allSelected) {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds - userMessageIds)
            } else {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds + userMessageIds)
            }
        }
    }

    fun toggleSelectAssistantMessages() {
        val filteredHistory = filteredMessages.value
        val assistantMessageIds = filteredHistory
            .filter { it.role == Conversation.Message.Role.ASSISTANT }
            .map { it.id }
            .toSet()
        
        if (assistantMessageIds.isEmpty()) return
        
        _uiState.update { currentState ->
            val allSelected = assistantMessageIds.all { it in currentState.selectedMessageIds }
            if (allSelected) {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds - assistantMessageIds)
            } else {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds + assistantMessageIds)
            }
        }
    }

    fun toggleSelectThinkingMessages() {
        val filteredHistory = filteredMessages.value
        val thinkingMessageIds = filteredHistory
            .filter { message -> message.content.any { it is Conversation.Message.ContentItem.Thinking } }
            .map { it.id }
            .toSet()
        
        if (thinkingMessageIds.isEmpty()) return
        
        _uiState.update { currentState ->
            val allSelected = thinkingMessageIds.all { it in currentState.selectedMessageIds }
            if (allSelected) {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds - thinkingMessageIds)
            } else {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds + thinkingMessageIds)
            }
        }
    }

    fun toggleSelectToolMessages() {
        val filteredHistory = filteredMessages.value
        val toolMessageIds = filteredHistory
            .filter { message -> message.content.any { it is Conversation.Message.ContentItem.ToolCall } }
            .map { it.id }
            .toSet()
        
        if (toolMessageIds.isEmpty()) return
        
        _uiState.update { currentState ->
            val allSelected = toolMessageIds.all { it in currentState.selectedMessageIds }
            if (allSelected) {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds - toolMessageIds)
            } else {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds + toolMessageIds)
            }
        }
    }

    fun toggleSelectPlainMessages() {
        val filteredHistory = filteredMessages.value
        val plainMessageIds = filteredHistory
            .filter { message -> 
                message.content.none { it is Conversation.Message.ContentItem.Thinking } &&
                message.content.none { it is Conversation.Message.ContentItem.ToolCall }
            }
            .map { it.id }
            .toSet()
        
        if (plainMessageIds.isEmpty()) return
        
        _uiState.update { currentState ->
            val allSelected = plainMessageIds.all { it in currentState.selectedMessageIds }
            if (allSelected) {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds - plainMessageIds)
            } else {
                currentState.copy(selectedMessageIds = currentState.selectedMessageIds + plainMessageIds)
            }
        }
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

    suspend fun distillSelectedMessages() {
        squashWithAI(SquashType.DISTILL)
    }

    suspend fun summarizeSelectedMessages() {
        squashWithAI(SquashType.SUMMARIZE)
    }

    private suspend fun squashWithAI(squashType: SquashType) {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.size < 2) {
            log.warn { "Need at least 2 messages to squash, got ${selectedIds.size}" }
            return
        }

        try {
            val conversation = conversationService.findById(conversationId)
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            val aiProvider = AIProvider.valueOf(conversation.aiProvider)
            val modelName = conversation.modelName

            log.info { "Starting AI squash: type=$squashType, provider=$aiProvider, model=$modelName" }

            val result = messageSquashService.squashWithAI(
                conversationId = conversationId,
                selectedIds = selectedIds.toList(),
                squashType = squashType,
                aiProvider = aiProvider,
                modelName = modelName,
                projectPath = projectPath
            )

            val squashedContent = listOf(
                Conversation.Message.ContentItem.UserMessage(result)
            )

            conversationService.squashMessages(
                conversationId,
                selectedIds.toList(),
                squashedContent
            )

            clearMessageSelection()
            loadMessages()

            log.debug { "AI squash completed: type=$squashType, result length=${result.length}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to squash messages with AI: $squashType" }
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
