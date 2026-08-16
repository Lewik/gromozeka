package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandMonitorSyncResult
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.CommandTaskUpsertResult
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeStateSyncService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class ServerCommandRuntimeStateService(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeStateSyncService: ConversationRuntimeStateSyncService,
    private val commandTaskLifecycleEventPublisher: CommandTaskLifecycleEventPublisher,
    private val commandMonitorLifecycleEventPublisher: CommandMonitorLifecycleEventPublisher,
) : CommandRuntimeStateService {
    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult {
        val result = runtimeCoordinator.upsertCommandTask(task)
        val storedTask = result.task
        if (storedTask.isTerminal &&
            storedTask.agentDefinitionId != null &&
            storedTask.completionNotificationRequestedAt != null &&
            storedTask.completionNotificationDeliveredAt == null
        ) {
            commandTaskLifecycleEventPublisher.publish(
                CommandTaskLifecycleEvent(
                    conversationId = storedTask.conversationId,
                    taskId = storedTask.id,
                    status = storedTask.status,
                    occurredAt = storedTask.completedAt ?: storedTask.updatedAt,
                )
            )
        }
        return result
    }

    override suspend fun findCommandTasks(): List<CommandTask> =
        runtimeCoordinator.findCommandTasks()

    override suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? =
        runtimeCoordinator.findCommandTask(conversationId, taskId)

    override suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ): CommandMonitorSyncResult {
        val result = runtimeCoordinator.synchronizeCommandMonitor(monitor, events)
        val storedMonitor = result.monitor
        val hasPendingEvent = events.any { it.deliveryRequested && it.deliveredAt == null }
        val hasPendingTerminalNotification =
            storedMonitor.isTerminal &&
                storedMonitor.terminalNotificationRequestedAt != null &&
                storedMonitor.terminalNotificationDeliveredAt == null
        if (storedMonitor.agentDefinitionId != null && (hasPendingEvent || hasPendingTerminalNotification)) {
            commandMonitorLifecycleEventPublisher.publish(
                CommandMonitorLifecycleEvent(
                    conversationId = storedMonitor.conversationId,
                    monitorId = storedMonitor.id,
                    kind = if (hasPendingEvent) {
                        CommandMonitorLifecycleEvent.Kind.EVENTS_AVAILABLE
                    } else {
                        CommandMonitorLifecycleEvent.Kind.TERMINAL
                    },
                    occurredAt = storedMonitor.completedAt ?: storedMonitor.updatedAt,
                )
            )
        }
        return result
    }

    override suspend fun findCommandMonitors(): List<CommandMonitor> =
        runtimeCoordinator.findCommandMonitors()

    override suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? =
        runtimeCoordinator.findCommandMonitor(conversationId, monitorId)

    override suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): List<CommandMonitorEvent> =
        runtimeCoordinator.findCommandMonitorEvents(conversationId, monitorId)

    override suspend fun publishSnapshot(conversationId: Conversation.Id) {
        runtimeStateSyncService.invalidate(conversationId)
    }
}
