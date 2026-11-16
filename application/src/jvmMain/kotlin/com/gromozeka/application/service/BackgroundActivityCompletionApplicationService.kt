package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class BackgroundActivityCompletionApplicationService(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val origins: BackgroundActivityOriginRepository,
    private val continuationPolicy: BackgroundActivityContinuationPolicy,
) {
    suspend fun prepareBatch(
        conversationId: Conversation.Id,
        sourceKey: String,
    ): Batch {
        val failedSources = runtimeCoordinator.schedulingSnapshot(conversationId).failedBackgroundActivitySourceKeys()
        val commandCandidates = runtimeCoordinator.findCommandTasks(conversationId)
            .asSequence()
            .filter { it.hasPendingNotification(failedSources) }
            .map { task ->
                DeliveryCandidate.Command(
                    task = task,
                    occurredAt = task.completedAt ?: task.updatedAt,
                    payloadBytes = task.terminalOutputContent?.bytes()?.size ?: 0,
                )
            }
        val monitors = runtimeCoordinator.findCommandMonitors(conversationId)
            .filter { it.agentDefinitionId != null }
        val pendingEvents = runtimeCoordinator.findCommandMonitorEvents(conversationId)
            .asSequence()
            .filter { it.hasPendingNotification(failedSources) }
            .groupBy(CommandMonitorEvent::monitorId)
        val monitorCandidates = monitors.asSequence().mapNotNull { monitor ->
            val allEvents = pendingEvents[monitor.id].orEmpty().sortedBy { it.occurredAt }
            val sourceEventIndex = allEvents.indexOfFirst { it.notificationSourceKey() == sourceKey }
            val selectedEvents = (if (sourceEventIndex >= 0) allEvents.drop(sourceEventIndex) else allEvents)
                .takeWithinNotificationBudget()
            val includeTerminal = monitor.hasPendingNotification(failedSources)
            if (selectedEvents.isEmpty() && !includeTerminal) {
                null
            } else {
                val occurredAt = minOf(
                    selectedEvents.firstOrNull()?.occurredAt ?: Instant.DISTANT_FUTURE,
                    (monitor.completedAt ?: monitor.updatedAt).takeIf { includeTerminal }
                        ?: Instant.DISTANT_FUTURE,
                )
                DeliveryCandidate.Monitor(
                    delivery = MonitorDelivery(
                        monitor = monitor,
                        events = selectedEvents,
                        remainingEventCount = allEvents.size - selectedEvents.size,
                        terminalNotificationIncluded = includeTerminal,
                    ),
                    occurredAt = occurredAt,
                    payloadBytes = selectedEvents.sumOf { it.content.bytes().size },
                )
            }
        }
        val sortedCandidates = (commandCandidates + monitorCandidates)
            .sortedWith(
                compareBy<DeliveryCandidate> { it.occurredAt }
                    .thenBy { it.stableKey }
            )
            .toList()
        val provenance = origins.find(conversationId, sortedCandidates.mapTo(mutableSetOf()) { it.originKey })
        fun actor(candidate: DeliveryCandidate): User.Id? = provenance[candidate.originKey]
            ?.takeIf { origin ->
                origin.conversationId == conversationId && origin.agentDefinitionId == candidate.agentDefinitionId &&
                    when (candidate) {
                        is DeliveryCandidate.Command -> origin.workerId == candidate.task.workerId && origin.workspaceMountId == candidate.task.workspaceMountId
                        is DeliveryCandidate.Monitor -> origin.workerId == candidate.delivery.monitor.workerId && origin.workspaceMountId == candidate.delivery.monitor.workspaceMountId
                    }
            }?.actorUserId
        val source = sortedCandidates.singleOrNull { candidate ->
            when (candidate) {
                is DeliveryCandidate.Command -> candidate.task.notificationSourceKey() == sourceKey
                is DeliveryCandidate.Monitor -> candidate.delivery.events.any { it.notificationSourceKey() == sourceKey } ||
                    (candidate.delivery.terminalNotificationIncluded && candidate.delivery.monitor.notificationSourceKey() == sourceKey)
            }
        } ?: return Batch(emptyList(), emptyList(), emptyList())
        val selectedActor = actor(source)
        val selectedCandidates = (sequenceOf(source) + sortedCandidates.asSequence().filter { candidate ->
            candidate !== source && candidate.agentDefinitionId == source.agentDefinitionId && actor(candidate) == selectedActor
        }).selectBatch()
        val commandTasks = selectedCandidates
            .filterIsInstance<DeliveryCandidate.Command>()
            .map(DeliveryCandidate.Command::task)
        val monitorDeliveries = selectedCandidates
            .filterIsInstance<DeliveryCandidate.Monitor>()
            .map(DeliveryCandidate.Monitor::delivery)
        return Batch(
            actorUserId = selectedActor,
            commandTasks = commandTasks,
            monitorDeliveries = monitorDeliveries,
            messages = selectedCandidates.flatMap { candidate ->
                when (candidate) {
                    is DeliveryCandidate.Command -> buildCommandNotification(candidate.task)
                    is DeliveryCandidate.Monitor -> buildMonitorNotification(candidate.delivery)
                }
            },
        )
    }

    suspend fun canContinue(batch: Batch, conversation: Conversation): Boolean =
        batch.actorUserId?.let { continuationPolicy.allowed(it, conversation) } ?: false

    suspend fun hasPendingConversationWork(conversationId: Conversation.Id): Boolean =
        runtimeCoordinator.schedulingSnapshot(conversationId).pendingTasks
            .any { it.payload !is ConversationRuntimeTask.Payload.BackgroundActivityCompletion }

    suspend fun markDelivered(batch: Batch, deliveredAt: Instant) {
        batch.commandTasks.forEach { task ->
            runtimeCoordinator.upsertCommandTask(
                task.copy(
                    completionNotificationDeliveredAt = deliveredAt,
                    updatedAt = maxOf(task.updatedAt, deliveredAt),
                )
            )
        }
        val deliveredEventIds = batch.monitorDeliveries
            .flatMapTo(mutableSetOf()) { delivery -> delivery.events.map(CommandMonitorEvent::id) }
        if (deliveredEventIds.isNotEmpty()) {
            val conversationId = batch.monitorDeliveries.first().monitor.conversationId
            runtimeCoordinator.markCommandMonitorEventsDelivered(
                conversationId = conversationId,
                eventIds = deliveredEventIds,
                deliveredAt = deliveredAt,
            )
        }
        batch.monitorDeliveries
            .filter(MonitorDelivery::terminalNotificationIncluded)
            .forEach { delivery ->
                runtimeCoordinator.markCommandMonitorTerminalNotificationDelivered(
                    conversationId = delivery.monitor.conversationId,
                    monitorId = delivery.monitor.id,
                    deliveredAt = deliveredAt,
                )
            }
    }

    data class Batch(
        val commandTasks: List<CommandTask>,
        val monitorDeliveries: List<MonitorDelivery>,
        val messages: List<Conversation.Message>,
        val actorUserId: User.Id? = null,
    ) {
        val isEmpty: Boolean
            get() = commandTasks.isEmpty() && monitorDeliveries.isEmpty()

        val resultMessageId: Conversation.Message.Id
            get() = messages.last().id

        val agentDefinitionId: AgentDefinition.Id
            get() = commandTasks.firstNotNullOfOrNull(CommandTask::agentDefinitionId)
                ?: monitorDeliveries.firstNotNullOfOrNull { it.monitor.agentDefinitionId }
                ?: error("Empty background activity batch has no agent")
    }

    data class MonitorDelivery(
        val monitor: CommandMonitor,
        val events: List<CommandMonitorEvent>,
        val remainingEventCount: Int,
        val terminalNotificationIncluded: Boolean,
    )

    private fun buildCommandNotification(task: CommandTask): List<Conversation.Message> {
        val createdAt = task.completedAt ?: task.updatedAt
        val result = buildJsonObject {
            put("success", task.status == CommandTask.Status.COMPLETED)
            put("task_id", task.id.value)
            put("worker_id", task.workerId.value)
            put("workspace_mount_id", task.workspaceMountId.value)
            task.slotOrigin?.let { origin ->
                put("slot_number", origin.slotNumber); put("slot_id", origin.slotId); put("slot_lease_id", origin.leaseId)
            }
            put("status", task.status.name)
            put("command", task.command)
            task.description?.let { put("description", it) }
            put("survive_worker_restart", task.processLifetime == CommandTask.ProcessLifetime.RESUMABLE)
            task.processId?.let { put("process_id", it) }
            task.exitCode?.let { put("exit_code", it) }
            task.statusMessage?.let { put("status_message", it) }
            put("output_start_byte", task.terminalOutputStartByte ?: task.outputBytes)
            put("next_output_byte", task.outputBytes)
            put("output_bytes", task.outputBytes)
            put("has_more_output", false)
            put("output_truncated_before", (task.terminalOutputStartByte ?: 0L) > 0L)
            put("output_file", task.outputFile)
            put("automatic_completion_notification", true)
            put("output_is_untrusted", true)
        }
        return listOf(Conversation.Message(
            id = Conversation.Message.Id("${task.id.value}:command:completion:notification"),
            conversationId = task.conversationId,
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(ContentItem.RuntimeNotification(
                level = when (task.status) {
                    CommandTask.Status.FAILED -> ContentItem.RuntimeNotification.Level.ERROR
                    CommandTask.Status.CANCELLED -> ContentItem.RuntimeNotification.Level.WARNING
                    else -> ContentItem.RuntimeNotification.Level.INFO
                },
                content = task.description ?: "Command ${task.status.name.lowercase()}",
                kind = ContentItem.RuntimeNotification.Kind.COMMAND_COMPLETED,
                referenceId = task.id.value,
                details = result,
                attachments = listOf(ContentItem.ToolResult.Data.Base64Data(
                    data = (task.terminalOutputContent ?: com.gromozeka.domain.model.BinaryContent.EMPTY).base64,
                    mediaType = Conversation.Message.MediaType.parse("application/octet-stream"),
                    fileName = "command-${task.id.value}-${task.terminalOutputStartByte ?: task.outputBytes}-${task.outputBytes}.bin",
                )),
            )),
            createdAt = createdAt,
        ))
    }

    private fun buildMonitorNotification(delivery: MonitorDelivery): List<Conversation.Message> {
        val monitor = delivery.monitor
        val deliveryKey = buildString {
            append(delivery.events.lastOrNull()?.id?.value ?: "no-events")
            if (delivery.terminalNotificationIncluded) append(":terminal")
        }
        val stableDeliveryKey = stableIdentifierSlug(deliveryKey)
        val createdAt = delivery.events.lastOrNull()?.occurredAt
            ?: monitor.completedAt
            ?: monitor.updatedAt
        val firstOutputByte = delivery.events.firstOrNull()?.outputStartByte ?: monitor.outputBytes
        val nextOutputByte = delivery.events.lastOrNull()?.outputEndByte ?: monitor.outputBytes
        val result = buildJsonObject {
            put("success", monitor.status != CommandMonitor.Status.FAILED)
            put("monitor_id", monitor.id.value)
            put("worker_id", monitor.workerId.value)
            put("workspace_mount_id", monitor.workspaceMountId.value)
            put("command_task_id", monitor.commandTaskId.value)
            monitor.slotOrigin?.let { origin ->
                put("slot_number", origin.slotNumber); put("slot_id", origin.slotId); put("slot_lease_id", origin.leaseId)
            }
            put("status", monitor.status.name)
            put("mode", monitor.mode.name)
            put("start_from", monitor.startFrom.name)
            put("filter_command", monitor.filterCommand)
            monitor.processId?.let { put("process_id", it) }
            monitor.exitCode?.let { put("exit_code", it) }
            monitor.statusMessage?.let { put("status_message", it) }
            put("events", buildJsonArray {
                delivery.events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("event_id", event.id.value)
                            put("output_start_byte", event.outputStartByte)
                            put("output_end_byte", event.outputEndByte)
                            put("output_truncated_before", event.outputTruncatedBefore)
                            put("occurred_at", event.occurredAt.toString())
                        }
                    )
                }
            })
            put("output_start_byte", firstOutputByte)
            put("next_output_byte", nextOutputByte)
            put("output_bytes", monitor.outputBytes)
            put("pending_events_remaining", delivery.remainingEventCount)
            put("has_more_output", delivery.remainingEventCount > 0)
            put(
                "output_truncated_before",
                delivery.events.firstOrNull()?.let {
                    it.outputTruncatedBefore || it.outputStartByte > 0
                } ?: ((monitor.terminalOutputStartByte ?: 0L) > 0L),
            )
            put("output_file", monitor.outputFile)
            put("automatic_monitor_notification", true)
            put("output_is_untrusted", true)
        }
        return listOf(Conversation.Message(
            id = Conversation.Message.Id("${monitor.id.value}:monitor:$stableDeliveryKey:notification"),
            conversationId = monitor.conversationId,
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(ContentItem.RuntimeNotification(
                level = if (monitor.status == CommandMonitor.Status.FAILED) ContentItem.RuntimeNotification.Level.ERROR
                    else ContentItem.RuntimeNotification.Level.INFO,
                content = "Command monitor update",
                kind = ContentItem.RuntimeNotification.Kind.MONITOR_OUTPUT,
                referenceId = monitor.id.value,
                details = result,
                attachments = buildList {
                    delivery.events.forEach { event ->
                        add(ContentItem.ToolResult.Data.Base64Data(event.content.base64,
                            Conversation.Message.MediaType.parse("application/octet-stream"),
                            "monitor-${monitor.id.value}-${event.outputStartByte}-${event.outputEndByte}.bin"))
                    }
                    if (delivery.terminalNotificationIncluded) {
                        monitor.terminalOutputContent?.let {
                            add(ContentItem.ToolResult.Data.Base64Data(it.base64,
                                Conversation.Message.MediaType.parse("application/octet-stream"),
                                "monitor-${monitor.id.value}-tail-${monitor.terminalOutputStartByte}-${monitor.outputBytes}.bin"))
                        }
                        monitor.terminalErrorContent?.let {
                            add(ContentItem.ToolResult.Data.Base64Data(it.base64,
                                Conversation.Message.MediaType.parse("application/octet-stream"),
                                "monitor-${monitor.id.value}-stderr-tail.bin"))
                        }
                    }
                },
            )),
            createdAt = createdAt,
        ))
    }

    private fun List<CommandMonitorEvent>.takeWithinNotificationBudget(): List<CommandMonitorEvent> {
        var bytes = 0
        return take(MAX_MONITOR_EVENTS_PER_DELIVERY)
            .takeWhile { event ->
                val eventBytes = event.content.bytes().size
                val accepted = bytes == 0 || bytes + eventBytes <= MAX_MONITOR_EVENT_BYTES_PER_DELIVERY
                if (accepted) bytes += eventBytes
                accepted
            }
    }

    private fun Sequence<DeliveryCandidate>.selectBatch(): List<DeliveryCandidate> {
        val selected = mutableListOf<DeliveryCandidate>()
        var bytes = 0
        for (candidate in this) {
            if (selected.size >= MAX_DELIVERIES_PER_BATCH) break
            if (selected.isNotEmpty() && bytes + candidate.payloadBytes > MAX_NOTIFICATION_BYTES_PER_BATCH) break
            selected += candidate
            bytes += candidate.payloadBytes
        }
        return selected
    }

    private fun stableIdentifierSlug(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private sealed interface DeliveryCandidate {
        val agentDefinitionId: AgentDefinition.Id
        val occurredAt: Instant
        val payloadBytes: Int
        val stableKey: String
        val originKey: BackgroundActivityOrigin.Key
            get() = when (this) {
                is Command -> BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.COMMAND, task.id.value)
                is Monitor -> BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.MONITOR, delivery.monitor.id.value)
            }

        data class Command(
            val task: CommandTask,
            override val occurredAt: Instant,
            override val payloadBytes: Int,
        ) : DeliveryCandidate {
            override val agentDefinitionId: AgentDefinition.Id = requireNotNull(task.agentDefinitionId)
            override val stableKey: String = "command:${task.id.value}"
        }

        data class Monitor(
            val delivery: MonitorDelivery,
            override val occurredAt: Instant,
            override val payloadBytes: Int,
        ) : DeliveryCandidate {
            override val agentDefinitionId: AgentDefinition.Id = requireNotNull(delivery.monitor.agentDefinitionId)
            override val stableKey: String = "monitor:${delivery.monitor.id.value}"
        }
    }

    private companion object {
        const val MAX_MONITOR_EVENTS_PER_DELIVERY = 32
        const val MAX_MONITOR_EVENT_BYTES_PER_DELIVERY = 16 * 1024
        const val MAX_DELIVERIES_PER_BATCH = 16
        const val MAX_NOTIFICATION_BYTES_PER_BATCH = 64 * 1024
    }
}

/** Current access, not permissions captured when the process was started. */
fun interface BackgroundActivityContinuationPolicy {
    suspend fun allowed(actor: User.Id, conversation: Conversation): Boolean
}

@Service
class DefaultBackgroundActivityContinuationPolicy(
    private val users: UserDirectoryService,
    private val projects: ProjectAccessService,
) : BackgroundActivityContinuationPolicy {
    override suspend fun allowed(actor: User.Id, conversation: Conversation): Boolean =
        Conversation.Participant.User(actor) in conversation.participants &&
            users.findActiveById(actor)?.canUseAi == true &&
            projects.can(actor, conversation.projectId, ProjectPermission.WRITE)
}
