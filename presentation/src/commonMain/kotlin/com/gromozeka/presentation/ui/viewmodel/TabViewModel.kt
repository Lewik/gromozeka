package com.gromozeka.presentation.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.presentation.services.ScreenCaptureController
import com.gromozeka.presentation.services.SoundNotificationPlayer
import com.gromozeka.domain.model.Settings
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.presentation.utils.ChatMessageSoundDetector
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTagDefinition
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeCommand
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class TabViewModel(
    val conversationId: Conversation.Id,
    val projectPath: String,
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationService: ConversationDomainService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val soundNotificationService: SoundNotificationPlayer,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
    initialTabUiState: UIState.Tab,
    private val screenCaptureController: ScreenCaptureController,
    private val tokenStatsService: ConversationTokenStatsService,
) {
    private val log = KLoggers.logger(this)
    private val settingsFlow: StateFlow<Settings> = settingsService.settingsFlow

    private val _uiState = MutableStateFlow(initialTabUiState)
    val uiState: StateFlow<UIState.Tab> = _uiState.asStateFlow()

    var jsonToShow by mutableStateOf<String?>(null)

    private var currentRequestJob: kotlinx.coroutines.Job? = null
    private var lastRuntimeMessage: Conversation.Message? = null

    companion object {
        private const val MID_TURN_STEER_INSTRUCTION_ID = "mid_turn_steer"

        private val MID_TURN_STEER_INSTRUCTION = Conversation.Message.Instruction.UserInstruction(
            id = MID_TURN_STEER_INSTRUCTION_ID,
            title = "Live steering update",
            description = "This user message was submitted while the assistant was already working. " +
                "Treat it as additional steering for the active turn and incorporate it at the next safe boundary, " +
                "usually after the current tool result. Do not restart or discard completed work unless the user explicitly asks."
        )

        private val ALL_MESSAGE_TAG_DEFINITIONS = listOf(
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
    private val _executionPauseRequested = MutableStateFlow(false)
    val executionPauseRequested: StateFlow<Boolean> = _executionPauseRequested.asStateFlow()

    private val _pendingMessages = MutableStateFlow<List<PendingUserMessage>>(emptyList())
    val pendingMessages: StateFlow<List<PendingUserMessage>> = _pendingMessages.asStateFlow()
    val pendingMessagesCount: StateFlow<Int> = pendingMessages
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)
    private val _tokenStats = MutableStateFlow<TokenUsageStatistics.ThreadTotals?>(null)
    val tokenStats: StateFlow<TokenUsageStatistics.ThreadTotals?> = _tokenStats.asStateFlow()

    private val _memoryActionItemsRefreshKey = MutableStateFlow(0)
    val memoryActionItemsRefreshKey: StateFlow<Int> = _memoryActionItemsRefreshKey.asStateFlow()

    init {
        _uiState.update { it.copy(isWaitingForResponse = false) }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeRuntimeEvents()
        }

        scope.launch {
            loadMessages()
            loadTokenStats()
        }
    }

    private suspend fun loadMessages() {
        try {
            val messages = conversationService.loadCurrentMessages(conversationId)
            _allMessages.value = messages
            collapseVisibleThinkingBlocks(messages, onlyWhenNoManualState = false)

            log.debug { "Loaded ${messages.size} messages for conversation $conversationId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to load messages for conversation $conversationId" }
        }
    }

    private suspend fun observeRuntimeEvents() {
        try {
            conversationRuntimeService.observeConversation(conversationId).collect { event ->
                handleRuntimeEvent(event)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error(error) { "Conversation runtime observation failed for $conversationId" }
            _isWaitingForResponse.value = false
            _executionPauseRequested.value = false
            _uiState.update { it.copy(isWaitingForResponse = false) }
        }
    }

    private suspend fun handleRuntimeEvent(event: ConversationRuntimeEvent) {
        when (event) {
            is ConversationRuntimeEvent.SnapshotUpdated -> applyRuntimeSnapshot(event.snapshot)
            is ConversationRuntimeEvent.MessageEmitted -> {
                _isWaitingForResponse.value = true
                _uiState.update { it.copy(isWaitingForResponse = true) }
                upsertRuntimeMessage(event.message)
            }
            is ConversationRuntimeEvent.ExecutionCompleted -> finishRuntimeExecution()
            is ConversationRuntimeEvent.ExecutionFailed -> {
                log.error { "Conversation runtime failed: ${event.type ?: "unknown"} ${event.message}" }
                soundNotificationService.playErrorSound()
                finishRuntimeExecution(playCompletionSound = false)
            }
        }
    }

    private fun applyRuntimeSnapshot(snapshot: ConversationRuntimeSnapshot) {
        _pendingMessages.value = snapshot.pendingCommands.map { it.toPendingUserMessage() }
        val status = snapshot.state?.status
        val isRuntimeActive = snapshot.state != null || snapshot.pendingCommands.isNotEmpty()
        val isPaused = status == ConversationExecutionState.Status.PAUSED ||
            status == ConversationExecutionState.Status.PAUSE_REQUESTED
        _isWaitingForResponse.value = isRuntimeActive
        _executionPauseRequested.value = isPaused
        _uiState.update { it.copy(isWaitingForResponse = isRuntimeActive) }
    }

    private suspend fun upsertRuntimeMessage(message: Conversation.Message) {
        _pendingMessages.update { pendingMessages ->
            pendingMessages.filterNot { it.userMessage.id == message.id }
        }

        val messages = _allMessages.value.toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == message.id }

        if (existingIndex != -1) {
            messages[existingIndex] = message
            log.debug { "Updated existing message ${message.id}" }
        } else {
            messages.add(message)
            log.debug { "Added new message ${message.id}" }
        }

        collapseVisibleThinkingBlocks(listOf(message), onlyWhenNoManualState = true)
        _allMessages.value = messages
        lastRuntimeMessage = message

        if (message.error != null) {
            log.error { "Stream error: ${message.error}" }
            log.error { "Message with error: id=${message.id}, role=${message.role}, content.size=${message.content.size}" }
            soundNotificationService.playErrorSound()
        }
    }

    private fun collapseVisibleThinkingBlocks(
        messages: List<Conversation.Message>,
        onlyWhenNoManualState: Boolean,
    ) {
        val collapsedItems = messages.mapNotNull { message ->
            val thinkingIndices = message.content.mapIndexedNotNull { index, item ->
                if ((item as? Conversation.Message.ContentItem.Thinking)?.isVisible == true) index else null
            }.toSet()

            if (thinkingIndices.isEmpty()) null else message.id to thinkingIndices
        }.toMap()

        if (collapsedItems.isEmpty()) {
            return
        }

        _uiState.update { currentState ->
            val updated = collapsedItems.entries.fold(currentState.collapsedContentItems) { currentCollapsed, (messageId, indices) ->
                if (onlyWhenNoManualState && !currentCollapsed[messageId].isNullOrEmpty()) {
                    currentCollapsed
                } else {
                    currentCollapsed + (messageId to indices)
                }
            }
            currentState.copy(collapsedContentItems = updated)
        }
    }

    private suspend fun finishRuntimeExecution(playCompletionSound: Boolean = true) {
        val lastMessage = lastRuntimeMessage
        if (playCompletionSound && lastMessage != null) {
            if (ChatMessageSoundDetector.shouldPlayErrorSound(lastMessage)) {
                soundNotificationService.playErrorSound()
            } else if (ChatMessageSoundDetector.shouldPlayMessageSound(lastMessage)) {
                soundNotificationService.playMessageSound()
            }
        }

        soundNotificationService.playReadySound()
        loadTokenStats()
        notifyMemoryActionItemsMayHaveChanged()
        log.debug { "Conversation runtime completed" }
        currentRequestJob = null
        lastRuntimeMessage = null
        _isWaitingForResponse.value = false
        _executionPauseRequested.value = false
        _uiState.update { it.copy(isWaitingForResponse = false) }
    }

    private suspend fun loadTokenStats() {
        try {
            val stats = tokenStatsService.getTokenStats(conversationId)
            _tokenStats.value = stats
            log.debug { "Loaded token stats for conversation $conversationId: $stats" }
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
            } else if (settings.userDeviceSettings.showSystemMessages) {
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

    fun updateAgent(agent: AgentDefinition) {
        _uiState.update { currentState ->
            currentState.copy(agent = agent)
        }
    }

    suspend fun sendMessageToSession(
        message: String,
        additionalInstructions: List<Conversation.Message.Instruction> = emptyList(),
    ) {
        if (message.isBlank()) {
            return
        }

        val queuedMessage = createPendingUserMessage(message, additionalInstructions)

        if (currentRequestJob?.isActive == true || _isWaitingForResponse.value) {
            if (submitPendingMessage(queuedMessage)) {
                _pendingMessages.update { it + queuedMessage }
                _uiState.update { it.copy(userInput = "") }
                log.info {
                    "Queued user message for conversation $conversationId because previous request is still running"
                }
            } else {
                log.warn { "Runtime queue rejected end-of-turn message for conversation $conversationId" }
            }
            return
        }

        sendPendingMessageToSession(queuedMessage)
    }

    fun cancelPendingMessage(messageId: String) {
        val message = _pendingMessages.value.firstOrNull { it.id == messageId }
        _pendingMessages.update { messages ->
            messages.filterNot { it.id == messageId }
        }
        message?.let { pendingMessage ->
            scope.launch {
                pendingMessage.cancelRuntimeQueueIfNeeded()
            }
        }
    }

    fun editPendingMessage(messageId: String) {
        val message = _pendingMessages.value.firstOrNull { it.id == messageId } ?: return
        _pendingMessages.update { messages ->
            messages.filterNot { it.id == messageId }
        }
        scope.launch {
            message.cancelRuntimeQueueIfNeeded()
        }
        _uiState.update { it.copy(userInput = message.text) }
    }

    fun sendPendingMessageInCurrentTurn(messageId: String) {
        val pendingMessage = _pendingMessages.value.firstOrNull { it.id == messageId } ?: return
        if (pendingMessage.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT) {
            return
        }

        val steeredMessage = pendingMessage.withMidTurnSteerInstruction()
        _pendingMessages.update { messages ->
            messages.map { message ->
                if (message.id == messageId) steeredMessage else message
            }
        }
        scope.launch {
            val runtimeAccepted = runCatching {
                conversationRuntimeService.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = steeredMessage.userMessage,
                    agent = steeredMessage.agent,
                    placement = QueuedMessagePlacement.AFTER_TOOL_RESULT
                )
            }.onFailure { error ->
                log.warn(error) {
                    "Runtime queue rejected live steering message for conversation $conversationId: ${error.message}"
                }
            }.getOrDefault(false)

            if (!runtimeAccepted) {
                _pendingMessages.update { messages ->
                    messages.map { message ->
                        if (message.id == messageId && message.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT) {
                            pendingMessage
                        } else {
                            message
                        }
                    }
                }
                return@launch
            }
        }
    }

    private fun createPendingUserMessage(
        message: String,
        additionalInstructions: List<Conversation.Message.Instruction>,
    ): PendingUserMessage {
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
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(message)),
            createdAt = Clock.System.now(),
            instructions = instructions
        )

        return PendingUserMessage(
            userMessage = userMessage,
            agent = currentState.agent,
            placement = QueuedMessagePlacement.END_OF_TURN,
        )
    }

    private fun sendPendingMessageToSession(
        pendingMessage: PendingUserMessage,
    ) {
        if (currentRequestJob?.isActive == true || _isWaitingForResponse.value) {
            scope.launch {
                if (submitPendingMessage(pendingMessage)) {
                    _pendingMessages.update { it + pendingMessage }
                }
            }
            return
        }

        val userMessage = pendingMessage.userMessage

        _allMessages.value += userMessage
        lastRuntimeMessage = null
        _isWaitingForResponse.value = true
        _executionPauseRequested.value = false
        _uiState.update { it.copy(userInput = "", isWaitingForResponse = true) }

        currentRequestJob = scope.launch {
            try {
                log.debug { "Submitting message to conversation $conversationId" }
                val accepted = conversationRuntimeService.submitMessage(conversationId, userMessage, pendingMessage.agent)
                if (!accepted) {
                    _allMessages.update { messages ->
                        messages.filterNot { it.id == userMessage.id }
                    }
                    _isWaitingForResponse.value = false
                    _uiState.update { it.copy(userInput = pendingMessage.text, isWaitingForResponse = false) }
                    log.warn { "Runtime rejected submitted message for conversation $conversationId" }
                }
            } catch (e: Exception) {
                _allMessages.update { messages ->
                    messages.filterNot { it.id == userMessage.id }
                }
                _isWaitingForResponse.value = false
                _uiState.update { it.copy(userInput = pendingMessage.text, isWaitingForResponse = false) }
                log.error(e) { "Failed to submit message" }
            } finally {
                currentRequestJob = null
            }
        }
    }

    private suspend fun submitPendingMessage(pendingMessage: PendingUserMessage): Boolean =
        runCatching {
            conversationRuntimeService.submitMessage(
                conversationId = conversationId,
                userMessage = pendingMessage.userMessage,
                agent = pendingMessage.agent
            )
        }.onFailure { error ->
            log.warn(error) {
                "Runtime queue request failed for conversation $conversationId: ${error.message}"
            }
        }.getOrDefault(false)

    private suspend fun PendingUserMessage.cancelRuntimeQueueIfNeeded() {
        conversationRuntimeService.cancelQueuedMessage(conversationId, userMessage.id)
    }

    private fun PendingUserMessage.withMidTurnSteerInstruction(): PendingUserMessage {
        val instructions = userMessage.instructions
            .filterNot { instruction ->
                instruction is Conversation.Message.Instruction.UserInstruction &&
                    instruction.id == MID_TURN_STEER_INSTRUCTION_ID
            } + MID_TURN_STEER_INSTRUCTION

        return copy(
            userMessage = userMessage.copy(instructions = instructions),
            placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
        )
    }

    fun notifyMemoryActionItemsMayHaveChanged() {
        _memoryActionItemsRefreshKey.update { it + 1 }
    }

    fun interrupt() {
        log.debug { "Interrupting current request for conversation $conversationId" }
        _pendingMessages.value = emptyList()
        _executionPauseRequested.value = false
        scope.launch {
            runCatching {
                conversationRuntimeService.controlExecution(conversationId, ConversationRuntimeControlAction.INTERRUPT)
            }.onFailure { error ->
                log.warn(error) { "Runtime interrupt request failed for conversation $conversationId: ${error.message}" }
            }
        }
        currentRequestJob?.cancel()
        currentRequestJob = null
        lastRuntimeMessage = null
        _isWaitingForResponse.value = false
        _uiState.update { it.copy(isWaitingForResponse = false) }
    }

    fun pauseExecution() {
        scope.launch {
            val accepted = runCatching {
                conversationRuntimeService.controlExecution(conversationId, ConversationRuntimeControlAction.PAUSE)
            }.onFailure { error ->
                log.warn(error) { "Runtime pause request failed for conversation $conversationId: ${error.message}" }
            }.getOrDefault(false)
            if (accepted) {
                _executionPauseRequested.value = true
            }
        }
    }

    fun resumeExecution() {
        scope.launch {
            val accepted = runCatching {
                conversationRuntimeService.controlExecution(conversationId, ConversationRuntimeControlAction.RESUME)
            }.onFailure { error ->
                log.warn(error) { "Runtime resume request failed for conversation $conversationId: ${error.message}" }
            }.getOrDefault(false)
            if (accepted) {
                _executionPauseRequested.value = false
            }
        }
    }

    fun stopExecution() {
        scope.launch {
            val accepted = runCatching {
                conversationRuntimeService.controlExecution(conversationId, ConversationRuntimeControlAction.STOP)
            }.onFailure { error ->
                log.warn(error) { "Runtime stop request failed for conversation $conversationId: ${error.message}" }
            }.getOrDefault(false)
            if (accepted) {
                _pendingMessages.value = emptyList()
                _executionPauseRequested.value = false
                lastRuntimeMessage = null
            }
        }
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

    fun toggleContentItemCollapse(messageId: Conversation.Message.Id, contentItemIndex: Int) {
        _uiState.update { currentState ->
            val currentCollapsed = currentState.collapsedContentItems[messageId] ?: emptySet()
            val newCollapsed = if (contentItemIndex in currentCollapsed) {
                currentCollapsed - contentItemIndex
            } else {
                currentCollapsed + contentItemIndex
            }
            
            val newCollapsedContentItems = if (newCollapsed.isEmpty()) {
                currentState.collapsedContentItems - messageId
            } else {
                currentState.collapsedContentItems + (messageId to newCollapsed)
            }
            
            currentState.copy(collapsedContentItems = newCollapsedContentItems)
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
            .filter { message ->
                message.content.any { (it as? Conversation.Message.ContentItem.Thinking)?.isVisible == true }
            }
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
                message.content.none { (it as? Conversation.Message.ContentItem.Thinking)?.isVisible == true } &&
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
            conversationService.deleteMessages(conversationId, listOf(messageId))
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
            val runtimeSelection = settingsService.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MESSAGE_SQUASH)

            log.info { "Starting AI squash: type=$squashType, runtimeSelection=${runtimeSelection.modelConfigurationId.value}" }

            val result = messageSquashGenerationService.squashWithAI(
                conversationId = conversationId,
                selectedIds = selectedIds.toList(),
                squashType = squashType,
                runtimeSelection = runtimeSelection,
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

data class PendingUserMessage(
    val userMessage: Conversation.Message,
    val agent: AgentDefinition,
    val placement: QueuedMessagePlacement,
) {
    val id: String get() = userMessage.id.value

    val text: String
        get() = userMessage.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }
}

private fun ConversationRuntimeCommand.toPendingUserMessage(): PendingUserMessage =
    PendingUserMessage(
        userMessage = userMessage,
        agent = agent,
        placement = placement,
    )
