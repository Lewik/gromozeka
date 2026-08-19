package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorOutput
import com.gromozeka.domain.service.CommandMonitorService
import com.gromozeka.domain.service.CommandMonitorSpec
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.CancelCommandMonitorRequest
import com.gromozeka.domain.tool.filesystem.GetCommandMonitorRequest
import com.gromozeka.domain.tool.filesystem.GrzCancelCommandMonitorTool
import com.gromozeka.domain.tool.filesystem.GrzGetCommandMonitorTool
import com.gromozeka.domain.tool.filesystem.GrzListCommandsAndMonitorsTool
import com.gromozeka.domain.tool.filesystem.GrzMonitorCommandTool
import com.gromozeka.domain.tool.filesystem.ListCommandsAndMonitorsRequest
import com.gromozeka.domain.tool.filesystem.MonitorCommandRequest
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzMonitorCommandToolImpl(
    private val commandMonitorService: CommandMonitorService,
) : GrzMonitorCommandTool {
    override fun execute(request: MonitorCommandRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            commandMonitorService.start(
                spec = CommandMonitorSpec(
                    commandTaskId = CommandTask.Id(request.task_id),
                    filterCommand = request.filter_command,
                    mode = request.mode,
                    startFrom = request.start_from,
                ),
                context = context ?: error("Tool execution context is required"),
            ).toResult()
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzGetCommandMonitorToolImpl(
    private val commandMonitorService: CommandMonitorService,
    private val runtimeState: CommandRuntimeStateService,
) : GrzGetCommandMonitorTool {
    override fun execute(request: GetCommandMonitorRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            val conversationId = context.requiredConversationId()
            val monitorId = CommandMonitor.Id(request.monitor_id)
            commandMonitorService.get(
                conversationId = conversationId,
                monitorId = monitorId,
                afterByte = request.after_byte,
                waitMillis = request.wait_ms,
            )?.let { output ->
                val matchingEvents = runtimeState.findCommandMonitorEvents(conversationId, monitorId)
                    .filter {
                        it.outputEndByte > output.outputStartByte &&
                            it.outputEndByte <= output.nextOutputByte
                    }
                output.toResult(
                    events = matchingEvents.take(MAX_EVENTS_PER_RESULT),
                    hasMoreEvents = matchingEvents.size > MAX_EVENTS_PER_RESULT,
                )
            } ?: mapOf(
                "success" to false,
                "error" to "Command monitor not found: ${request.monitor_id}",
            )
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCancelCommandMonitorToolImpl(
    private val commandMonitorService: CommandMonitorService,
) : GrzCancelCommandMonitorTool {
    override fun execute(request: CancelCommandMonitorRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            val conversationId = context.requiredConversationId()
            val monitorId = CommandMonitor.Id(request.monitor_id)
            val cancelled = commandMonitorService.cancel(conversationId, monitorId)
            mapOf(
                "success" to cancelled,
                "monitor_id" to monitorId.value,
                "status" to if (cancelled) CommandMonitor.Status.CANCELLED.name else "UNCHANGED",
            )
        }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class GrzListCommandsAndMonitorsToolImpl(
    private val runtimeState: CommandRuntimeStateService,
) : GrzListCommandsAndMonitorsTool {
    override fun execute(
        request: ListCommandsAndMonitorsRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> = runBlocking {
        val conversationId = context.requiredConversationId()
        val commands = runtimeState.findCommandTasks()
            .filter { it.conversationId == conversationId }
            .filter { request.include_terminal || !it.isTerminal }
            .sortedBy { it.createdAt }
        val monitors = runtimeState.findCommandMonitors()
            .filter { it.conversationId == conversationId }
            .filter { request.include_terminal || !it.isTerminal }
            .sortedBy { it.createdAt }
        val monitorIdsByTask = monitors.groupBy(CommandMonitor::commandTaskId)
            .mapValues { (_, taskMonitors) -> taskMonitors.map { it.id.value } }
        mapOf(
            "success" to true,
            "conversation_id" to conversationId.value,
            "commands" to commands.map { it.toSummary(monitorIdsByTask[it.id].orEmpty()) },
            "monitors" to monitors.map(CommandMonitor::toSummary),
            "command_count" to commands.size,
            "monitor_count" to monitors.size,
        )
    }
}

private fun CommandMonitor.toResult(): Map<String, Any> = buildMap {
    put("success", status == CommandMonitor.Status.WORKING || status == CommandMonitor.Status.COMPLETED)
    put("monitor_id", id.value)
    put("command_task_id", commandTaskId.value)
    put("status", status.name)
    put("mode", mode.name)
    put("start_from", startFrom.name)
    put("filter_command", filterCommand)
    processId?.let { put("process_id", it) }
    statusMessage?.let { put("status_message", it) }
    put("output_bytes", outputBytes)
    put("event_count", eventCount)
    put("automatic_delivery", agentDefinitionId != null)
    put("output_file", outputFile)
    put("error_file", errorFile)
    put("output_is_untrusted", true)
}

private fun CommandMonitorOutput.toResult(
    events: List<CommandMonitorEvent>,
    hasMoreEvents: Boolean,
): Map<String, Any> = buildMap {
    putAll(monitor.toResult())
    monitor.exitCode?.let { put("exit_code", it) }
    monitor.terminalErrorOutput?.takeIf(String::isNotBlank)?.let { put("error_output", it) }
    put("output", output)
    put("output_start_byte", outputStartByte)
    put("next_output_byte", nextOutputByte)
    put("has_more_output", hasMoreOutput)
    put(
        "events",
        events.map { event ->
            mapOf(
                "event_id" to event.id.value,
                "output" to event.output,
                "output_start_byte" to event.outputStartByte,
                "output_end_byte" to event.outputEndByte,
                "output_truncated_before" to event.outputTruncatedBefore,
                "occurred_at" to event.occurredAt.toString(),
            )
        },
    )
    put("has_more_events", hasMoreEvents)
}

private fun CommandTask.toSummary(monitorIds: List<String>): Map<String, Any> = buildMap {
    put("task_id", id.value)
    put("status", status.name)
    put("command", command)
    put("worker_id", workerId.value)
    put("workspace_mount_id", workspaceMountId.value)
    processId?.let { put("process_id", it) }
    exitCode?.let { put("exit_code", it) }
    statusMessage?.let { put("status_message", it) }
    put("output_bytes", outputBytes)
    put("monitor_ids", monitorIds)
    put("created_at", createdAt.toString())
    put("updated_at", updatedAt.toString())
    completedAt?.let { put("completed_at", it.toString()) }
}

private fun CommandMonitor.toSummary(): Map<String, Any> = buildMap {
    put("monitor_id", id.value)
    put("command_task_id", commandTaskId.value)
    put("status", status.name)
    put("mode", mode.name)
    put("start_from", startFrom.name)
    put("filter_command", filterCommand)
    put("worker_id", workerId.value)
    put("workspace_mount_id", workspaceMountId.value)
    processId?.let { put("process_id", it) }
    exitCode?.let { put("exit_code", it) }
    statusMessage?.let { put("status_message", it) }
    put("output_bytes", outputBytes)
    put("event_count", eventCount)
    lastEventPreview?.let { put("last_event_preview", it) }
    put("automatic_delivery", agentDefinitionId != null)
    put("created_at", createdAt.toString())
    put("updated_at", updatedAt.toString())
    completedAt?.let { put("completed_at", it.toString()) }
}

private fun ToolExecutionContext?.requiredConversationId(): Conversation.Id =
    this?.getString("conversationId")
        ?.takeIf { it.isNotBlank() }
        ?.let(Conversation::Id)
        ?: error("conversationId is required in tool context")

private const val MAX_EVENTS_PER_RESULT = 64
