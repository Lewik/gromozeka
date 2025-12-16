package com.gromozeka.application.actor

import com.gromozeka.application.service.MessageConversionService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.McpToolProvider
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.ai.tool.ToolCallback

/**
 * Supervisor managing lifecycle of ConversationEngine instances.
 * 
 * Responsibilities:
 * - Create ConversationEngine on demand (one per conversation)
 * - Route commands to appropriate engine
 * - Broadcast events from engines to all subscribers
 * - Auto-cleanup: remove engine when it completes (Event.Completed)
 * - Manage subscriber lifecycle (Subscribe/Unsubscribe)
 * - Shutdown all engines on application exit
 * 
 * Does NOT:
 * - Load/save data from/to DB (ConversationEngine does it itself)
 * - Track message state (ConversationEngine manages state)
 * - Wrap events for persistence (ConversationEngine persists directly)
 * 
 * Architecture:
 * ```
 * ConversationSupervisor
 *     ├── engines: Map<Conversation.Id, EngineInstance>
 *     ├── createEngine(conversationId) → ConversationEngine
 *     ├── subscribe(conversationId, channel) → add to subscribers
 *     ├── broadcast(conversationId, event) → send to all subscribers
 *     └── removeEngine on Event.Completed (auto-cleanup)
 * ```
 * 
 * Broadcasting:
 * - One engine per conversation
 * - Multiple subscribers per engine (one per UI tab)
 * - Events broadcast to all subscribers
 * - Subscribers can unsubscribe when tab closes
 * 
 * Thread Safety:
 * - All mutable state protected by mutex
 * - Commands processed sequentially
 * - Subscribers modified under lock
 */
