package com.gromozeka.application.service

import com.gromozeka.application.actor.ConversationEngine
import com.gromozeka.application.actor.ConversationSupervisor
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationEngineService
import com.gromozeka.domain.service.ConversationEngineService.ConversationEvent
import klog.KLoggers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.springframework.stereotype.Service

/**
 * Implementation of ConversationEngineService using actor-based architecture.
 *
 * Provides Flow-based API for conversation events, hiding actor/channel complexity.
 *
 * Architecture:
 * - Implements domain interface (ConversationEngineService)
 * - Uses ConversationSupervisor for actor lifecycle management
 * - Converts ConversationEngine.Event to ConversationEvent
 * - Manages subscription lifecycle (auto-unsubscribe)
 *
 * Responsibilities:
 * - Create event channels for commands
 * - Convert channel-based API to Flow-based API
 * - Map actor events to domain events
 * - Forward commands to supervisor
 *
 * Does NOT:
 * - Manage engine lifecycle (supervisor does it)
 * - Persist data (ConversationEngine does it)
 * - Process events (UI does it)
 */
@Service
class ConversationService(
    private val supervisor: ConversationSupervisor
) : ConversationEngineService {
    private val log = KLoggers.logger(this)

    override suspend fun subscribe(conversationId: Conversation.Id): Flow<ConversationEvent> = flow {
        val replyChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)

        try {
            // Subscribe to supervisor
            supervisor.send(ConversationSupervisor.Command.Subscribe(conversationId, replyChannel))

            // Emit all events, converting to domain events
            for (event in replyChannel) {
                emit(event.toDomainEvent())
            }
        } finally {
            // Unsubscribe when Flow is cancelled
            try {
                supervisor.send(ConversationSupervisor.Command.Unsubscribe(conversationId, replyChannel))
                replyChannel.close()
            } catch (e: Exception) {
                log.error(e) { "Error unsubscribing from conversation $conversationId" }
            }
        }
    }

    override suspend fun sendUserMessage(
        conversationId: Conversation.Id,
        content: List<Conversation.Message.ContentItem>
    ): Flow<ConversationEvent> = flow {
        val replyChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)

        try {
            // Send command
            supervisor.send(
                ConversationSupervisor.Command.SendUserMessage(
                    conversationId = conversationId,
                    content = content,
                    replyChannel = replyChannel
                )
            )

            // Emit all events until terminal event
            for (event in replyChannel) {
                emit(event.toDomainEvent())

                // Stop on terminal events
                when (event) {
                    is ConversationEngine.Event.Completed,
                    is ConversationEngine.Event.Error -> break
                    else -> {}
                }
            }
        } finally {
            replyChannel.close()
        }
    }

    override suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>
    ): Flow<ConversationEvent> = flow {
        val replyChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)

        try {
            supervisor.send(
                ConversationSupervisor.Command.EditMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    newContent = newContent,
                    replyChannel = replyChannel
                )
            )

            // Emit events until we get ThreadForked or Error
            for (event in replyChannel) {
                emit(event.toDomainEvent())

                when (event) {
                    is ConversationEngine.Event.ThreadForked,
                    is ConversationEngine.Event.Error -> break
                    else -> {}
                }
            }
        } finally {
            replyChannel.close()
        }
    }

    override suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>
    ): Flow<ConversationEvent> = flow {
        val replyChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)

        try {
            supervisor.send(
                ConversationSupervisor.Command.DeleteMessages(
                    conversationId = conversationId,
                    messageIds = messageIds,
                    replyChannel = replyChannel
                )
            )

            // Emit events until we get ThreadForked or Error
            for (event in replyChannel) {
                emit(event.toDomainEvent())

                when (event) {
                    is ConversationEngine.Event.ThreadForked,
                    is ConversationEngine.Event.Error -> break
                    else -> {}
                }
            }
        } finally {
            replyChannel.close()
        }
    }

    override suspend fun switchDefinition(
        conversationId: Conversation.Id,
        definition: AgentDefinition
    ) {
        supervisor.send(
            ConversationSupervisor.Command.SwitchDefinition(
                conversationId = conversationId,
                definition = definition
            )
        )
    }

    override suspend fun interrupt(conversationId: Conversation.Id) {
        supervisor.send(
            ConversationSupervisor.Command.Interrupt(conversationId)
        )
    }

    override suspend fun reinitialize(conversationId: Conversation.Id): Flow<ConversationEvent> = flow {
        val replyChannel = Channel<ConversationEngine.Event>(Channel.UNLIMITED)

        try {
            supervisor.send(
                ConversationSupervisor.Command.Reinitialize(
                    conversationId = conversationId,
                    replyChannel = replyChannel
                )
            )

            // Emit events until initialized or error
            for (event in replyChannel) {
                emit(event.toDomainEvent())

                when (event) {
                    is ConversationEngine.Event.Initialized,
                    is ConversationEngine.Event.Error -> break
                    else -> {}
                }
            }
        } finally {
            replyChannel.close()
        }
    }

    override suspend fun shutdownAll() {
        supervisor.send(ConversationSupervisor.Command.ShutdownAll)
    }

    /**
     * Convert actor event to domain event.
     */
    private fun ConversationEngine.Event.toDomainEvent(): ConversationEvent {
        return when (this) {
            is ConversationEngine.Event.Initialized -> ConversationEvent.Initialized
            is ConversationEngine.Event.StateChanged -> ConversationEvent.StateChanged(messages)
            is ConversationEngine.Event.MessageEmitted -> ConversationEvent.MessageEmitted(message)
            is ConversationEngine.Event.DefinitionSwitched -> ConversationEvent.DefinitionSwitched(definition)
            is ConversationEngine.Event.ThreadForked -> ConversationEvent.ThreadForked(newThreadId, originalThreadId)
            is ConversationEngine.Event.Error -> ConversationEvent.Error(throwable)
            is ConversationEngine.Event.Interrupted -> ConversationEvent.Interrupted
            is ConversationEngine.Event.Completed -> ConversationEvent.Completed
        }
    }
}
