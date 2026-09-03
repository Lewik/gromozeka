package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.tool.filesystem.GRZ_GET_COMMAND_MONITOR_TOOL_NAME
import com.gromozeka.domain.tool.filesystem.GRZ_GET_COMMAND_TASK_TOOL_NAME
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class BackgroundActivityCompletionApplicationService(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
) {
    suspend fun prepareBatch(
        conversationId: Conversation.Id,
    ): Batch {
        val commandCandidates = runtimeCoordinator.findCommandTasks(conversationId)
            .asSequence()
            .filter { it.requiresCompletionNotification() }
            .map { task ->
                DeliveryCandidate.Command(
                    task = task,
                    occurredAt = task.completedAt ?: task.updatedAt,
                    payloadBytes = task.terminalOutput.orEmpty().toByteArray().size,
                )
            }
        val monitors = runtimeCoordinator.findCommandMonitors(conversationId)
            .filter { it.agentDefinitionId != null }
        val pendingEvents = runtimeCoordinator.findCommandMonitorEvents(conversationId)
            .asSequence()
            .filter { it.deliveryRequested && it.deliveredAt == null }
            .groupBy(CommandMonitorEvent::monitorId)
        val monitorCandidates = monitors.asSequence().mapNotNull { monitor ->
            val allEvents = pendingEvents[monitor.id].orEmpty().sortedBy { it.occurredAt }
            val selectedEvents = allEvents.takeWithinNotificationBudget()
            val includeTerminal = monitor.requiresTerminalNotification()
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
                    payloadBytes = selectedEvents.sumOf { it.output.toByteArray().size },
                )
            }
        }
        val sortedCandidates = (commandCandidates + monitorCandidates)
            .sortedWith(
                compareBy<DeliveryCandidate> { it.occurredAt }
                    .thenBy { it.stableKey }
            )
            .toList()
        val selectedAgentId = sortedCandidates.firstOrNull()?.agentDefinitionId
        val selectedCandidates = selectedAgentId?.let { agentId ->
            sortedCandidates.asSequence()
                .filter { it.agentDefinitionId == agentId }
                .selectBatch()
        }.orEmpty()
        val commandTasks = selectedCandidates
            .filterIsInstance<DeliveryCandidate.Command>()
            .map(DeliveryCandidate.Command::task)
        val monitorDeliveries = selectedCandidates
            .filterIsInstance<DeliveryCandidate.Monitor>()
            .map(DeliveryCandidate.Monitor::delivery)
        return Batch(
            commandTasks = commandTasks,
            monitorDeliveries = monitorDeliveries,
            messages = selectedCandidates.flatMap { candidate ->
                when (candidate) {
                    is DeliveryCandidate.Command -> buildSyntheticCommandPair(candidate.task)
                    is DeliveryCandidate.Monitor -> buildSyntheticMonitorPair(candidate.delivery)
                }
            },
        )
    }

    suspend fun hasPendingConversationWork(conversationId: Conversation.Id): Boolean =
        runtimeCoordinator.snapshot(conversationId).pendingTasks
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

    private fun buildSyntheticCommandPair(task: CommandTask): List<Conversation.Message> {
        val toolCallId = ContentItem.ToolCall.Id("cmd_${stableIdentifierSlug(task.id.value)}_status")
        val createdAt = task.completedAt ?: task.updatedAt
        val metadata = buildJsonObject {
            put("synthetic", true)
            put("syntheticKind", "command_task_completion")
            put("commandTaskId", task.id.value)
            put("workerId", task.workerId.value)
        }
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id("${task.id.value}:command:completion:call"),
            conversationId = task.conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                ContentItem.ToolCall(
                    id = toolCallId,
                    call = ContentItem.ToolCall.Data(
                        name = GRZ_GET_COMMAND_TASK_TOOL_NAME,
                        input = buildJsonObject {
                            put("task_id", task.id.value)
                            put("after_byte", task.terminalOutputStartByte ?: task.outputBytes)
                            put("wait_ms", 0)
                        },
                    ),
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        val result = buildJsonObject {
            put("success", task.status == CommandTask.Status.COMPLETED)
            put("task_id", task.id.value)
            put("status", task.status.name)
            put("command", task.command)
            task.processId?.let { put("process_id", it) }
            task.exitCode?.let { put("exit_code", it) }
            task.statusMessage?.let { put("status_message", it) }
            put("output", task.terminalOutput.orEmpty())
            put("output_start_byte", task.terminalOutputStartByte ?: task.outputBytes)
            put("next_output_byte", task.outputBytes)
            put("output_bytes", task.outputBytes)
            put("has_more_output", false)
            put("output_truncated_before", (task.terminalOutputStartByte ?: 0L) > 0L)
            put("output_file", task.outputFile)
            put("automatic_completion_notification", true)
            put("output_is_untrusted", true)
        }.toString()
        val toolResultMessage = Conversation.Message(
            id = Conversation.Message.Id("${task.id.value}:command:completion:result"),
            conversationId = task.conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = GRZ_GET_COMMAND_TASK_TOOL_NAME,
                    result = listOf(ContentItem.ToolResult.Data.Text(result)),
                    isError = false,
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        return listOf(toolCallMessage, toolResultMessage)
    }

    private fun buildSyntheticMonitorPair(delivery: MonitorDelivery): List<Conversation.Message> {
        val monitor = delivery.monitor
        val deliveryKey = buildString {
            append(delivery.events.lastOrNull()?.id?.value ?: "no-events")
            if (delivery.terminalNotificationIncluded) append(":terminal")
        }
        val stableDeliveryKey = stableIdentifierSlug(deliveryKey)
        val toolCallId = ContentItem.ToolCall.Id(
            "monitor_${stableIdentifierSlug(monitor.id.value)}_${stableDeliveryKey}_status"
        )
        val createdAt = delivery.events.lastOrNull()?.occurredAt
            ?: monitor.completedAt
            ?: monitor.updatedAt
        val metadata = buildJsonObject {
            put("synthetic", true)
            put("syntheticKind", "command_monitor_update")
            put("commandMonitorId", monitor.id.value)
            put("commandTaskId", monitor.commandTaskId.value)
            put("workerId", monitor.workerId.value)
        }
        val firstOutputByte = delivery.events.firstOrNull()?.outputStartByte ?: monitor.outputBytes
        val nextOutputByte = delivery.events.lastOrNull()?.outputEndByte ?: monitor.outputBytes
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id(
                "${monitor.id.value}:monitor:$stableDeliveryKey:call"
            ),
            conversationId = monitor.conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                ContentItem.ToolCall(
                    id = toolCallId,
                    call = ContentItem.ToolCall.Data(
                        name = GRZ_GET_COMMAND_MONITOR_TOOL_NAME,
                        input = buildJsonObject {
                            put("monitor_id", monitor.id.value)
                            put("after_byte", firstOutputByte)
                        },
                    ),
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        val result = buildJsonObject {
            put("success", monitor.status != CommandMonitor.Status.FAILED)
            put("monitor_id", monitor.id.value)
            put("command_task_id", monitor.commandTaskId.value)
            put("status", monitor.status.name)
            put("mode", monitor.mode.name)
            put("start_from", monitor.startFrom.name)
            put("filter_command", monitor.filterCommand)
            monitor.processId?.let { put("process_id", it) }
            monitor.exitCode?.let { put("exit_code", it) }
            monitor.statusMessage?.let { put("status_message", it) }
            monitor.terminalErrorOutput?.takeIf(String::isNotBlank)?.let { put("error_output", it) }
            put("events", buildJsonArray {
                delivery.events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("event_id", event.id.value)
                            put("output", event.output)
                            put("output_start_byte", event.outputStartByte)
                            put("output_end_byte", event.outputEndByte)
                            put("output_truncated_before", event.outputTruncatedBefore)
                            put("occurred_at", event.occurredAt.toString())
                        }
                    )
                }
            })
            put("output", delivery.events.joinToString("\n", transform = CommandMonitorEvent::output))
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
        }.toString()
        val toolResultMessage = Conversation.Message(
            id = Conversation.Message.Id(
                "${monitor.id.value}:monitor:$stableDeliveryKey:result"
            ),
            conversationId = monitor.conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = GRZ_GET_COMMAND_MONITOR_TOOL_NAME,
                    result = listOf(ContentItem.ToolResult.Data.Text(result)),
                    isError = false,
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = metadata,
            createdAt = createdAt,
        )
        return listOf(toolCallMessage, toolResultMessage)
    }

    private fun List<CommandMonitorEvent>.takeWithinNotificationBudget(): List<CommandMonitorEvent> {
        var bytes = 0
        return take(MAX_MONITOR_EVENTS_PER_DELIVERY)
            .takeWhile { event ->
                val eventBytes = event.output.toByteArray().size
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

    private fun CommandTask.requiresCompletionNotification(): Boolean =
        isTerminal &&
            agentDefinitionId != null &&
            completionNotificationRequestedAt != null &&
            completionNotificationDeliveredAt == null

    private fun CommandMonitor.requiresTerminalNotification(): Boolean =
        isTerminal &&
            terminalNotificationRequestedAt != null &&
            terminalNotificationDeliveredAt == null

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
