package com.gromozeka.application.service

import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorLifecycleEventStream
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEventStream
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
    private val origins: BackgroundActivityOriginRepository,
) {
    private val log = KLoggers.logger(this)
    private val inventoryLog = KLoggers.logger("com.gromozeka.runtime.commandInventory")
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
        inventoryLog.debug { "event=command_inventory_request scope=global operation=tasks_and_monitors reason=server_startup_reconciliation" }
        val commandTasks = runtimeCoordinator.findCommandTasks()
            .filter { it.hasPendingNotification(emptySet()) }
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

    internal fun deliveryFinished(conversationId: Conversation.Id) {
        check(changedConversations.trySend(conversationId).isSuccess) {
            "Background activity reconciliation queue is closed"
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
        val failedSources = runtimeCoordinator.schedulingSnapshot(conversationId).failedBackgroundActivitySourceKeys()
        val conversationMonitors = monitors
            .filter { it.conversationId == conversationId && it.agentDefinitionId != null }
        val monitorsById = conversationMonitors.associateBy { it.id }
        val monitorIds = monitorsById.keys
        val pendingEvents = runtimeCoordinator.findCommandMonitorEvents(conversationId)
            .filter {
                it.monitorId in monitorIds && it.hasPendingNotification(failedSources)
            }
        val source = buildList {
            commandTasks.asSequence()
                .filter { it.conversationId == conversationId }
                .filter { it.hasPendingNotification(failedSources) }
                .mapTo(this) {
                    PendingSource(
                        key = it.notificationSourceKey(),
                        occurredAt = it.completedAt ?: it.updatedAt,
                        agentId = requireNotNull(it.agentDefinitionId),
                        originKey = BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.COMMAND, it.id.value),
                    )
                }
            pendingEvents.mapTo(this) {
                PendingSource(
                    key = it.notificationSourceKey(),
                    occurredAt = it.occurredAt,
                    agentId = requireNotNull(monitorsById.getValue(it.monitorId).agentDefinitionId),
                    originKey = BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.MONITOR, it.monitorId.value),
                )
            }
            conversationMonitors.asSequence()
                .filter { it.hasPendingNotification(failedSources) }
                .mapTo(this) {
                    PendingSource(
                        key = it.notificationSourceKey(),
                        occurredAt = it.completedAt ?: it.updatedAt,
                        agentId = requireNotNull(it.agentDefinitionId),
                        originKey = BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.MONITOR, it.id.value),
                    )
                }
        }.minWithOrNull(compareBy(PendingSource::occurredAt, PendingSource::key))
            ?: return
        val origin = origins.find(conversationId, setOf(source.originKey))[source.originKey]
        check(origin == null || origin.conversationId == conversationId && origin.agentDefinitionId == source.agentId) {
            "Background activity recipient does not match its launch origin"
        }
        if (!runtimeDispatcher.submitBackgroundActivityCompletion(conversationId, source.key, source.agentId, origin?.actorUserId)) {
            log.debug { "Background activity delivery not admitted: conversation=${conversationId.value} source=${source.key}" }
        }
    }

    private data class PendingSource(
        val key: String,
        val occurredAt: Instant,
        val agentId: AgentDefinition.Id,
        val originKey: BackgroundActivityOrigin.Key,
    )

}
