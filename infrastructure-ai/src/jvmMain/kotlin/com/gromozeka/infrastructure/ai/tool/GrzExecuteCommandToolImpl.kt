package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.tool.ToolExecutionContext
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
    override fun execute(request: ExecuteCommandRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            commandTaskService.start(request, context ?: error("Tool execution context is required")).toResult()
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzGetCommandTaskToolImpl(
    private val commandTaskService: CommandTaskService,
) : GrzGetCommandTaskTool {
    override fun execute(request: GetCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            val conversationId = context.requiredConversationId()
            commandTaskService.get(
                conversationId = conversationId,
                taskId = CommandTask.Id(request.task_id),
                afterByte = request.after_byte,
                waitMillis = request.wait_ms,
            )?.toResult() ?: mapOf(
                "success" to false,
                "error" to "Command task not found: ${request.task_id}",
            )
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

private fun CommandTaskOutput.toResult(): Map<String, Any> = buildMap {
    put("success", task.status == CommandTask.Status.WORKING || task.status == CommandTask.Status.COMPLETED)
    put("task_id", task.id.value)
    put("status", task.status.name)
    put("command", task.command)
    task.processId?.let { put("process_id", it) }
    task.exitCode?.let { put("exit_code", it) }
    task.statusMessage?.let { put("status_message", it) }
    put("output", output)
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
