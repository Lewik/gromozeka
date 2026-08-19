package com.gromozeka.presentation.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.client.ArtifactTransferService
import com.gromozeka.presentation.services.AttachmentAcquisitionController
import com.gromozeka.domain.model.Settings
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.MessageInstructionTextShortcut
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.WorkspaceContextReference
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class TabViewModel(
    val conversationId: Conversation.Id,
    val projectId: Project.Id,
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationService: ConversationDomainService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val settingsService: SettingsService,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val scope: CoroutineScope,
    initialTabUiState: UIState.Tab,
    private val attachmentAcquisitionController: AttachmentAcquisitionController,
    private val artifactTransferService: ArtifactTransferService,
    private val tokenStatsService: ConversationTokenStatsService,
    private val messageInputClientPlatform: MessageInputContext.ClientPlatform,
) {
    private val log = KLoggers.logger(this)
    private val settingsFlow: StateFlow<Settings> = settingsService.settingsFlow
    private val artifactUploadMutex = Mutex()
    private val artifactContentCacheMutex = Mutex()
    private val messageSquashMutex = Mutex()
    private val artifactContentCache = linkedMapOf<com.gromozeka.domain.model.Artifact.Id, ByteArray>()
    private var artifactContentCacheBytes = 0

    private val _uiState = MutableStateFlow(initialTabUiState)
    val uiState: StateFlow<UIState.Tab> = _uiState.asStateFlow()

    var jsonToShow by mutableStateOf<String?>(null)

    private var currentRequestJob: kotlinx.coroutines.Job? = null
    private var lastRuntimeSnapshotRevision = -1L
    private var claimedUserInput: String? = null
    private val textMessageInputContext = MessageInputContext(
        modality = MessageInputContext.Modality.TEXT,
        source = MessageInputContext.Source.CHAT_INPUT,
        clientPlatform = messageInputClientPlatform,
        reliability = MessageInputContext.Reliability.NORMAL,
    )

    companion object {
        private const val MID_TURN_STEER_INSTRUCTION_ID = "mid_turn_steer"
        private const val MAX_ARTIFACT_CONTENT_CACHE_BYTES = 32 * 1024 * 1024

        private val MID_TURN_STEER_INSTRUCTION = Conversation.Message.Instruction.UserInstruction(
            id = MID_TURN_STEER_INSTRUCTION_ID,
            title = "Live steering update",
            description = "This user message was submitted while the assistant was already working. " +
                "Treat it as additional steering for the active turn and incorporate it at the next safe boundary, " +
                "usually after the current tool result. Do not restart or discard completed work unless the user explicitly asks."
        )

    }

    val messageInstructionGroups: List<MessageInstructionGroup>
        get() = settingsFlow.value.userProfile.messageInstructionGroups

    val activeMessageInstructionIds: Set<String> get() = _uiState.value.activeMessageInstructionIds
    val userInput: String get() = _uiState.value.userInput
    val attachmentCapabilities get() = attachmentAcquisitionController.capabilities
    val activeMessageInstructionIdsFlow: StateFlow<Set<String>> = _uiState.map { it.activeMessageInstructionIds }.stateIn(
        scope, SharingStarted.Lazily, initialTabUiState.activeMessageInstructionIds
    )

    private val _allMessages = MutableStateFlow<List<Conversation.Message>>(emptyList())
    val allMessages: StateFlow<List<Conversation.Message>> = _allMessages.asStateFlow()

    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()
    private val _suggestedRepliesOverride = MutableStateFlow<SuggestedRepliesOverride?>(null)
    val suggestedRepliesOverride: StateFlow<SuggestedRepliesOverride?> = _suggestedRepliesOverride.asStateFlow()
    private val _suggestedRepliesRegeneratingFor = MutableStateFlow<Conversation.Message.Id?>(null)
    val suggestedRepliesRegeneratingFor: StateFlow<Conversation.Message.Id?> =
        _suggestedRepliesRegeneratingFor.asStateFlow()
    private val _executionPauseRequested = MutableStateFlow(false)
    val executionPauseRequested: StateFlow<Boolean> = _executionPauseRequested.asStateFlow()

    private val _pendingMessages = MutableStateFlow<List<PendingUserMessage>>(emptyList())
    val pendingMessages: StateFlow<List<PendingUserMessage>> = _pendingMessages.asStateFlow()
    private val _activeToolExecutions = MutableStateFlow<List<ConversationRuntimeToolExecution>>(emptyList())
    val activeToolExecutions: StateFlow<List<ConversationRuntimeToolExecution>> = _activeToolExecutions.asStateFlow()
    private val _runtimeTrace = MutableStateFlow<List<ConversationRuntimeTraceEntry>>(emptyList())
    val runtimeTrace: StateFlow<List<ConversationRuntimeTraceEntry>> = _runtimeTrace.asStateFlow()
    private val _runtimeSnapshot = MutableStateFlow<ConversationRuntimeSnapshot?>(null)
    val runtimeSnapshot: StateFlow<ConversationRuntimeSnapshot?> = _runtimeSnapshot.asStateFlow()
    private val _activeGeneration = MutableStateFlow<ActiveGenerationSnapshot?>(null)
    val activeGeneration: StateFlow<ActiveGenerationSnapshot?> = _activeGeneration.asStateFlow()
    val pendingMessagesCount: StateFlow<Int> = pendingMessages
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    data class SuggestedRepliesOverride(
        val sourceMessageId: Conversation.Message.Id,
        val values: List<String>,
    )

    fun regenerateSuggestedReplies(sourceMessageId: Conversation.Message.Id) {
        if (_suggestedRepliesRegeneratingFor.value != null) return
        scope.launch {
            _suggestedRepliesRegeneratingFor.value = sourceMessageId
            try {
                val replies = conversationService.regenerateSuggestedReplies(
                    conversationId = conversationId,
                    sourceMessageId = sourceMessageId,
                )
                _suggestedRepliesOverride.value = SuggestedRepliesOverride(sourceMessageId, replies)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.error(error) {
                    "Failed to regenerate suggested replies: conversation=${conversationId.value} source=${sourceMessageId.value}"
                }
            } finally {
                _suggestedRepliesRegeneratingFor.value = null
            }
        }
    }
    private val _tokenStats = MutableStateFlow<TokenUsageStatistics.ThreadTotals?>(null)
    val tokenStats: StateFlow<TokenUsageStatistics.ThreadTotals?> = _tokenStats.asStateFlow()
    private val _messageSquashState = MutableStateFlow<MessageSquashUiState>(MessageSquashUiState.Idle)
    val messageSquashState: StateFlow<MessageSquashUiState> = _messageSquashState.asStateFlow()

    private val _memoryActionItemsRefreshKey = MutableStateFlow(0)
    val memoryActionItemsRefreshKey: StateFlow<Int> = _memoryActionItemsRefreshKey.asStateFlow()

    init {
        _uiState.update { it.copy(isWaitingForResponse = false) }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeRuntimeEvents()
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeActiveGeneration()
        }

        scope.launch {
            loadMessages(preserveRuntimeMessages = true)
            loadTokenStats()
        }
    }

    private suspend fun loadMessages(preserveRuntimeMessages: Boolean = false) {
        try {
            val messages = conversationService.loadCurrentMessages(conversationId)
            if (preserveRuntimeMessages) {
                mergeLoadedMessages(messages)
            } else {
                _allMessages.value = messages
            }
            collapseVisibleThinkingBlocks(messages, onlyWhenNoManualState = false)

            log.debug { "Loaded ${messages.size} messages for conversation $conversationId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to load messages for conversation $conversationId" }
        }
    }

    private fun mergeLoadedMessages(loadedMessages: List<Conversation.Message>) {
        _allMessages.update { currentMessages ->
            val loadedIds = loadedMessages.map { it.id }.toSet()
            val runtimeOnlyMessages = currentMessages.filterNot { it.id in loadedIds }

            (loadedMessages + runtimeOnlyMessages)
                .sortedWith(compareBy<Conversation.Message> { it.createdAt }.thenBy { it.id.value })
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

    private suspend fun observeActiveGeneration() {
        try {
            conversationRuntimeService.observeActiveGeneration(conversationId).collect {
                _activeGeneration.value = it
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _activeGeneration.value = null
            log.warn(error) { "Active generation observation failed for $conversationId" }
        }
    }

    private suspend fun handleRuntimeEvent(event: ConversationRuntimeEvent) {
        when (event) {
            is ConversationRuntimeEvent.SnapshotUpdated -> applyRuntimeSnapshot(event.snapshot)
            is ConversationRuntimeEvent.ReplayCompleted -> Unit
            is ConversationRuntimeEvent.MessageEmitted -> {
                _isWaitingForResponse.value = true
                _uiState.update { it.copy(isWaitingForResponse = true) }
                upsertRuntimeMessage(event.message)
            }
            is ConversationRuntimeEvent.ExecutionCompleted -> finishRuntimeExecution()
            is ConversationRuntimeEvent.ExecutionFailed -> {
                log.error { "Conversation runtime failed: ${event.failureType ?: "unknown"} ${event.message}" }
                finishRuntimeExecution()
            }
        }
    }

    private fun applyRuntimeSnapshot(snapshot: ConversationRuntimeSnapshot) {
        if (snapshot.revision < lastRuntimeSnapshotRevision) {
            log.warn {
                "Ignoring stale runtime snapshot: conversation=${conversationId.value} " +
                    "revision=${snapshot.revision} last=$lastRuntimeSnapshotRevision"
            }
            return
        }
        lastRuntimeSnapshotRevision = snapshot.revision

        _pendingMessages.value = snapshot.pendingTasks.mapNotNull { it.toPendingUserMessageOrNull() }
        _activeToolExecutions.value = snapshot.toolExecutions
        _runtimeTrace.value = snapshot.trace
        _runtimeSnapshot.value = snapshot
        val controlState = snapshot.state?.controlState
        val isRuntimeActive = snapshot.state != null || snapshot.pendingTasks.isNotEmpty()
        val isPaused = controlState == ConversationExecutionState.ControlState.PAUSED ||
            controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED
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
        if (message.error != null) {
            log.error { "Stream error: ${message.error}" }
            log.error { "Message with error: id=${message.id}, role=${message.role}, content.size=${message.content.size}" }
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

    private suspend fun finishRuntimeExecution() {
        loadMessages()
        loadTokenStats()
        notifyMemoryActionItemsMayHaveChanged()
        log.debug { "Conversation runtime completed" }
        currentRequestJob = null
        _activeToolExecutions.value = emptyList()
    }

    private suspend fun loadTokenStats() {
        try {
            val stats = tokenStatsService.getTokenStats(conversationId)
            _tokenStats.value = stats
            log.debug { "Loaded token stats for conversation $conversationId: $stats" }
        } catch (error: CancellationException) {
            throw error
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

    fun selectMessageInstruction(group: MessageInstructionGroup, controlIndex: Int) {
        _uiState.update { currentState -> currentState.withSelectedMessageInstruction(group, controlIndex) }
    }

    fun updateUserInput(input: String) {
        val claimedInput = claimedUserInput
        if (claimedInput != null) {
            if (input.isBlank() || input.trimEnd() == claimedInput.trimEnd()) {
                return
            }
            claimedUserInput = null
        }
        val profile = settingsFlow.value.userProfile
        val shortcut = MessageInstructionTextShortcut.consume(
            input = input,
            settings = profile.messageInstructionTextShortcuts,
            groups = profile.messageInstructionGroups,
        )
        _uiState.update { currentState ->
            val selectedState = shortcut?.let { match ->
                currentState.withSelectedMessageInstruction(match.group, match.controlIndex)
            } ?: currentState
            val updatedInput = shortcut?.remainingInput ?: input
            selectedState.copy(
                userInput = updatedInput,
                composerMessageInputContext = selectedState.composerMessageInputContext
                    .takeUnless { updatedInput.isBlank() },
            )
        }
    }

    fun appendUserInput(
        input: String,
        messageInputContext: MessageInputContext? = null,
    ) {
        val appendedText = input.trim()
        if (appendedText.isEmpty()) return

        claimedUserInput = null
        _uiState.update { currentState ->
            currentState.copy(
                userInput = appendComposerText(currentState.userInput, appendedText),
                composerMessageInputContext = messageInputContext ?: currentState.composerMessageInputContext,
            )
        }
    }

    fun updateAgent(agent: AgentDefinition) {
        scope.launch {
            val updatedConversation = conversationService.updateAgentDefinition(conversationId, agent.id)
                ?: error("Conversation not found: ${conversationId.value}")
            _uiState.update { currentState ->
                currentState.copy(agent = agent)
            }
            log.info {
                "Updated conversation agent: conversation=${conversationId.value} " +
                    "agent=${updatedConversation.agentDefinitionId.value}"
            }
        }
    }

    suspend fun sendMessageToSession(
        message: String,
        additionalInstructions: List<Conversation.Message.Instruction> = emptyList(),
        messageInputContext: MessageInputContext? = null,
    ) {
        if (message.isBlank()) {
            return
        }

        val queuedMessage = createPendingUserMessage(
            message = message,
            additionalInstructions = additionalInstructions,
            messageInputContext = messageInputContext,
        )
        sendPendingUserMessage(queuedMessage)
    }

    suspend fun submitUserInputToSession() {
        val pendingMessage = claimUserInput() ?: return
        try {
            sendPendingUserMessage(pendingMessage, restoreComposerOnQueueRejection = true)
        } finally {
            if (claimedUserInput == pendingMessage.text) {
                claimedUserInput = null
            }
        }
    }

    private suspend fun sendPendingUserMessage(
        queuedMessage: PendingUserMessage,
        restoreComposerOnQueueRejection: Boolean = false,
    ) {
        if (currentRequestJob?.isActive == true || _isWaitingForResponse.value) {
            if (submitPendingMessage(queuedMessage)) {
                showPendingMessage(queuedMessage)
                _uiState.update {
                    it.copy(
                        userInput = "",
                        composerArtifacts = emptyList(),
                        composerMessageInputContext = null,
                        workspaceContextReferences = emptyList(),
                    )
                }
                log.info {
                    "Queued user message for conversation $conversationId because previous request is still running"
                }
            } else {
                if (restoreComposerOnQueueRejection) {
                    restoreComposerMessage(queuedMessage)
                }
                log.warn { "Runtime queue rejected end-of-turn message for conversation $conversationId" }
            }
            return
        }

        sendPendingMessageToSession(queuedMessage)
    }

    private fun claimUserInput(): PendingUserMessage? {
        while (true) {
            val currentState = _uiState.value
            if (currentState.userInput.isBlank() && currentState.composerArtifacts.isEmpty()) return null

            val pendingMessage = createPendingUserMessage(
                message = currentState.userInput,
                additionalInstructions = emptyList(),
                messageInputContext = currentState.composerMessageInputContext ?: textMessageInputContext,
                currentState = currentState,
            )
            val clearedState = currentState.copy(
                userInput = "",
                composerArtifacts = emptyList(),
                composerMessageInputContext = null,
                workspaceContextReferences = emptyList(),
            )
            if (_uiState.compareAndSet(currentState, clearedState)) {
                claimedUserInput = currentState.userInput
                return pendingMessage
            }
        }
    }

    private fun restoreComposerMessage(pendingMessage: PendingUserMessage) {
        claimedUserInput = null
        _uiState.update { currentState ->
            val restoredInput = when {
                currentState.userInput.isBlank() -> pendingMessage.text
                currentState.userInput == pendingMessage.text -> currentState.userInput
                else -> "${pendingMessage.text}\n\n${currentState.userInput}"
            }
            currentState.copy(
                userInput = restoredInput,
                composerMessageInputContext = pendingMessage.messageInputContext
                    ?: currentState.composerMessageInputContext,
                composerArtifacts = (pendingMessage.artifacts + currentState.composerArtifacts)
                    .distinctBy { it.id },
                workspaceContextReferences =
                    (pendingMessage.workspaceContextReferences + currentState.workspaceContextReferences)
                        .distinctBy { it.kind to it.relativePath },
            )
        }
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
        _uiState.update {
            it.copy(
                userInput = message.text,
                composerArtifacts = message.artifacts,
                composerMessageInputContext = message.messageInputContext,
                workspaceContextReferences = message.workspaceContextReferences,
            )
        }
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
                    agentDefinitionId = steeredMessage.agentDefinitionId,
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
        messageInputContext: MessageInputContext? = null,
        currentState: UIState.Tab = _uiState.value,
    ): PendingUserMessage {
        val activeInstructions = messageInstructionGroups.mapNotNull { group ->
            val activeControlIndex = group.controls.indexOfFirst { control ->
                control.data.id in currentState.activeMessageInstructionIds
            }

            val selectedControlIndex = if (activeControlIndex >= 0) activeControlIndex else group.selectedByDefault
            val selectedControl = group.controls[selectedControlIndex]

            if (selectedControl.includeInMessage) {
                selectedControl.data
            } else null
        }

        val workspaceContextInstruction = currentState.workspaceContextReferences
            .takeIf { it.isNotEmpty() }
            ?.let { Conversation.Message.Instruction.WorkspaceContext(it) }

        val suppliedInputContextInstruction = additionalInstructions
            .filterIsInstance<Conversation.Message.Instruction.MessageInputRuntimeContext>()
            .lastOrNull()
        val inputContextInstruction = messageInputContext
            ?.let { Conversation.Message.Instruction.MessageInputRuntimeContext(it) }
            ?: suppliedInputContextInstruction
        val otherAdditionalInstructions = additionalInstructions.filterNot {
            it is Conversation.Message.Instruction.MessageInputRuntimeContext
        }
        val instructions = listOfNotNull(inputContextInstruction) +
            activeInstructions +
            listOfNotNull(workspaceContextInstruction) +
            otherAdditionalInstructions

        val content = buildList {
            message.takeIf(String::isNotBlank)?.let {
                add(Conversation.Message.ContentItem.UserMessage(it))
            }
            currentState.composerArtifacts.forEach { artifact ->
                add(Conversation.Message.ContentItem.ArtifactItem(artifact))
            }
        }
        require(content.isNotEmpty()) { "User message must contain text or an attachment" }

        val userMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = content,
            createdAt = Clock.System.now(),
            instructions = instructions
        )

        return PendingUserMessage(
            userMessage = userMessage,
            agentDefinitionId = currentState.agent.id,
            placement = QueuedMessagePlacement.END_OF_TURN,
        )
    }

    private fun sendPendingMessageToSession(
        pendingMessage: PendingUserMessage,
    ) {
        if (currentRequestJob?.isActive == true || _isWaitingForResponse.value) {
            scope.launch {
                if (submitPendingMessage(pendingMessage)) {
                    showPendingMessage(pendingMessage)
                }
            }
            return
        }

        val userMessage = pendingMessage.userMessage

        _allMessages.value += userMessage
        _isWaitingForResponse.value = true
        _executionPauseRequested.value = false
        _uiState.update {
            it.copy(
                userInput = "",
                composerArtifacts = emptyList(),
                composerMessageInputContext = null,
                workspaceContextReferences = emptyList(),
                isWaitingForResponse = true,
            )
        }

        currentRequestJob = scope.launch {
            try {
                log.debug { "Submitting message to conversation $conversationId" }
                val accepted = conversationRuntimeService.submitMessage(
                    conversationId,
                    userMessage,
                    pendingMessage.agentDefinitionId,
                )
                if (!accepted) {
                    _allMessages.update { messages ->
                        messages.filterNot { it.id == userMessage.id }
                    }
                    _isWaitingForResponse.value = false
                    restoreComposerMessage(pendingMessage)
                    _uiState.update { it.copy(isWaitingForResponse = false) }
                    log.warn { "Runtime rejected submitted message for conversation $conversationId" }
                }
            } catch (e: Exception) {
                _allMessages.update { messages ->
                    messages.filterNot { it.id == userMessage.id }
                }
                _isWaitingForResponse.value = false
                restoreComposerMessage(pendingMessage)
                _uiState.update { it.copy(isWaitingForResponse = false) }
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
                agentDefinitionId = pendingMessage.agentDefinitionId,
            )
        }.onFailure { error ->
            log.warn(error) {
                "Runtime queue request failed for conversation $conversationId: ${error.message}"
            }
        }.getOrDefault(false)

    private fun showPendingMessage(pendingMessage: PendingUserMessage) {
        _pendingMessages.update { messages ->
            if (messages.any { it.id == pendingMessage.id }) {
                messages
            } else {
                messages + pendingMessage
            }
        }
    }

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
        _activeToolExecutions.value = emptyList()
        _runtimeTrace.value = emptyList()
        scope.launch {
            runCatching {
                conversationRuntimeService.controlExecution(conversationId, ConversationRuntimeControlAction.INTERRUPT)
            }.onFailure { error ->
                log.warn(error) { "Runtime interrupt request failed for conversation $conversationId: ${error.message}" }
            }
        }
        currentRequestJob?.cancel()
        currentRequestJob = null
        _isWaitingForResponse.value = false
        _uiState.update { it.copy(isWaitingForResponse = false) }
    }

    fun cancelCommandTask(taskId: CommandTask.Id) {
        scope.launch {
            runCatching {
                conversationRuntimeService.cancelCommandTask(conversationId, taskId)
            }.onFailure { error ->
                log.warn(error) { "Command task cancellation failed for ${taskId.value}: ${error.message}" }
            }
        }
    }

    fun cancelCommandMonitor(monitorId: CommandMonitor.Id) {
        scope.launch {
            runCatching {
                conversationRuntimeService.cancelCommandMonitor(conversationId, monitorId)
            }.onFailure { error ->
                log.warn(error) { "Command monitor cancellation failed for ${monitorId.value}: ${error.message}" }
            }
        }
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
                _activeToolExecutions.value = emptyList()
                _runtimeTrace.value = emptyList()
            }
        }
    }

    fun pickAttachments() {
        acquireAndUploadAttachments(attachmentAcquisitionController::pickAttachments)
    }

    fun captureScreenshot() {
        acquireAndUploadAttachments {
            listOfNotNull(attachmentAcquisitionController.captureScreenshot())
        }
    }

    fun addAttachments(uploads: List<com.gromozeka.domain.model.ArtifactUpload>) {
        if (uploads.isEmpty()) return
        acquireAndUploadAttachments { uploads }
    }

    fun removeComposerArtifact(id: com.gromozeka.domain.model.Artifact.Id) {
        val removedArtifact = _uiState.value.composerArtifacts.firstOrNull { it.id == id }
        _uiState.update { state ->
            state.copy(
                composerArtifacts = state.composerArtifacts.filterNot { it.id == id },
                composerArtifactError = null,
            )
        }
        if (removedArtifact != null) {
            scope.launch {
                runCatching { artifactTransferService.deleteDraft(id) }
                    .onFailure { error ->
                        log.warn(error) { "Failed to release draft artifact ${id.value}: ${error.message}" }
                    }
            }
        }
    }

    fun clearComposerArtifactError() {
        _uiState.update { it.copy(composerArtifactError = null) }
    }

    suspend fun loadArtifactContent(id: com.gromozeka.domain.model.Artifact.Id): ByteArray {
        artifactContentCacheMutex.withLock {
            artifactContentCache[id]?.let { return it }
        }

        val content = artifactTransferService.download(id)
        if (content.size > MAX_ARTIFACT_CONTENT_CACHE_BYTES) return content

        artifactContentCacheMutex.withLock {
            artifactContentCache[id]?.let { return it }
            while (
                artifactContentCacheBytes + content.size > MAX_ARTIFACT_CONTENT_CACHE_BYTES &&
                artifactContentCache.isNotEmpty()
            ) {
                val oldest = artifactContentCache.entries.first()
                artifactContentCache.remove(oldest.key)
                artifactContentCacheBytes -= oldest.value.size
            }
            artifactContentCache[id] = content
            artifactContentCacheBytes += content.size
        }
        return content
    }

    fun reportAttachmentError(message: String) {
        _uiState.update { it.copy(composerArtifactError = message) }
    }

    private fun acquireAndUploadAttachments(
        acquire: suspend () -> List<com.gromozeka.domain.model.ArtifactUpload>,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            artifactUploadMutex.withLock {
                _uiState.update {
                    it.copy(
                        composerArtifactUploadInProgress = true,
                        composerArtifactError = null,
                    )
                }
                try {
                    acquire().forEach { upload ->
                        require(upload.content.size <= ArtifactLimits.MAX_FILE_BYTES) {
                            "${upload.fileName} exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB limit"
                        }
                        val currentArtifacts = _uiState.value.composerArtifacts
                        require(currentArtifacts.size < ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE) {
                            "A message can contain at most ${ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE} attachments"
                        }
                        val totalBytes = currentArtifacts.sumOf { it.sizeBytes } + upload.content.size
                        require(totalBytes <= ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE) {
                            "Message attachments exceed the ${ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE / (1024 * 1024)} MB total limit"
                        }
                        val reference = artifactTransferService.upload(conversationId, upload)
                        _uiState.update { state ->
                            state.copy(
                                composerArtifacts = (state.composerArtifacts + reference)
                                    .distinctBy { it.id },
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn(error) { "Artifact acquisition failed: ${error.message}" }
                    _uiState.update {
                        it.copy(composerArtifactError = error.message ?: "Failed to attach file")
                    }
                } finally {
                    _uiState.update { it.copy(composerArtifactUploadInProgress = false) }
                }
            }
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
        val toolMessageIds = allMessages.value
            .filter { message ->
                message.content.any {
                    it is Conversation.Message.ContentItem.ToolCall ||
                        it is Conversation.Message.ContentItem.ToolResult
                }
            }
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
        runSelectedMessageSquash(SquashType.CONCATENATE) { selectedIds ->
            val selectedMessages = _allMessages.value.filter { it.id in selectedIds }
            require(selectedMessages.size == selectedIds.size) { "Some selected messages are no longer available" }
            val combinedText = selectedMessages.toConcatenatedCompactionText()

            conversationService.squashMessages(
                conversationId,
                selectedIds.toList(),
                listOf(Conversation.Message.ContentItem.UserMessage(combinedText)),
            )
        }
    }

    suspend fun distillSelectedMessages() {
        squashWithAI(SquashType.DISTILL)
    }

    suspend fun summarizeSelectedMessages() {
        squashWithAI(SquashType.SUMMARIZE)
    }

    private suspend fun squashWithAI(squashType: SquashType) {
        runSelectedMessageSquash(squashType) { selectedIds ->
            val runtimeSelection = aiConfigurationProvider.runtimeSelectionFor(
                AiRuntimeAssignment.Purpose.MESSAGE_SQUASH
            )

            log.info { "Starting AI squash: type=$squashType, runtimeSelection=${runtimeSelection.modelConfigurationId.value}" }

            val result = messageSquashGenerationService.squashWithAI(
                conversationId = conversationId,
                selectedIds = selectedIds.toList(),
                squashType = squashType,
                runtimeSelection = runtimeSelection,
            )

            val squashedContent = listOf(
                Conversation.Message.ContentItem.UserMessage(result)
            )

            conversationService.squashMessages(
                conversationId,
                selectedIds.toList(),
                squashedContent
            )
        }
    }

    private suspend fun runSelectedMessageSquash(
        squashType: SquashType,
        action: suspend (Set<Conversation.Message.Id>) -> Unit,
    ) {
        val selectedIds = _uiState.value.selectedMessageIds
        if (selectedIds.size < 2) {
            _messageSquashState.value = MessageSquashUiState.Failed(
                squashType,
                "Select at least two messages",
            )
            return
        }
        if (!messageSquashMutex.tryLock()) return

        _messageSquashState.value = MessageSquashUiState.Running(squashType)
        try {
            action(selectedIds)
            clearMessageSelection()
            loadMessages()
            val succeededState = MessageSquashUiState.Succeeded(squashType)
            _messageSquashState.value = succeededState
            scope.launch {
                delay(3_000)
                _messageSquashState.compareAndSet(succeededState, MessageSquashUiState.Idle)
            }
            log.info { "Message squash completed: type=$squashType selectedCount=${selectedIds.size}" }
        } catch (error: CancellationException) {
            _messageSquashState.value = MessageSquashUiState.Idle
            throw error
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
            _messageSquashState.value = MessageSquashUiState.Failed(squashType, message)
            log.error(error) { "Message squash failed: type=$squashType" }
        } finally {
            messageSquashMutex.unlock()
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

internal fun appendComposerText(currentInput: String, appendedText: String): String {
    val normalizedAppend = appendedText.trim()
    if (normalizedAppend.isEmpty()) return currentInput
    val separator = if (currentInput.isBlank() || currentInput.last().isWhitespace()) "" else " "
    return "$currentInput$separator$normalizedAppend"
}

sealed interface MessageSquashUiState {
    data object Idle : MessageSquashUiState
    data class Running(val squashType: SquashType) : MessageSquashUiState
    data class Succeeded(val squashType: SquashType) : MessageSquashUiState
    data class Failed(val squashType: SquashType, val message: String) : MessageSquashUiState
}

internal fun List<Conversation.Message>.toConcatenatedCompactionText(): String =
    joinToString("\n\n") { message ->
        val content = message.content.mapNotNull { item ->
            when (item) {
                is Conversation.Message.ContentItem.UserMessage -> item.text
                is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                is Conversation.Message.ContentItem.Thinking -> item.thinking.takeIf(String::isNotBlank)
                is Conversation.Message.ContentItem.System -> item.content
                is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] ${item.call.input}"
                is Conversation.Message.ContentItem.ToolResult -> buildString {
                    append("[tool_result:${item.toolName}]")
                    item.result.forEach { result ->
                        append('\n')
                        append(
                            when (result) {
                                is Conversation.Message.ContentItem.ToolResult.Data.Text -> result.content
                                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                                    "[binary:${result.fileName ?: result.mediaType.value} media_type=${result.mediaType.value}]"
                                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url:${result.url}]"
                                is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                                    "[attachment:${result.artifact.fileName} media_type=${result.artifact.mediaType}]"
                            }
                        )
                    }
                }
                is Conversation.Message.ContentItem.ImageItem -> when (val source = item.source) {
                    is Conversation.Message.ImageSource.Base64ImageSource -> "[image:${source.mediaType}]"
                    is Conversation.Message.ImageSource.UrlImageSource -> "[image:${source.url}]"
                    is Conversation.Message.ImageSource.FileImageSource -> "[image:${source.fileId}]"
                }
                is Conversation.Message.ContentItem.DocumentItem -> when (val source = item.source) {
                    is Conversation.Message.DocumentSource.Base64DocumentSource ->
                        "[document:${source.fileName} media_type=${source.mediaType}]"
                }
                is Conversation.Message.ContentItem.ArtifactItem ->
                    "[attachment:${item.artifact.fileName} media_type=${item.artifact.mediaType}]"
                is Conversation.Message.ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
                    is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
                    is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                        "[context_compaction:${item.providerScope?.provider ?: "unknown"}]"
                }
                is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
            }
        }.filter(String::isNotBlank).joinToString("\n")
        "[${message.role.name.lowercase()}]\n$content"
    }.trim().also { require(it.isNotBlank()) { "Selected messages contain no readable content" } }

private fun UIState.Tab.withSelectedMessageInstruction(
    group: MessageInstructionGroup,
    controlIndex: Int,
): UIState.Tab {
    val selectedControl = group.controls.getOrNull(controlIndex) ?: return this
    val selectedId = selectedControl.data.id
    if (selectedId in activeMessageInstructionIds) return this

    val groupInstructionIds = group.controls.mapTo(mutableSetOf()) { control -> control.data.id }
    return copy(activeMessageInstructionIds = activeMessageInstructionIds - groupInstructionIds + selectedId)
}

data class PendingUserMessage(
    val userMessage: Conversation.Message,
    val agentDefinitionId: AgentDefinition.Id,
    val placement: QueuedMessagePlacement,
) {
    val id: String get() = userMessage.id.value

    val text: String
        get() = userMessage.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }

    val artifacts: List<com.gromozeka.domain.model.Artifact.Reference>
        get() = userMessage.content
            .filterIsInstance<Conversation.Message.ContentItem.ArtifactItem>()
            .map { it.artifact }

    val workspaceContextReferences: List<WorkspaceContextReference>
        get() = userMessage.instructions
            .filterIsInstance<Conversation.Message.Instruction.WorkspaceContext>()
            .flatMap { it.references }

    val messageInputContext: MessageInputContext?
        get() = userMessage.instructions
            .filterIsInstance<Conversation.Message.Instruction.MessageInputRuntimeContext>()
            .lastOrNull()
            ?.context
}

private fun ConversationRuntimeTask.toPendingUserMessageOrNull(): PendingUserMessage? =
    userTurnOrNull()?.let { userTurn ->
        PendingUserMessage(
            userMessage = userTurn.userMessage,
            agentDefinitionId = userTurn.agentDefinitionId,
            placement = placement,
        )
    }
