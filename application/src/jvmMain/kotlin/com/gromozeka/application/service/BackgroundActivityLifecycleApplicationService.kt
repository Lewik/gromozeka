package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEventStream
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEventStream
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
import kotlin.time.Instant
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
class BackgroundActivityLifecycleApplicationService(
    private val commandEventStream: CommandTaskLifecycleEventStream,
    private val monitorEventStream: CommandMonitorLifecycleEventStream,
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
        collectCommandEvents()
        collectMonitorEvents()
        reconcileChangedConversations()
        coroutineScope.launch {
            try {
                reconcileAll()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn(error) { "Background activity startup reconciliation failed: ${error.message}" }
            }
        }
    }

    internal suspend fun reconcileAll() {
        val commandTasks = runtimeCoordinator.findCommandTasks()
            .filter { it.requiresCompletionNotification() }
        val monitors = runtimeCoordinator.findCommandMonitors()
            .filter { it.agentDefinitionId != null }
        val conversationIds = buildSet {
            commandTasks.mapTo(this) { it.conversationId }
            monitors.mapTo(this) { it.conversationId }
        }
        conversationIds.forEach { conversationId ->
            submitPendingNotification(conversationId, commandTasks, monitors)
        }
    }

    private fun collectCommandEvents() {
        coroutineScope.launch {
            commandEventStream.events.collect { event ->
                try {
                    changedConversations.send(event.conversationId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) {
                        "Command lifecycle event handling failed; startup reconciliation can recover it: " +
                            "task=${event.taskId.value} error=${error.message}"
                    }
                }
            }
        }
    }

    private fun collectMonitorEvents() {
        coroutineScope.launch {
            monitorEventStream.events.collect { event ->
                try {
                    changedConversations.send(event.conversationId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) {
                        "Command monitor lifecycle event handling failed; startup reconciliation can recover it: " +
                            "monitor=${event.monitorId.value} error=${error.message}"
                    }
                }
            }
        }
    }

    private fun reconcileChangedConversations() {
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
                        submitPendingNotification(conversationId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.warn(error) {
                            "Background activity reconciliation failed: " +
                                "conversation=${conversationId.value} error=${error.message}"
                        }
                    }
                }
            }
        }
    }

    private suspend fun submitPendingNotification(conversationId: Conversation.Id) {
        submitPendingNotification(
            conversationId = conversationId,
            commandTasks = runtimeCoordinator.findCommandTasks(conversationId),
            monitors = runtimeCoordinator.findCommandMonitors(conversationId),
        )
    }

    private suspend fun submitPendingNotification(
        conversationId: Conversation.Id,
        commandTasks: List<CommandTask>,
        monitors: List<CommandMonitor>,
    ) {
        val conversationMonitors = monitors
            .filter { it.conversationId == conversationId && it.agentDefinitionId != null }
        val monitorIds = conversationMonitors.mapTo(mutableSetOf()) { it.id }
        val pendingEvents = runtimeCoordinator.findCommandMonitorEvents(conversationId)
            .filter {
                it.monitorId in monitorIds &&
                    it.deliveryRequested &&
                    it.deliveredAt == null
            }
        val source = buildList {
            commandTasks.asSequence()
                .filter { it.conversationId == conversationId }
                .filter { it.requiresCompletionNotification() }
                .mapTo(this) {
                    PendingSource(
                        key = "command-task:${it.id.value}",
                        occurredAt = it.completedAt ?: it.updatedAt,
                    )
                }
            pendingEvents.mapTo(this) {
                PendingSource(
                    key = "command-monitor-event:${it.id.value}",
                    occurredAt = it.occurredAt,
                )
            }
            conversationMonitors.asSequence()
                .filter { it.requiresTerminalNotification() }
                .mapTo(this) {
                    PendingSource(
                        key = "command-monitor-terminal:${it.id.value}:${it.completedAt}",
                        occurredAt = it.completedAt ?: it.updatedAt,
                    )
                }
        }.minWithOrNull(compareBy(PendingSource::occurredAt, PendingSource::key))
            ?: return
        runtimeDispatcher.submitBackgroundActivityCompletion(conversationId, source.key)
    }

    private fun CommandTask.requiresCompletionNotification(): Boolean =
        isTerminal &&
            agentDefinitionId != null &&
            completionNotificationRequestedAt != null &&
            completionNotificationDeliveredAt == null

    private fun CommandMonitor.requiresTerminalNotification(): Boolean =
        isTerminal &&
            terminalNotificationRequestedAt != null &&
            terminalNotificationDeliveredAt == null

    private data class PendingSource(
        val key: String,
        val occurredAt: Instant,
    )

    private companion object {
        const val COALESCING_WINDOW_MILLIS = 250L
    }
}
