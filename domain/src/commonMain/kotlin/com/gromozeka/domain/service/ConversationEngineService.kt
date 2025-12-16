package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

/**
 * Service for managing conversation execution with AI.
 *
 * This is domain specification for conversation engine service.
 * Application layer provides implementation using actor-based architecture
 * (ConversationEngine + ConversationSupervisor).
 *
 * Service provides Flow-based API for UI, hiding actor/channel complexity.
 * All operations are asynchronous and support cancellation.
 *
 * Architecture:
 * - Domain defines interface (specification)
 * - Application implements using actors
 * - Presentation consumes via Flow API
 *
 * @see ConversationEngine for actor implementation
 * @see ConversationSupervisor for lifecycle management
 */
interface ConversationEngineService {

    /**
     * Subscribe to conversation events.
     *
     * Returns Flow that emits all events for this conversation.
     * Flow automatically unsubscribes when cancelled.
     *
     * Use this for long-lived subscriptions (e.g., UI tab displaying conversation).
     *
     * @param conversationId target conversation
     * @return Flow of ConversationEvent
     */
    suspend fun subscribe(conversationId: Conversation.Id): Flow<ConversationEvent>

    /**
     * Send user message to conversation.
     *
     * Returns Flow that emits events for this specific message send operation.
     * Flow completes when LLM loop finishes (Event.Completed).
     *
     * @param conversationId target conversation
     * @param content message content items
     * @return Flow of ConversationEvent
     */
    suspend fun sendUserMessage(
        conversationId: Conversation.Id,
        content: List<Conversation.Message.ContentItem>
    ): Flow<ConversationEvent>

    /**
     * Edit message in conversation (creates thread fork).
     *
     * Returns Flow that emits events for this edit operation.
     * Flow completes when thread fork is created.
     *
     * @param conversationId target conversation
     * @param messageId message to edit
     * @param newContent new content for message
     * @return Flow of ConversationEvent
     */
    suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>
    ): Flow<ConversationEvent>

    /**
     * Delete messages from conversation (creates thread fork).
     *
     * Returns Flow that emits events for this delete operation.
     * Flow completes when thread fork is created.
     *
     * @param conversationId target conversation
     * @param messageIds messages to delete
     * @return Flow of ConversationEvent
     */
    suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>
    ): Flow<ConversationEvent>

    /**
     * Switch agent definition for conversation.
     *
     * Fire-and-forget operation (no events returned).
     *
     * @param conversationId target conversation
     * @param definition new agent definition to use
     */
    suspend fun switchDefinition(
        conversationId: Conversation.Id,
        definition: AgentDefinition
    )

    /**
     * Interrupt running LLM loop for conversation.
     *
     * Fire-and-forget operation (no events returned).
     *
     * @param conversationId target conversation
     */
    suspend fun interrupt(conversationId: Conversation.Id)

    /**
     * Reinitialize conversation (reload from DB).
     *
     * Useful when external changes were made to conversation.
     * Returns Flow that emits events for reinitialization.
     *
     * @param conversationId target conversation
     * @return Flow of ConversationEvent
     */
    suspend fun reinitialize(conversationId: Conversation.Id): Flow<ConversationEvent>

    /**
     * Shutdown all engines and close service.
     *
     * Should be called on application shutdown.
     */
    suspend fun shutdownAll()

    /**
     * Events emitted by conversation engine.
     */
    sealed class ConversationEvent {
        /**
         * Engine initialized (loaded from DB).
         */
        object Initialized : ConversationEvent()

        /**
         * Message list changed.
         *
         * @property messages current message list
         */
        data class StateChanged(
            val messages: List<Conversation.Message>
        ) : ConversationEvent()

        /**
         * New message added.
         *
         * @property message added message
         */
        data class MessageEmitted(
            val message: Conversation.Message
        ) : ConversationEvent()

        /**
         * Agent definition switched.
         *
         * @property definition new agent definition
         */
        data class DefinitionSwitched(
            val definition: AgentDefinition
        ) : ConversationEvent()

        /**
         * Thread forked (Edit/Delete operation).
         *
         * @property newThreadId new thread ID
         * @property originalThreadId original thread ID
         */
        data class ThreadForked(
            val newThreadId: Conversation.Thread.Id,
            val originalThreadId: Conversation.Thread.Id
        ) : ConversationEvent()

        /**
         * Error occurred in engine.
         *
         * @property throwable error cause
         */
        data class Error(
            val throwable: Throwable
        ) : ConversationEvent()

        /**
         * LLM loop interrupted.
         */
        object Interrupted : ConversationEvent()

        /**
         * LLM loop completed (terminal event).
         */
        object Completed : ConversationEvent()
    }
}
