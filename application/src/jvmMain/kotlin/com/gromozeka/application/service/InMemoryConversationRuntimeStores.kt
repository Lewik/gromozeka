package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCommand
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeEventSubscription
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class InMemoryConversationRuntimeCoordinator : ConversationRuntimeCoordinator {
    private val mutex = Mutex()
    private val commandsByConversation = mutableMapOf<Conversation.Id, MutableList<ConversationRuntimeCommand>>()
    private val statesByConversation = mutableMapOf<Conversation.Id, ConversationExecutionState>()

    override suspend fun submit(command: ConversationRuntimeCommand): Boolean =
        mutex.withLock {
            val state = statesByConversation[command.conversationId]
            if (state?.status == ConversationExecutionState.Status.STOPPING ||
                state?.status == ConversationExecutionState.Status.INTERRUPTING
            ) {
                return@withLock false
            }
            if (command.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && state?.activeCommandId == null) {
                return@withLock false
            }

            val commands = commandsByConversation.getOrPut(command.conversationId) { mutableListOf() }
            commands.removeAll { it.userMessage.id == command.userMessage.id }
            commands.add(command)
            true
        }

    override suspend fun claimNextTurn(
        conversationId: Conversation.Id,
        workerId: String,
        leaseUntil: Instant?,
    ): ConversationRuntimeCommand? =
        mutex.withLock {
            val state = statesByConversation[conversationId]
            if (state != null && (state.status != ConversationExecutionState.Status.RUNNING || state.activeCommandId != null)) {
                return@withLock null
            }

            val commands = commandsByConversation[conversationId] ?: return@withLock null
            val commandIndex = commands.indexOfFirst { it.placement == QueuedMessagePlacement.END_OF_TURN }
            if (commandIndex < 0) {
                return@withLock null
            }

            val command = commands.removeAt(commandIndex)
            if (commands.isEmpty()) {
                commandsByConversation.remove(conversationId)
            }

            val now = Clock.System.now()
            statesByConversation[conversationId] = (state ?: ConversationExecutionState(
                conversationId = conversationId,
                status = ConversationExecutionState.Status.RUNNING,
                phase = ConversationExecutionState.Phase.BEFORE_LLM,
                activeCommandId = null,
                updatedAt = now,
            )).copy(
                status = ConversationExecutionState.Status.RUNNING,
                phase = ConversationExecutionState.Phase.BEFORE_LLM,
                activeCommandId = command.id,
                activeWorkerId = workerId,
                leaseUntil = leaseUntil,
                updatedAt = now,
            )

            command
        }

    override suspend fun completeActiveTurn(conversationId: Conversation.Id) {
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return
            val completedState = state.copy(
                activeCommandId = null,
                phase = ConversationExecutionState.Phase.END_OF_TURN,
                activeWorkerId = null,
                leaseUntil = null,
                updatedAt = Clock.System.now(),
            )
            statesByConversation[conversationId] = completedState

            if (completedState.status != ConversationExecutionState.Status.STOPPING &&
                completedState.status != ConversationExecutionState.Status.INTERRUPTING
            ) {
                commandsByConversation[conversationId]?.let { commands ->
                    val promotedActiveInsertions = commands
                        .filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                        .map { it.copy(placement = QueuedMessagePlacement.END_OF_TURN) }
                    if (promotedActiveInsertions.isNotEmpty()) {
                        val existingEndOfTurn = commands
                            .filterNot { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                        commands.clear()
                        commands.addAll(promotedActiveInsertions + existingEndOfTurn)
                    }
                }
            }
        }
    }

    override suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.activeCommandId != null) {
                return@withLock false
            }
            if (state.status == ConversationExecutionState.Status.STOPPING ||
                state.status == ConversationExecutionState.Status.INTERRUPTING
            ) {
                clearConversation(conversationId)
                return@withLock true
            }
            val hasPendingEndOfTurn = commandsByConversation[conversationId]
                ?.any { it.placement == QueuedMessagePlacement.END_OF_TURN }
                ?: false
            if (hasPendingEndOfTurn) {
                return@withLock false
            }
            statesByConversation.remove(conversationId)
            true
        }

    override suspend fun markPhase(
        conversationId: Conversation.Id,
        phase: ConversationExecutionState.Phase,
    ) {
        update(conversationId) { state ->
            state.copy(phase = phase, updatedAt = Clock.System.now())
        }
    }

    override suspend fun requestPause(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            when (state.status) {
                ConversationExecutionState.Status.RUNNING -> {
                    statesByConversation[conversationId] = state.copy(
                        status = ConversationExecutionState.Status.PAUSE_REQUESTED,
                        updatedAt = Clock.System.now(),
                    )
                    true
                }
                ConversationExecutionState.Status.PAUSE_REQUESTED,
                ConversationExecutionState.Status.PAUSED -> true
                ConversationExecutionState.Status.STOPPING,
                ConversationExecutionState.Status.INTERRUPTING -> false
            }
        }

    override suspend fun markPaused(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.status != ConversationExecutionState.Status.PAUSE_REQUESTED) {
                return@withLock false
            }
            statesByConversation[conversationId] = state.copy(
                status = ConversationExecutionState.Status.PAUSED,
                updatedAt = Clock.System.now(),
            )
            true
        }

    override suspend fun requestResume(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock false
            if (state.status != ConversationExecutionState.Status.PAUSED &&
                state.status != ConversationExecutionState.Status.PAUSE_REQUESTED
            ) {
                return@withLock false
            }
            statesByConversation[conversationId] = state.copy(
                status = ConversationExecutionState.Status.RUNNING,
                updatedAt = Clock.System.now(),
            )
            true
        }

    override suspend fun requestStop(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            requestTerminalStatus(conversationId, ConversationExecutionState.Status.STOPPING)
        }

    override suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean =
        mutex.withLock {
            requestTerminalStatus(conversationId, ConversationExecutionState.Status.INTERRUPTING)
        }

    override suspend fun fail(conversationId: Conversation.Id) {
        mutex.withLock {
            clearConversation(conversationId)
        }
    }

    override suspend fun abort(conversationId: Conversation.Id) {
        mutex.withLock {
            clearConversation(conversationId)
        }
    }

    override suspend fun find(conversationId: Conversation.Id): ConversationExecutionState? =
        mutex.withLock {
            statesByConversation[conversationId]
        }

    override suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean =
        mutex.withLock {
            val commands = commandsByConversation[conversationId] ?: return@withLock false
            val removed = commands.removeAll { it.userMessage.id == messageId }
            if (commands.isEmpty()) {
                commandsByConversation.remove(conversationId)
            }
            removed
        }

    override suspend fun takeActiveInsertions(
        conversationId: Conversation.Id,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeCommand> =
        mutex.withLock {
            val state = statesByConversation[conversationId] ?: return@withLock emptyList()
            if (state.activeCommandId == null) {
                return@withLock emptyList()
            }
            val commands = commandsByConversation[conversationId] ?: return@withLock emptyList()
            val ready = commands.filter { it.placement == placement }
            commands.removeAll(ready.toSet())
            if (commands.isEmpty()) {
                commandsByConversation.remove(conversationId)
            }
            ready
        }

    override suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeCommand> =
        mutex.withLock {
            commandsByConversation[conversationId]?.toList().orEmpty()
        }

    override suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot =
        mutex.withLock {
            ConversationRuntimeSnapshot(
                conversationId = conversationId,
                state = statesByConversation[conversationId],
                pendingCommands = commandsByConversation[conversationId]?.toList().orEmpty(),
            )
        }

    private suspend fun update(
        conversationId: Conversation.Id,
        change: (ConversationExecutionState) -> ConversationExecutionState,
    ): Boolean =
        mutex.withLock {
            val current = statesByConversation[conversationId] ?: return@withLock false
            statesByConversation[conversationId] = change(current)
            true
        }

    private fun terminalState(
        conversationId: Conversation.Id,
        status: ConversationExecutionState.Status,
    ): ConversationExecutionState =
        ConversationExecutionState(
            conversationId = conversationId,
            status = status,
            phase = ConversationExecutionState.Phase.END_OF_TURN,
            activeCommandId = null,
            updatedAt = Clock.System.now(),
        )

    private fun requestTerminalStatus(
        conversationId: Conversation.Id,
        status: ConversationExecutionState.Status,
    ): Boolean {
        val removedCommands = commandsByConversation.remove(conversationId)?.size ?: 0
        val state = statesByConversation[conversationId]
        if (state == null && removedCommands == 0) {
            return false
        }
        statesByConversation[conversationId] = (state ?: terminalState(conversationId, status)).copy(
            status = status,
            updatedAt = Clock.System.now(),
        )
        return true
    }

    private fun clearConversation(conversationId: Conversation.Id) {
        commandsByConversation.remove(conversationId)
        statesByConversation.remove(conversationId)
    }
}