class ConversationSupervisor(
    private val scope: CoroutineScope,
    
    // Repositories (passed to ConversationEngine)
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: ThreadMessageRepository,
    private val projectRepository: com.gromozeka.domain.repository.ProjectRepository,
    
    // Services (passed to ConversationEngine)
    private val chatModelProvider: ChatModelProvider,
    private val agentDomainService: AgentDomainService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val messageConversionService: MessageConversionService,
    
    // Tools (merged before passing to ConversationEngine)
    private val toolCallbacks: List<ToolCallback>,
    private val mcpToolProvider: McpToolProvider,
    
    // Configuration
    private val maxIterations: Int = 200
) {
    private val log = KLoggers.logger(this)
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val mutex = Mutex()
    
    // One engine per conversation
    private val engines = mutableMapOf<Conversation.Id, EngineInstance>()
    
    /**
     * Running engine instance with event channel and subscribers.
     */
    data class EngineInstance(
        val engine: ConversationEngine,
        val eventChannel: Channel<ConversationEngine.Event>,
        val subscribers: MutableSet<SendChannel<ConversationEngine.Event>> = mutableSetOf()
    )
    
    /**
     * Start a supervisor command processing loop.
     */
    fun start() {
        scope.launch {
            for (command in commandChannel) {
                handleCommand(command)
            }
        }
    }
    
    /**
     * Send command to supervisor.
     */
    suspend fun send(command: Command) {
        commandChannel.send(command)
    }
    
    private suspend fun handleCommand(command: Command) {
        try {
            when (command) {
                is Command.Subscribe -> handleSubscribe(command)
                is Command.Unsubscribe -> handleUnsubscribe(command)
                is Command.SendUserMessage -> handleSendUserMessage(command)
                is Command.SwitchDefinition -> handleSwitchDefinition(command)
                is Command.Interrupt -> handleInterrupt(command)
                is Command.EditMessage -> handleEditMessage(command)
                is Command.DeleteMessages -> handleDeleteMessages(command)
                is Command.Reinitialize -> handleReinitialize(command)
                is Command.ShutdownAll -> handleShutdownAll()
            }
        } catch (e: Exception) {
            log.error(e) { "Error handling command: ${command::class.simpleName}" }
            // Send error to replyChannel if available
            when (command) {
                is Command.Subscribe -> command.replyChannel.send(ConversationEngine.Event.Error(e))
                is Command.SendUserMessage -> command.replyChannel.send(ConversationEngine.Event.Error(e))
                is Command.EditMessage -> command.replyChannel.send(ConversationEngine.Event.Error(e))
                is Command.DeleteMessages -> command.replyChannel.send(ConversationEngine.Event.Error(e))
                is Command.Reinitialize -> command.replyChannel.send(ConversationEngine.Event.Error(e))
                else -> {} // No replyChannel for other commands
            }
        }
    }
    
    private suspend fun handleSubscribe(command: Command.Subscribe) {
        mutex.withLock {
            val instance = getOrCreateEngine(command.conversationId)
            instance.subscribers.add(command.replyChannel)
            log.debug { "Subscriber added for conversation ${command.conversationId}, total: ${instance.subscribers.size}" }
        }
    }
    
    private suspend fun handleUnsubscribe(command: Command.Unsubscribe) {
        mutex.withLock {
            val instance = engines[command.conversationId]
            if (instance != null) {
                instance.subscribers.remove(command.replyChannel)
                log.debug { "Subscriber removed for conversation ${command.conversationId}, remaining: ${instance.subscribers.size}" }
            }
        }
    }
    
    private suspend fun handleSendUserMessage(command: Command.SendUserMessage) {
        val instance = mutex.withLock {
            getOrCreateEngine(command.conversationId).also {
                it.subscribers.add(command.replyChannel)
            }
        }
        
        // Send command to engine (outside lock to avoid deadlock)
        instance.engine.send(ConversationEngine.Command.SendUserMessage(command.content))
    }
    
    private suspend fun handleSwitchDefinition(command: Command.SwitchDefinition) {
        val instance = mutex.withLock {
            engines[command.conversationId].also {
                if (command.replyChannel != null) {
                    it?.subscribers?.add(command.replyChannel)
                }
            }
        }
        
        if (instance == null) {
            log.warn { "No engine found for conversation ${command.conversationId}, ignoring SwitchDefinition" }
            command.replyChannel?.send(ConversationEngine.Event.Error(IllegalStateException("No engine found")))
            return
        }
        
        instance.engine.send(ConversationEngine.Command.SwitchDefinition(command.definition))
    }
    
    private suspend fun handleInterrupt(command: Command.Interrupt) {
        val instance = mutex.withLock { engines[command.conversationId] }
        
        if (instance == null) {
            log.warn { "No engine found for conversation ${command.conversationId}, ignoring Interrupt" }
            return
        }
        
        instance.engine.send(ConversationEngine.Command.Interrupt)
    }
    
    private suspend fun handleEditMessage(command: Command.EditMessage) {
        val instance = mutex.withLock {
            getOrCreateEngine(command.conversationId).also {
                it.subscribers.add(command.replyChannel)
            }
        }
        
        instance.engine.send(
            ConversationEngine.Command.EditMessage(
                messageId = command.messageId,
                newContent = command.newContent
            )
        )
    }
    
    private suspend fun handleDeleteMessages(command: Command.DeleteMessages) {
        val instance = mutex.withLock {
            getOrCreateEngine(command.conversationId).also {
                it.subscribers.add(command.replyChannel)
            }
        }
        
        instance.engine.send(
            ConversationEngine.Command.DeleteMessages(
                messageIds = command.messageIds
            )
        )
    }
    
    private suspend fun handleReinitialize(command: Command.Reinitialize) {
        val instance = mutex.withLock {
            engines[command.conversationId].also {
                it?.subscribers?.add(command.replyChannel)
            }
        }
        
        if (instance == null) {
            log.warn { "No engine found for conversation ${command.conversationId}, ignoring Reinitialize" }
            command.replyChannel.send(ConversationEngine.Event.Error(IllegalStateException("No engine found")))
            return
        }
        
        instance.engine.send(ConversationEngine.Command.Initialize)
    }
    
    private suspend fun handleShutdownAll() {
        log.info { "Shutting down all engines (${engines.size} active)" }
        
        mutex.withLock {
            engines.values.forEach { instance ->
                try {
                    instance.engine.send(ConversationEngine.Command.Interrupt)
                    instance.eventChannel.close()
                    instance.subscribers.clear()
                } catch (e: Exception) {
                    log.error(e) { "Error shutting down engine" }
                }
            }
            
            engines.clear()
        }
        
        commandChannel.close()
    }
    
    /**
     * Get existing engine or create new one for conversation.
     * 
     * MUST be called under mutex lock.
     * 
     * NOTE: Only one engine exists per conversation at any time.
     * Engine works with conversation.currentThread, which updates on Edit/Delete.
     * 
     * If multiple UI tabs view the same conversation:
     * - They share the same engine instance
     * - Each tab subscribes to engine events
     * - Edit/Delete in one tab broadcasts to all tabs
     * 
     * Auto-cleanup: Engine is removed from memory when it emits Event.Completed.
     * Next command for same conversation will create fresh engine.
     */
    private fun getOrCreateEngine(conversationId: Conversation.Id): EngineInstance {
        return engines.getOrPut(conversationId) {
            createEngine(conversationId)
        }
    }
    
    /**
     * Create new ConversationEngine instance and start broadcasting.
     * 
     * Steps:
     * 1. Merge all available tools (built-in + MCP)
     * 2. Create event channel for this engine
     * 3. Create ConversationEngine with all dependencies
     * 4. Start engine command processing loop
     * 5. Start event broadcasting loop
     * 6. Send Initialize command (engine loads from DB)
     */
    private fun createEngine(conversationId: Conversation.Id): EngineInstance {
        log.info { "Creating new engine for conversation $conversationId" }
        
        try {
            // Merge tools from all sources
            val allTools = mutableListOf<ToolCallback>()
            allTools.addAll(toolCallbacks)
            allTools.addAll(mcpToolProvider.getToolCallbacks())
            
            log.debug { "Total tools available for engine: ${allTools.size}" }
            
            // Create event channel
            val eventChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)
            
            // Create engine
            val engine = ConversationEngine(
                conversationId = conversationId,
                scope = scope,
                
                // Repositories
                conversationRepository = conversationRepository,
                messageRepository = messageRepository,
                threadRepository = threadRepository,
                threadMessageRepository = threadMessageRepository,
                projectRepository = projectRepository,
                
                // Services
                chatModelProvider = chatModelProvider,
                agentDomainService = agentDomainService,
                parallelToolExecutor = parallelToolExecutor,
                messageConversionService = messageConversionService,
                
                // Tools
                availableTools = allTools,
                
                // Communication
                eventChannel = eventChannel,
                
                // Configuration
                maxIterations = maxIterations
            )
            
            val instance = EngineInstance(engine, eventChannel)
            
            // Start engine
            engine.start()
            
            // Start broadcasting events to all subscribers
            scope.launch {
                try {
                    for (event in eventChannel) {
                        // Broadcast to all subscribers
                        val subscribersCopy = mutex.withLock {
                            instance.subscribers.toList()
                        }
                        
                        subscribersCopy.forEach { subscriber ->
                            try {
                                subscriber.send(event)
                            } catch (e: Exception) {
                                log.error(e) { "Error sending event to subscriber for conversation $conversationId" }
                                // Remove failed subscriber
                                mutex.withLock {
                                    instance.subscribers.remove(subscriber)
                                }
                            }
                        }
                        
                        // Auto-cleanup on terminal events
                        when (event) {
                            is ConversationEngine.Event.Completed -> {
                                log.info { "Engine completed for conversation $conversationId, removing from memory" }
                                removeEngine(conversationId)
                                break
                            }
                            is ConversationEngine.Event.Error -> {
                                log.error(event.throwable) { "Engine error for conversation $conversationId, removing from memory" }
                                removeEngine(conversationId)
                                break
                            }
                            else -> {
                                // Continue broadcasting
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error(e) { "Error in broadcast loop for conversation $conversationId" }
                    removeEngine(conversationId)
                }
            }
            
            // Initialize engine (load from DB)
            scope.launch {
                try {
                    engine.send(ConversationEngine.Command.Initialize)
                } catch (e: Exception) {
                    log.error(e) { "Failed to initialize engine for $conversationId" }
                    eventChannel.send(ConversationEngine.Event.Error(e))
                }
            }
            
            log.info { "Engine created and initialized for conversation $conversationId" }
            
            return instance
            
        } catch (e: Exception) {
            log.error(e) { "Failed to create engine for $conversationId" }
            throw e
        }
    }
    
    /**
     * Remove engine from active engines map and close its event channel.
     * 
     * Called automatically when engine emits Event.Completed or Event.Error.
     * Engine instance is garbage collected after removal.
     */
    private suspend fun removeEngine(conversationId: Conversation.Id) {
        mutex.withLock {
            engines.remove(conversationId)?.let { instance ->
                instance.eventChannel.close()
                instance.subscribers.clear()
                log.info { "Engine removed for conversation $conversationId (${engines.size} active engines remaining)" }
            }
        }
    }
    
    /**
     * Commands accepted by supervisor.
     */
    sealed class Command {
        /**
         * Subscribe to conversation events.
         * 
         * Creates engine if needed, adds replyChannel to subscribers.
         * All future events will be broadcast to this channel.
         * 
         * @property conversationId target conversation
         * @property replyChannel channel for receiving ConversationEngine.Event stream
         */
        data class Subscribe(
            val conversationId: Conversation.Id,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Unsubscribe from conversation events.
         * 
         * Removes replyChannel from subscribers.
         * Engine remains alive if other subscribers exist.
         * 
         * @property conversationId target conversation
         * @property replyChannel channel to remove from subscribers
         */
        data class Unsubscribe(
            val conversationId: Conversation.Id,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Send user message to conversation.
         * 
         * Creates engine if needed, subscribes replyChannel, sends message.
         * Events are broadcast to all subscribers.
         * Engine is auto-removed from memory when it emits Event.Completed.
         * 
         * @property conversationId target conversation
         * @property content message content items
         * @property replyChannel channel for receiving ConversationEngine.Event stream
         */
        data class SendUserMessage(
            val conversationId: Conversation.Id,
            val content: List<Conversation.Message.ContentItem>,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Switch agent definition for conversation.
         * 
         * Engine updates conversation.agentDefinitionId and reloads prompts/tools.
         * Events are broadcast to all subscribers.
         * 
         * @property conversationId target conversation
         * @property definition new agent definition to use
         * @property replyChannel optional channel for receiving events
         */
        data class SwitchDefinition(
            val conversationId: Conversation.Id,
            val definition: AgentDefinition,
            val replyChannel: SendChannel<ConversationEngine.Event>? = null
        ) : Command()
        
        /**
         * Interrupt running engine.
         * 
         * Cancels LLM loop, engine remains alive for new commands.
         * 
         * @property conversationId target conversation
         */
        data class Interrupt(
            val conversationId: Conversation.Id
        ) : Command()
        
        /**
         * Edit message (creates thread fork).
         * 
         * Engine creates new thread, copies messages up to edit point,
         * adds edited message, updates conversation.currentThread.
         * Events are broadcast to all subscribers.
         * 
         * @property conversationId target conversation
         * @property messageId message to edit
         * @property newContent new content for message
         * @property replyChannel channel for receiving events
         */
        data class EditMessage(
            val conversationId: Conversation.Id,
            val messageId: Conversation.Message.Id,
            val newContent: List<Conversation.Message.ContentItem>,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Delete messages (creates thread fork).
         * 
         * Engine creates new thread without deleted messages,
         * updates conversation.currentThread.
         * Events are broadcast to all subscribers.
         * 
         * @property conversationId target conversation
         * @property messageIds messages to delete
         * @property replyChannel channel for receiving events
         */
        data class DeleteMessages(
            val conversationId: Conversation.Id,
            val messageIds: List<Conversation.Message.Id>,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Reinitialize engine (reload from DB).
         * 
         * Cancels running LLM loop, reloads conversation and messages from DB.
         * Useful when external changes were made to conversation.
         * Events are broadcast to all subscribers.
         * 
         * @property conversationId target conversation
         * @property replyChannel channel for receiving events
         */
        data class Reinitialize(
            val conversationId: Conversation.Id,
            val replyChannel: SendChannel<ConversationEngine.Event>
        ) : Command()
        
        /**
         * Shutdown all engines and close supervisor.
         * 
         * Interrupts all running engines, closes all channels.
         * Supervisor stops accepting commands after this.
         */
        object ShutdownAll : Command()
    }
}
