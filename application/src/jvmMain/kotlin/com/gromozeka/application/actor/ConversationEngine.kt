package com.gromozeka.application.actor

import com.gromozeka.application.service.MessageConversionService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Conversation execution engine managing a single conversation.
 * 
 * NOT TO BE CONFUSED with AgentDefinition (AI agent role/configuration).
 * 
 * ConversationEngine is a live process that:
 * - Manages ONE conversation state in memory (exclusive access point while alive)
 * - Persists changes to database immediately (self-managed persistence)
 * - Executes LLM loop with current AgentDefinition
 * - Emits events for observers (UI, logging, etc.)
 * 
 * Relationship to domain concepts:
 * - ConversationEngine : Conversation = 1:1 (one engine per conversation)
 * - ConversationEngine : AgentDefinition = N:1 (can switch definitions)
 * - ConversationEngine : Thread = 1:N (creates forks on edit/delete)
 * 
 * Lifecycle:
 * 1. Created by supervisor with conversation ID
 * 2. start() - begins processing commands
 * 3. send(Command.Initialize) - loads conversation and messages from DB once
 * 4. Processes commands sequentially (no re-reading from DB)
 * 5. Persists every state change to DB immediately
 * 6. Emits Event.Completed when LLM loop finishes
 * 7. Supervisor removes engine from memory on Event.Completed
 * 
 * Does NOT:
 * - Re-read from database during operation (holds state in memory)
 * - Wait for supervisor to persist (does it itself)
 * - Manage its own lifecycle (supervisor's responsibility)
 */
class ConversationEngine(
    private val conversationId: Conversation.Id,
    private val scope: CoroutineScope,
    
    // Repositories for self-managed persistence
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: ThreadMessageRepository,
    private val projectRepository: com.gromozeka.domain.repository.ProjectRepository,
    
    // Services
    private val chatModelProvider: ChatModelProvider,
    private val agentDomainService: AgentDomainService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val messageConversionService: MessageConversionService,
    private val availableTools: List<ToolCallback>,
    
    // Communication
    private val eventChannel: SendChannel<Event>,
    
    // Configuration
    private val maxIterations: Int = 200,
) {
    private val log = KLoggers.logger(this)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    
    private var state = State(
        conversation = null,
        messages = emptyList(),
        isRunning = false,
        isInitialized = false
    )
    
    private var currentJob: Job? = null
    
    fun start() {
        scope.launch {
            for (command in commandChannel) {
                handleCommand(command)
            }
        }
    }
    
    suspend fun send(command: Command) {
        commandChannel.send(command)
    }
    
    private suspend fun handleCommand(command: Command) {
        // Initialize command allowed anytime, others require initialization
        if (!state.isInitialized && command !is Command.Initialize) {
            log.error { "Agent not initialized, ignoring command: ${command::class.simpleName}" }
            eventChannel.send(Event.Error(IllegalStateException("Agent not initialized")))
            return
        }
        
        when (command) {
            is Command.Initialize -> handleInitialize(command)
            is Command.SendUserMessage -> handleSendUserMessage(command)
            is Command.SwitchDefinition -> handleSwitchDefinition(command)
            is Command.Interrupt -> handleInterrupt()
            is Command.EditMessage -> handleEditMessage(command)
            is Command.DeleteMessages -> handleDeleteMessages(command)
        }
    }
    
    private suspend fun handleInitialize(command: Command.Initialize) {
        try {
            // Cancel any running job before reinitialization
            if (state.isRunning) {
                currentJob?.cancel()
                currentJob = null
            }
            
            val conversation = conversationRepository.findById(conversationId)
            if (conversation == null) {
                val error = IllegalStateException("Conversation not found: $conversationId")
                log.error(error) { "Failed to initialize agent" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            val messages = messageRepository.findByConversation(conversationId)
            
            val definition = agentDomainService.findById(conversation.agentDefinitionId)
            if (definition == null) {
                val error = IllegalStateException("Agent definition not found: ${conversation.agentDefinitionId}")
                log.error(error) { "Failed to initialize agent" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            // Fix orphaned tool calls in historical data (if any)
            val fixedMessages = fixOrphanedToolCallsInHistory(messages)
            
            state = state.copy(
                conversation = conversation,
                messages = fixedMessages,
                isRunning = false,
                isInitialized = true
            )
            
            eventChannel.send(Event.DefinitionSwitched(definition))
            
            log.info { "Agent initialized for conversation $conversationId with ${messages.size} messages, definition: ${definition.name}" }
            eventChannel.send(Event.Initialized)
        } catch (e: Exception) {
            log.error(e) { "Failed to initialize agent for conversation $conversationId" }
            eventChannel.send(Event.Error(e))
        }
    }
    
    private suspend fun handleSendUserMessage(command: Command.SendUserMessage) {
        // 1. Check if last message has unanswered tool calls
        val lastMessage = state.messages.lastOrNull()
        val needsFix = lastMessage?.role == Conversation.Message.Role.ASSISTANT &&
                       lastMessage.content.any { it is Conversation.Message.ContentItem.ToolCall }
        
        // 2. Create error results if needed
        val errorResults = if (needsFix) {
            lastMessage!!.content
                .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                .map { toolCall ->
                    Conversation.Message(
                        id = Conversation.Message.Id(uuid7()),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.ToolResult(
                                toolUseId = toolCall.id,
                                toolName = toolCall.call.name,
                                result = listOf(
                                    Conversation.Message.ContentItem.ToolResult.Data.Text(
                                        content = "Tool execution was interrupted or cancelled"
                                    )
                                ),
                                isError = true,
                                state = Conversation.Message.BlockState.COMPLETE
                            )
                        ),
                        createdAt = kotlinx.datetime.Clock.System.now()
                    )
                }
        } else {
            emptyList()
        }
        
        // 3. Add user message
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = command.content,
            createdAt = kotlinx.datetime.Clock.System.now()
        )
        
        // 4. Update state
        val newMessages = state.messages + errorResults + userMessage
        state = state.copy(
            messages = newMessages,
            isRunning = true
        )
        
        // 5. Persist to database
        (errorResults + userMessage).forEach { messageRepository.save(it) }
        
        // 6. Emit state change
        eventChannel.send(Event.StateChanged(state.messages))
        
        // 6. Run LLM loop in background
        currentJob = scope.launch {
            try {
                runLLMLoop()
            } catch (e: Exception) {
                eventChannel.send(Event.Error(e))
                state = state.copy(isRunning = false)
            }
        }
    }
    
    private suspend fun handleSwitchDefinition(command: Command.SwitchDefinition) {
        val conversation = state.conversation
        if (conversation == null) {
            val error = IllegalStateException("Conversation not loaded")
            log.error(error) { "Cannot switch definition" }
            eventChannel.send(Event.Error(error))
            return
        }
        
        // Update conversation with new agent definition
        conversationRepository.updateAgentDefinition(conversationId, command.definition.id)
        val updatedConversation = conversation.copy(
            agentDefinitionId = command.definition.id,
            updatedAt = kotlinx.datetime.Clock.System.now()
        )
        
        state = state.copy(conversation = updatedConversation)
        
        eventChannel.send(Event.DefinitionSwitched(command.definition))
        log.info { "Switched to agent definition: ${command.definition.name}" }
    }
    
    private suspend fun handleInterrupt() {
        currentJob?.cancel()
        currentJob = null
        state = state.copy(isRunning = false)
        eventChannel.send(Event.Interrupted)
    }
    
    private suspend fun handleEditMessage(command: Command.EditMessage) {
        val conversation = state.conversation
        if (conversation == null) {
            val error = IllegalStateException("Conversation not loaded")
            log.error(error) { "Cannot edit message" }
            eventChannel.send(Event.Error(error))
            return
        }
        
        try {
            // Find position of edited message
            val editPosition = state.messages.indexOfFirst { it.id == command.messageId }
            if (editPosition == -1) {
                val error = IllegalArgumentException("Message not found: ${command.messageId}")
                log.error(error) { "Cannot edit message" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            // Create edited message (new ID, new content)
            val editedMessage = state.messages[editPosition].copy(
                id = Conversation.Message.Id(uuid7()),
                content = command.newContent,
                createdAt = kotlinx.datetime.Clock.System.now()
            )
            
            // Save edited message
            messageRepository.save(editedMessage)
            
            // Create new thread (fork)
            val currentThread = threadRepository.findById(conversation.currentThread)
            if (currentThread == null) {
                val error = IllegalStateException("Current thread not found: ${conversation.currentThread}")
                log.error(error) { "Cannot edit message" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            val newThread = Conversation.Thread(
                id = Conversation.Thread.Id(uuid7()),
                conversationId = conversationId,
                originalThread = currentThread.id,
                lastTurnNumber = editPosition,
                createdAt = kotlinx.datetime.Clock.System.now(),
                updatedAt = kotlinx.datetime.Clock.System.now()
            )
            threadRepository.save(newThread)
            
            // Copy messages up to edit position + add edited message
            val messagesBeforeEdit = state.messages.take(editPosition)
            val newMessages = messagesBeforeEdit + editedMessage
            
            // Check if last message before edit has orphaned tool calls
            val lastBeforeEdit = messagesBeforeEdit.lastOrNull()
            val needsFix = lastBeforeEdit?.role == Conversation.Message.Role.ASSISTANT &&
                           lastBeforeEdit.content.any { it is Conversation.Message.ContentItem.ToolCall }
            
            val messagesToLink = if (needsFix) {
                // Add error results for orphaned tool calls
                val errorResults = lastBeforeEdit!!.content
                    .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                    .map { toolCall ->
                        Conversation.Message(
                            id = Conversation.Message.Id(uuid7()),
                            conversationId = conversationId,
                            role = Conversation.Message.Role.USER,
                            content = listOf(
                                Conversation.Message.ContentItem.ToolResult(
                                    toolUseId = toolCall.id,
                                    toolName = toolCall.call.name,
                                    result = listOf(
                                        Conversation.Message.ContentItem.ToolResult.Data.Text(
                                            content = "Tool execution was interrupted by message edit"
                                        )
                                    ),
                                    isError = true,
                                    state = Conversation.Message.BlockState.COMPLETE
                                )
                            ),
                            createdAt = kotlinx.datetime.Clock.System.now()
                        )
                    }
                
                // Save error results
                errorResults.forEach { messageRepository.save(it) }
                
                messagesBeforeEdit + errorResults + editedMessage
            } else {
                newMessages
            }
            
            // Link messages to new thread
            messagesToLink.forEachIndexed { index, message ->
                threadMessageRepository.add(newThread.id, message.id, index)
            }
            
            // Update conversation to point to new thread
            conversationRepository.updateCurrentThread(conversationId, newThread.id)
            val updatedConversation = conversation.copy(
                currentThread = newThread.id,
                updatedAt = kotlinx.datetime.Clock.System.now()
            )
            
            // Update state
            state = state.copy(
                conversation = updatedConversation,
                messages = messagesToLink
            )
            
            eventChannel.send(Event.StateChanged(state.messages))
            eventChannel.send(Event.ThreadForked(newThread.id, currentThread.id))
            
            log.info { "Message edited, created new thread ${newThread.id} from ${currentThread.id}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to edit message" }
            eventChannel.send(Event.Error(e))
        }
    }
    
    private suspend fun handleDeleteMessages(command: Command.DeleteMessages) {
        val conversation = state.conversation
        if (conversation == null) {
            val error = IllegalStateException("Conversation not loaded")
            log.error(error) { "Cannot delete messages" }
            eventChannel.send(Event.Error(error))
            return
        }
        
        try {
            // Filter out deleted messages
            val remainingMessages = state.messages.filterNot { msg ->
                msg.id in command.messageIds
            }
            
            // Check if last remaining message has orphaned tool calls
            val lastRemaining = remainingMessages.lastOrNull()
            val needsFix = lastRemaining?.role == Conversation.Message.Role.ASSISTANT &&
                           lastRemaining.content.any { it is Conversation.Message.ContentItem.ToolCall }
            
            val messagesToLink = if (needsFix) {
                // Add error results for orphaned tool calls
                val errorResults = lastRemaining!!.content
                    .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                    .map { toolCall ->
                        Conversation.Message(
                            id = Conversation.Message.Id(uuid7()),
                            conversationId = conversationId,
                            role = Conversation.Message.Role.USER,
                            content = listOf(
                                Conversation.Message.ContentItem.ToolResult(
                                    toolUseId = toolCall.id,
                                    toolName = toolCall.call.name,
                                    result = listOf(
                                        Conversation.Message.ContentItem.ToolResult.Data.Text(
                                            content = "Tool execution was interrupted by message deletion"
                                        )
                                    ),
                                    isError = true,
                                    state = Conversation.Message.BlockState.COMPLETE
                                )
                            ),
                            createdAt = kotlinx.datetime.Clock.System.now()
                        )
                    }
                
                // Save error results
                errorResults.forEach { messageRepository.save(it) }
                
                remainingMessages + errorResults
            } else {
                remainingMessages
            }
            
            // Create new thread with remaining messages
            val currentThread = threadRepository.findById(conversation.currentThread)
            if (currentThread == null) {
                val error = IllegalStateException("Current thread not found: ${conversation.currentThread}")
                log.error(error) { "Cannot delete messages" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            val newThread = Conversation.Thread(
                id = Conversation.Thread.Id(uuid7()),
                conversationId = conversationId,
                originalThread = currentThread.id,
                lastTurnNumber = messagesToLink.size - 1,
                createdAt = kotlinx.datetime.Clock.System.now(),
                updatedAt = kotlinx.datetime.Clock.System.now()
            )
            threadRepository.save(newThread)
            
            // Link messages to new thread
            messagesToLink.forEachIndexed { index, message ->
                threadMessageRepository.add(newThread.id, message.id, index)
            }
            
            // Update conversation to point to new thread
            conversationRepository.updateCurrentThread(conversationId, newThread.id)
            val updatedConversation = conversation.copy(
                currentThread = newThread.id,
                updatedAt = kotlinx.datetime.Clock.System.now()
            )
            
            // Update state
            state = state.copy(
                conversation = updatedConversation,
                messages = messagesToLink
            )
            
            eventChannel.send(Event.StateChanged(state.messages))
            eventChannel.send(Event.ThreadForked(newThread.id, currentThread.id))
            
            log.info { "Messages deleted, created new thread ${newThread.id} from ${currentThread.id}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to delete messages" }
            eventChannel.send(Event.Error(e))
        }
    }
    
    private suspend fun runLLMLoop() {
        try {
            val conversation = state.conversation
            if (conversation == null) {
                val error = IllegalStateException("Conversation not loaded")
                log.error(error) { "Cannot run LLM loop" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            val definition = agentDomainService.findById(conversation.agentDefinitionId)
            if (definition == null) {
                val error = IllegalStateException("Agent definition not found: ${conversation.agentDefinitionId}")
                log.error(error) { "Cannot run LLM loop" }
                eventChannel.send(Event.Error(error))
                return
            }
            
            // Get projectPath from conversation.projectId
            val project = projectRepository.findById(conversation.projectId)
                ?: throw IllegalStateException("Project not found: ${conversation.projectId}")
            
            val provider = AIProvider.valueOf(definition.aiProvider)
            
            // Special case: use Opus for Architect agent
            val modelName = if (definition.name == "Архитектор" && provider == AIProvider.ANTHROPIC) {
                "claude-opus-4-6"
            } else {
                "claude-sonnet-4-5-20250929"
            }
            
            val model = chatModelProvider.getChatModel(provider, modelName, project.path)
            
            // Assemble system prompts from agent definition
            val systemPrompts = agentDomainService.assembleSystemPrompt(definition, project)
            val systemMessages = systemPrompts.map { SystemMessage(it) }
            
            val toolOptions = collectToolOptions(project.path, provider, definition)
            
            var iteration = 0
            
            while (iteration < maxIterations) {
                iteration++
                
                // Convert current state messages to Spring AI format
                val springHistory = messageConversionService.convertHistoryToSpringAI(state.messages)
                val currentPrompt = Prompt(systemMessages + springHistory, toolOptions)
                
                // Call LLM
                log.info { "Calling LLM: model=$modelName, provider=$provider, iteration=$iteration" }
                val chatResponse: ChatResponse = try {
                    withContext(Dispatchers.IO) {
                        model.call(currentPrompt)
                    }
                } catch (e: Exception) {
                    log.error(e) { "Chat call error" }
                    eventChannel.send(Event.Error(e))
                    break
                }
                
                val generation = chatResponse.results.firstOrNull()
                if (generation == null) {
                    log.warn { "Empty response from chat model" }
                    break
                }
                
                val allToolCalls = chatResponse.results.flatMap { it.output.toolCalls }
                val hasToolCalls = chatResponse.hasToolCalls()
                
                // Create assistant message
                val assistantMessage = createAssistantMessageFromResponse(chatResponse)
                
                // Update state with new message and pending tool calls
                val toolCallsMap = allToolCalls.associateBy { 
                    Conversation.Message.ContentItem.ToolCall.Id(it.id()) 
                }
                
                state = state.copy(
                    messages = state.messages + assistantMessage
                )
                
                // Persist assistant message
                messageRepository.save(assistantMessage)
                
                // Emit state change and message
                eventChannel.send(Event.StateChanged(state.messages))
                eventChannel.send(Event.MessageEmitted(assistantMessage))
                
                if (!hasToolCalls) {
                    // No tools - done
                    break
                }
                
                // Execute tools
                val toolContext = ToolContext(mapOf("projectPath" to project.path))
                val executionResult = parallelToolExecutor.executeParallel(
                    toolCalls = allToolCalls,
                    toolContext = toolContext,
                    scope = scope
                )
                
                // Create tool result message
                val toolResultMessage = Conversation.Message(
                    id = Conversation.Message.Id(uuid7()),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.USER,
                    content = executionResult.results,
                    createdAt = kotlinx.datetime.Clock.System.now()
                )
                
                // Update state
                state = state.copy(
                    messages = state.messages + toolResultMessage
                )
                
                // Persist tool result message
                messageRepository.save(toolResultMessage)
                
                // Emit state change and message
                eventChannel.send(Event.StateChanged(state.messages))
                eventChannel.send(Event.MessageEmitted(toolResultMessage))
                
                if (executionResult.returnDirect) {
                    break
                }
            }
            
            if (iteration >= maxIterations) {
                log.warn { "Tool execution loop exceeded maximum iterations ($maxIterations)" }
            }
            
        } catch (e: Exception) {
            log.error(e) { "LLM loop error" }
            eventChannel.send(Event.Error(e))
        } finally {
            state = state.copy(isRunning = false)
            eventChannel.send(Event.Completed)
        }
    }
    
    private suspend fun collectToolOptions(
        projectPath: String?, 
        provider: AIProvider,
        definition: AgentDefinition
    ): ChatOptions {
        // Filter tools by definition.tools if specified
        val toolsToUse = if (definition.tools.isNotEmpty()) {
            val allowedTools = definition.tools.toSet()
            availableTools.filter { it.toolDefinition.name() in allowedTools }
        } else {
            // No filter - use all available tools
            availableTools
        }
        
        val toolNames = toolsToUse.map { it.toolDefinition.name() }.toSet()
        
        return when (provider) {
            AIProvider.ANTHROPIC -> {
                val cacheOptions = AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .build()
                
                AnthropicChatOptions.builder()
                    .toolCallbacks(toolsToUse)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .cacheOptions(cacheOptions)
                    .build()
            }
            AIProvider.OPEN_AI -> {
                OpenAiChatOptions.builder()
                    .toolCallbacks(toolsToUse)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()
            }
            else -> {
                ToolCallingChatOptions.builder()
                    .toolCallbacks(toolsToUse)
                    .toolNames(toolNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()
            }
        }
    }
    
    private fun createAssistantMessageFromResponse(chatResponse: ChatResponse): Conversation.Message {
        val content = mutableListOf<Conversation.Message.ContentItem>()
        
        chatResponse.results.forEach { generation ->
            val assistantMsg = generation.output
            
            if (!assistantMsg.text.isNullOrBlank()) {
                content.add(
                    Conversation.Message.ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(fullText = assistantMsg.text ?: ""),
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                )
            }
            
            assistantMsg.toolCalls.forEach { toolCall ->
                val input = try {
                    Json.parseToJsonElement(toolCall.arguments())
                } catch (e: Exception) {
                    log.error(e) { "Failed to parse tool call arguments for ${toolCall.name()}: ${toolCall.arguments()}" }
                    JsonObject(
                        mapOf(
                            "error" to kotlinx.serialization.json.JsonPrimitive("Parse error: ${e.message}"),
                            "raw" to kotlinx.serialization.json.JsonPrimitive(toolCall.arguments())
                        )
                    )
                }
                content.add(
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id(toolCall.id()),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = toolCall.name(),
                            input = input
                        ),
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                )
            }
        }
        
        return Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = content,
            createdAt = kotlinx.datetime.Clock.System.now()
        )
    }
    
    /**
     * Fix orphaned tool calls in historical data from database.
     * 
     * Orphaned = ToolCall without corresponding ToolResult.
     * This happens when app crashed/restarted after LLM returned tool calls.
     * 
     * Adds error ToolResult messages for each orphaned tool call.
     */
    private suspend fun fixOrphanedToolCallsInHistory(
        messages: List<Conversation.Message>
    ): List<Conversation.Message> {
        val toolCalls = mutableMapOf<Conversation.Message.ContentItem.ToolCall.Id, Conversation.Message.ContentItem.ToolCall>()
        val toolResults = mutableSetOf<Conversation.Message.ContentItem.ToolCall.Id>()
        
        messages.forEach { message ->
            message.content.forEach { item ->
                when (item) {
                    is Conversation.Message.ContentItem.ToolCall -> {
                        toolCalls[item.id] = item
                    }
                    is Conversation.Message.ContentItem.ToolResult -> {
                        toolResults.add(item.toolUseId)
                    }
                    else -> {}
                }
            }
        }
        
        // Find orphaned tool calls
        val orphanedToolCalls = toolCalls.filterKeys { it !in toolResults }
        
        if (orphanedToolCalls.isEmpty()) {
            return messages
        }
        
        // Create error results for orphaned tool calls
        val errorResults = orphanedToolCalls.values.map { toolCall ->
            Conversation.Message(
                id = Conversation.Message.Id(uuid7()),
                conversationId = conversationId,
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = toolCall.id,
                        toolName = toolCall.call.name,
                        result = listOf(
                            Conversation.Message.ContentItem.ToolResult.Data.Text(
                                content = "Tool execution was interrupted or cancelled"
                            )
                        ),
                        isError = true,
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                ),
                createdAt = kotlinx.datetime.Clock.System.now()
            )
        }
        
        // Persist error results
        errorResults.forEach { messageRepository.save(it) }
        
        return messages + errorResults
    }
    
    data class State(
        val conversation: Conversation?,
        val messages: List<Conversation.Message>,
        val isRunning: Boolean,
        val isInitialized: Boolean
    )
    
    sealed class Command {
        /**
         * Initialize (or reinitialize) agent by loading conversation data from database.
         * 
         * Must be sent before any other commands on first use.
         * Can be sent again to reload data from database (e.g., if data changed externally).
         * 
         * Reinitialization will:
         * - Cancel any running LLM loop
         * - Reload conversation and messages from DB
         * - Reset to initial agent definition
         * - Clear running state
         */
        object Initialize : Command()
        
        data class SendUserMessage(
            val content: List<Conversation.Message.ContentItem>
        ) : Command()
        
        data class SwitchDefinition(
            val definition: AgentDefinition
        ) : Command()
        
        object Interrupt : Command()
        
        data class EditMessage(
            val messageId: Conversation.Message.Id,
            val newContent: List<Conversation.Message.ContentItem>
        ) : Command()
        
        data class DeleteMessages(
            val messageIds: List<Conversation.Message.Id>
        ) : Command()
    }
    
    sealed class Event {
        object Initialized : Event()
        
        data class StateChanged(
            val messages: List<Conversation.Message>
        ) : Event()
        
        data class MessageEmitted(val message: Conversation.Message) : Event()
        data class DefinitionSwitched(val definition: AgentDefinition) : Event()
        data class ThreadForked(val newThreadId: Conversation.Thread.Id, val originalThreadId: Conversation.Thread.Id) : Event()
        data class Error(val throwable: Throwable) : Event()
        object Interrupted : Event()
        object Completed : Event()
    }
}
