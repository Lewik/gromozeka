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
import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.domain.message.MessageTagDefinition
import com.gromozeka.shared.services.ConversationTreeService
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import java.util.UUID

class TabViewModel(
    val conversationId: ConversationTree.Id,
    val projectPath: String,
    private val conversationEngineService: ConversationEngineService,
    private val conversationTreeService: ConversationTreeService,
    private val soundNotificationService: SoundNotificationService,
    private val settingsFlow: StateFlow<Settings>,
    private val scope: CoroutineScope,
    initialTabUiState: UIState.Tab,
    private val screenCaptureController: ScreenCaptureController,
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
                        data = ConversationTree.Message.Instruction.UserInstruction("thinking_off", "Off", "Обычный режим работы"),
                        includeInMessage = false
                    ),
                    MessageTagDefinition.Control(
                        data = ConversationTree.Message.Instruction.UserInstruction(
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
                        data = ConversationTree.Message.Instruction.UserInstruction(
                            "mode_readonly",
                            "Readonly",
                            "Режим readonly - никаких изменений кода или команд применяющих изменения"
                        ),
                        includeInMessage = true
                    ),
                    MessageTagDefinition.Control(
                        data = ConversationTree.Message.Instruction.UserInstruction("mode_writable", "Writable", "Разрешено исправление файлов"),
                        includeInMessage = true
                    )
                ),
                selectedByDefault = 0
            )
        )

        fun getDefaultEnabledTags(): Set<String> {
            return ALL_MESSAGE_TAG_DEFINITIONS.map { tagDefinition ->
                (tagDefinition.controls[tagDefinition.selectedByDefault].data as ConversationTree.Message.Instruction.UserInstruction).id
            }.toSet()
        }
    }

    val availableMessageTags = ALL_MESSAGE_TAG_DEFINITIONS

    val activeMessageTags: Set<String> get() = _uiState.value.activeMessageTags
    val userInput: String get() = _uiState.value.userInput
    val activeMessageTagsFlow: StateFlow<Set<String>> = _uiState.map { it.activeMessageTags }.stateIn(
        scope, SharingStarted.Lazily, initialTabUiState.activeMessageTags
    )

    private val _allMessages = MutableStateFlow<List<ConversationTree.Message>>(emptyList())
    val allMessages: StateFlow<List<ConversationTree.Message>> = _allMessages.asStateFlow()

    private val _isWaitingForResponse = MutableStateFlow(false)
    val isWaitingForResponse: StateFlow<Boolean> = _isWaitingForResponse.asStateFlow()

    val pendingMessagesCount: StateFlow<Int> = flowOf(0).stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        _uiState.update { it.copy(isWaitingForResponse = false) }

        scope.launch {
            loadMessages()
        }
    }

    private suspend fun loadMessages() {
        try {
            val conversation = conversationTreeService.findById(conversationId)
            if (conversation != null) {
                _allMessages.value = conversation.messages
                log.debug { "Loaded ${conversation.messages.size} messages for conversation $conversationId" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load messages for conversation $conversationId" }
        }
    }

    val filteredMessages: StateFlow<List<ConversationTree.Message>> = combine(
        allMessages,
        settingsFlow
    ) { messages, settings ->
        messages.filter { message ->
            val containsOnlyToolResults = message.content.isNotEmpty() &&
                    message.content.all { it is ConversationTree.Message.ContentItem.ToolResult }

            if (containsOnlyToolResults) {
                false
            } else if (settings.showSystemMessages) {
                true
            } else {
                message.role != ConversationTree.Message.Role.SYSTEM ||
                        message.content.any { content ->
                            content is ConversationTree.Message.ContentItem.System &&
                                    content.level == ConversationTree.Message.ContentItem.System.SystemLevel.ERROR
                        }
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val toolResultsMap: StateFlow<Map<String, ConversationTree.Message.ContentItem.ToolResult>> =
        allMessages
            .map { messages ->
                messages
                    .flatMap { message ->
                        message.content.filterIsInstance<ConversationTree.Message.ContentItem.ToolResult>()
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
                val selectedId = (messageTag.controls[controlIndex].data as ConversationTree.Message.Instruction.UserInstruction).id

                val allIdsInGroup = messageTag.controls.map { (it.data as ConversationTree.Message.Instruction.UserInstruction).id }.toSet()

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
        additionalInstructions: List<ConversationTree.Message.Instruction> = emptyList(),
    ) {
        val currentState = _uiState.value

        val activeTagsData = availableMessageTags.mapNotNull { messageTag ->
            val activeControlIndex = messageTag.controls.indexOfFirst { control ->
                (control.data as ConversationTree.Message.Instruction.UserInstruction).id in currentState.activeMessageTags
            }

            val selectedControlIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault
            val selectedControl = messageTag.controls[selectedControlIndex]

            if (selectedControl.includeInMessage) {
                selectedControl.data
            } else null
        }

        val instructions = activeTagsData + additionalInstructions

        val userMessage = ConversationTree.Message(
            id = ConversationTree.Message.Id(UUID.randomUUID().toString()),
            role = ConversationTree.Message.Role.USER,
            content = listOf(ConversationTree.Message.ContentItem.UserMessage(message)),
            timestamp = Clock.System.now(),
            instructions = instructions
        )

        _allMessages.value += userMessage
        _isWaitingForResponse.value = true
        _uiState.update { it.copy(userInput = "", isWaitingForResponse = true) }

        currentRequestJob = scope.launch {
            try {
                log.debug { "Sending message to conversation $conversationId" }

                var lastMessage: ConversationTree.Message? = null

                conversationEngineService.streamMessage(conversationId, userMessage)
                    .collect { update ->
                        when (update) {
                            is ConversationEngineService.StreamUpdate.Chunk -> {
                                // Add new message to the list
                                val messages = _allMessages.value.toMutableList()
                                messages.add(update.message)
                                _allMessages.value = messages

                                lastMessage = update.message
                            }
                            is ConversationEngineService.StreamUpdate.Error -> {
                                log.error(update.exception) { "Stream error" }
                                soundNotificationService.playErrorSound()
                            }
                        }
                    }

                // Play completion sounds
                if (lastMessage != null) {
                    if (ChatMessageSoundDetector.shouldPlayErrorSound(lastMessage)) {
                        soundNotificationService.playErrorSound()
                    } else if (ChatMessageSoundDetector.shouldPlayMessageSound(lastMessage)) {
                        soundNotificationService.playMessageSound()
                    }
                }

                soundNotificationService.playReadySound()
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
}
