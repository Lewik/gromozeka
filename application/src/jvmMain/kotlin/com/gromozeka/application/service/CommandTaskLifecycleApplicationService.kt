package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEventConsumer
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CommandTaskLifecycleApplicationService(
    private val eventConsumer: CommandTaskLifecycleEventConsumer,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeDispatcher: ConversationRuntimeDispatcher,
    @Qualifier("applicationScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val started = AtomicBoolean(false)
    private val changedConversations = Channel<Conversation.Id>(Channel.UNLIMITED)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        coroutineScope.launch {
            eventConsumer.deliveries.collect { delivery ->
                try {
                    changedConversations.send(delivery.event.conversationId)
                    delivery.acknowledge()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) {
                        "Command lifecycle event handling failed; DB reconciliation will retry it: " +
                            "task=${delivery.event.taskId.value} error=${error.message}"
                    }
                    delivery.reject()
                }
            }
        }
        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                val conversations = linkedSetOf(changedConversations.receive())
                delay(COALESCING_WINDOW_MILLIS)
                while (true) {
                    val conversationId = changedConversations.tryReceive().getOrNull() ?: break
                    conversations += conversationId
                }
                conversations.forEach { conversationId ->
                    try {
                        reconcileConversation(conversationId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn(error) {
                            "Command lifecycle event reconciliation failed: " +
                                "conversation=${conversationId.value} error=${error.message}"
                        }
                    }
                }
            }
        }
        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    reconcileAll()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) { "Command lifecycle reconciliation failed: ${error.message}" }
                }
                delay(RECONCILIATION_INTERVAL_MILLIS)
            }
        }
    }

    internal suspend fun reconcileAll() {
        val pendingByConversation = runtimeCoordinator.findCommandTasks()
            .asSequence()
            .filter { it.requiresCompletionNotification() }
            .groupBy { it.conversationId }
        pendingByConversation.forEach { (_, tasks) ->
            submitPendingNotifications(tasks)
        }
    }

    private suspend fun reconcileConversation(conversationId: Conversation.Id) {
        val tasks = runtimeCoordinator.findCommandTasks()
            .filter { it.conversationId == conversationId }
        submitPendingNotifications(tasks)
    }

    private suspend fun submitPendingNotifications(tasks: List<CommandTask>) {
        tasks.asSequence()
            .filter { it.requiresCompletionNotification() }
            .sortedBy { it.completedAt }
            .forEach { task ->
                runtimeDispatcher.submitCommandTaskCompletion(task)
            }
    }

    private fun CommandTask.requiresCompletionNotification(): Boolean =
        isTerminal &&
            agentDefinitionId != null &&
            completionNotificationRequestedAt != null &&
            completionNotificationDeliveredAt == null

    private companion object {
        const val COALESCING_WINDOW_MILLIS = 250L
        const val RECONCILIATION_INTERVAL_MILLIS = 5_000L
    }
}
