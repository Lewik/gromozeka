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
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
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
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val commandTaskLifecycleEventPublisher: CommandTaskLifecycleEventPublisher,
    private val commandMonitorLifecycleEventPublisher: CommandMonitorLifecycleEventPublisher,
) : CommandRuntimeStateService {
    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult =
        runtimeCoordinator.upsertCommandTask(task)

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
    ): CommandMonitorSyncResult =
        runtimeCoordinator.synchronizeCommandMonitor(monitor, events)

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

    override suspend fun publishCommandTaskLifecycle(event: CommandTaskLifecycleEvent) {
        commandTaskLifecycleEventPublisher.publish(event)
    }

    override suspend fun publishCommandMonitorLifecycle(event: CommandMonitorLifecycleEvent) {
        commandMonitorLifecycleEventPublisher.publish(event)
    }

    override suspend fun publishSnapshot(conversationId: Conversation.Id) {
        runtimeEventBus.publish(
            ConversationRuntimeEvent.SnapshotUpdated(
                conversationId = conversationId,
                snapshot = runtimeCoordinator.snapshot(conversationId),
            )
        )
    }
}