@Service
class InMemoryConversationRuntimeEventBus : ConversationRuntimeEventBus {
    private val mutex = Mutex()
    private val subscribersByConversation = mutableMapOf<Conversation.Id, MutableSet<Channel<ConversationRuntimeEvent>>>()

    override suspend fun subscribe(conversationId: Conversation.Id): ConversationRuntimeEventSubscription {
        val channel = Channel<ConversationRuntimeEvent>(Channel.UNLIMITED)
        mutex.withLock {
            subscribersByConversation.getOrPut(conversationId) { mutableSetOf() }.add(channel)
        }

        return object : ConversationRuntimeEventSubscription {
            override val events: Flow<ConversationRuntimeEvent> = channel.receiveAsFlow()

            override suspend fun close() {
                mutex.withLock {
                    val subscribers = subscribersByConversation[conversationId]
                    subscribers?.remove(channel)
                    if (subscribers?.isEmpty() == true) {
                        subscribersByConversation.remove(conversationId)
                    }
                }
                channel.close()
            }
        }
    }

    override suspend fun publish(event: ConversationRuntimeEvent) {
        val subscribers = mutex.withLock {
            subscribersByConversation[event.conversationId]?.toList().orEmpty()
        }
        subscribers.forEach { subscriber ->
            subscriber.trySend(event)
        }
    }
}
