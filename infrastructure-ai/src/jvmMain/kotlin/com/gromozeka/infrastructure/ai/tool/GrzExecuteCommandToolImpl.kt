package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.gromozeka.domain.tool.filesystem.CancelCommandTaskRequest
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.tool.filesystem.GetCommandTaskRequest
import com.gromozeka.domain.tool.filesystem.GrzCancelCommandTaskTool
import com.gromozeka.domain.tool.filesystem.GrzExecuteCommandTool
import com.gromozeka.domain.tool.filesystem.GrzGetCommandTaskTool
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzExecuteCommandToolImpl(
    private val commandTaskService: CommandTaskService,
) : GrzExecuteCommandTool {
    override fun execute(request: ExecuteCommandRequest, context: ToolExecutionContext?): List<AiToolResult> =
        runBlocking {
            commandTaskService.start(request, context ?: error("Tool execution context is required")).toResult()
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzGetCommandTaskToolImpl(
    private val commandTaskService: CommandTaskService,
) : GrzGetCommandTaskTool {
    override fun execute(request: GetCommandTaskRequest, context: ToolExecutionContext?): List<AiToolResult> =
        runBlocking {
            val conversationId = context.requiredConversationId()
            commandTaskService.get(
                conversationId = conversationId,
                taskId = CommandTask.Id(request.task_id),
                afterByte = request.after_byte,
                waitMillis = request.wait_ms,
            )?.toResult() ?: listOf(AiToolResult.Text("Command task not found: ${request.task_id}"))
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCancelCommandTaskToolImpl(
    private val commandTaskService: CommandTaskService,
) : GrzCancelCommandTaskTool {
    override fun execute(request: CancelCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            val conversationId = context.requiredConversationId()
            val taskId = CommandTask.Id(request.task_id)
            val cancelled = commandTaskService.cancel(conversationId, taskId)
            mapOf(
                "success" to cancelled,
                "task_id" to taskId.value,
                "status" to if (cancelled) CommandTask.Status.CANCELLED.name else "UNCHANGED",
            )
        }
}

private fun CommandTaskOutput.metadata(): Map<String, Any> = buildMap {
    put("success", task.status == CommandTask.Status.WORKING || task.status == CommandTask.Status.COMPLETED)
    put("task_id", task.id.value)
    put("status", task.status.name)
    task.synchronizationError?.let { put("synchronization_error", it) }
    put("command", task.command)
    put("survive_worker_restart", task.processLifetime == CommandTask.ProcessLifetime.RESUMABLE)
    task.processId?.let { put("process_id", it) }
    task.exitCode?.let { put("exit_code", it) }
    task.statusMessage?.let { put("status_message", it) }
    put("output_text_available", content.utf8TextOrNull() != null)
    put("output_start_byte", outputStartByte)
    put("next_output_byte", nextOutputByte)
    put("output_bytes", task.outputBytes)
    put("has_more_output", hasMoreOutput)
    put("output_file", task.outputFile)
}

private fun ToolExecutionContext?.requiredConversationId(): Conversation.Id =
    this?.getString("conversationId")
        ?.takeIf { it.isNotBlank() }
        ?.let { Conversation.Id(it) }
        ?: error("conversationId is required in tool context")

private fun CommandTaskOutput.toResult(): List<AiToolResult> = listOf(
    AiToolResult.Text(JsonObject(metadata().mapValues { (_, value) ->
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }).toString()),
    AiToolResult.Binary(
        content = content.bytes(),
        fileName = "command-${task.id.value}-${outputStartByte}-${nextOutputByte}.bin",
        mediaType = "application/octet-stream",
    ),
)
