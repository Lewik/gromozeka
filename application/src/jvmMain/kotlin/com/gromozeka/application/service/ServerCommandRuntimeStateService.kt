package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandJournal
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
import com.gromozeka.shared.utils.sha256
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
    private val commandJournal: CommandJournal? = null,
) : CommandRuntimeStateService {
    override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult {
        val result = runtimeCoordinator.upsertCommandTask(task)
        val storedTask = result.task
        recordCommandJournal(storedTask)
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

    private suspend fun recordCommandJournal(task: CommandTask) {
        val journal = commandJournal ?: return
        val sourceCallId = task.launchReference?.toolCallId?.let(ContentItem.ToolCall::Id)
            ?: return // An older or non-tool command can legitimately predate the optional journal.
        val call = journal.findCallBySource(task.conversationId, sourceCallId) ?: return
        val entry = requireNotNull(journal.findEntry(call.entryId)) {
            "Command Journal call ${call.id.value} lost its entry ${call.entryId.value}"
        }
        val eventKind = when (task.status) {
            CommandTask.Status.WORKING -> CommandJournal.Event.Kind.STARTED
            CommandTask.Status.COMPLETED -> CommandJournal.Event.Kind.COMPLETED
            CommandTask.Status.FAILED -> CommandJournal.Event.Kind.FAILED
            CommandTask.Status.CANCELLED -> CommandJournal.Event.Kind.CANCELLED
        }
        if (task.status == CommandTask.Status.WORKING && task.processStartedAt == null) return
        val event = CommandJournal.Event(
            id = CommandJournal.Event.Id(
                "event:${"${entry.id.value}:${task.id.value}:${eventKind.name}".sha256()}"
            ),
            entryId = entry.id,
            callId = call.id,
            sourceKey = "command-task:${task.id.value}:${eventKind.name.lowercase()}",
            kind = eventKind,
            occurredAt = when (task.status) {
                CommandTask.Status.WORKING -> requireNotNull(task.processStartedAt)
                else -> task.completedAt ?: task.updatedAt
            },
            commandTaskId = task.id,
            statusMessage = task.statusMessage.takeIf { task.isTerminal },
            exitCode = task.exitCode,
        )
        val terminalBytes = task.terminalOutputContent?.bytes()
        val decodedOutput = terminalBytes?.let { bytes ->
            (0..minOf(3, bytes.size)).firstNotNullOfOrNull { offset ->
                runCatching {
                    offset to bytes.copyOfRange(offset, bytes.size).decodeToString(throwOnInvalidSequence = true)
                }.getOrNull()
            }
        }
        val output = if (!task.isTerminal) null else decodedOutput?.takeIf { it.second.isNotEmpty() }?.let { (offset, text) ->
            val retainedStart = requireNotNull(task.terminalOutputStartByte)
            val start = retainedStart + offset
            val end = retainedStart + requireNotNull(terminalBytes).size
            CommandJournal.Content(
                id = CommandJournal.Content.Id(text.sha256()),
                entryId = entry.id,
                callId = call.id,
                eventId = event.id,
                kind = CommandJournal.Content.Kind.OUTPUT,
                text = text,
                startByte = start,
                endByte = end,
                totalBytes = task.outputBytes,
                truncatedBefore = start > 0,
                truncatedAfter = end < task.outputBytes,
                createdAt = event.occurredAt,
            )
        }
        journal.append(event, listOfNotNull(output))
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
