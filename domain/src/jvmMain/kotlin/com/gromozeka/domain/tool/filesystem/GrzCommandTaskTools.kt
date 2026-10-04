package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.CommandTaskOwnerToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MAX_COMMAND_TASK_WAIT_MILLIS = 300_000L
const val GRZ_GET_COMMAND_TASK_TOOL_NAME = "grz_get_command_task"
const val GRZ_CANCEL_COMMAND_TASK_TOOL_NAME = "grz_cancel_command_task"

data class GetCommandTaskRequest(
    val task_id: String,
    @property:ToolParameter(
        description = "Byte offset returned as next_output_byte by the previous call.",
        minimum = 0,
    )
    val after_byte: Long = 0,
    @property:ToolParameter(
        description = "Maximum time to wait for new output or terminal status. Prefer one wait covering the expected remaining command duration.",
        minimum = 0,
        maximum = MAX_COMMAND_TASK_WAIT_MILLIS,
    )
    val wait_ms: Long = 10_000,
)

interface GrzGetCommandTaskTool : Tool<GetCommandTaskRequest, List<AiToolResult>> {
    override val name: String
        get() = GRZ_GET_COMMAND_TASK_TOOL_NAME

    override val metadata
        get() = CommandTaskOwnerToolMetadata

    override val description: String
        get() = """
            Wait for a command task and return only bounded output starting at after_byte.
            Output is returned as raw bytes with a safe text preview and an artifact_id for grz_save_tool_output in conversations.
            Reuse next_output_byte on the next call. Continue while status is WORKING or has_more_output is true.
            wait_ms may be from 0 to $MAX_COMMAND_TASK_WAIT_MILLIS; prefer one wait covering the expected remaining duration, capped at this limit.
            The terminal statuses are COMPLETED, FAILED, and CANCELLED.
            Gromozeka conversations receive terminal command results automatically, so call this only for intermediate output or an explicit status check.
        """.trimIndent()

    override val requestType: Class<GetCommandTaskRequest>
        get() = GetCommandTaskRequest::class.java

    override fun execute(request: GetCommandTaskRequest, context: ToolExecutionContext?): List<AiToolResult>
}

data class CancelCommandTaskRequest(
    val task_id: String,
)

interface GrzCancelCommandTaskTool : Tool<CancelCommandTaskRequest, Map<String, Any>> {
    override val name: String
        get() = GRZ_CANCEL_COMMAND_TASK_TOOL_NAME

    override val metadata
        get() = CommandTaskOwnerToolMetadata

    override val description: String
        get() = "Cancel a running command task and terminate its process tree."

    override val requestType: Class<CancelCommandTaskRequest>
        get() = CancelCommandTaskRequest::class.java

    override fun execute(request: CancelCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any>
}

const val GRZ_SEND_COMMAND_INPUT_TOOL_NAME = "grz_send_command_input"

data class SendCommandInputRequest(
    val task_id: String,
    @property:ToolParameter(
        description = "UTF-8 text to write exactly as supplied, at most 65536 encoded bytes. No newline is added; include a newline explicitly for line-based programs.",
    )
    val text: String = "",
    @property:ToolParameter(
        description = "Close stdin (send EOF) after writing text. Stdin cannot be reopened. May be true with empty text.",
    )
    val close_input: Boolean = false,
)

interface GrzSendCommandInputTool : Tool<SendCommandInputRequest, Map<String, Any>> {
    override val name: String get() = GRZ_SEND_COMMAND_INPUT_TOOL_NAME
    override val metadata get() = CommandTaskOwnerToolMetadata.copy(logInput = false)
    override val requestType: Class<SendCommandInputRequest> get() = SendCommandInputRequest::class.java
    override val description: String get() = """
        Send UTF-8 text to a running command's stdin, optionally closing it with EOF.
        The task ID selects its owning Worker and workspace mount; do not supply execution_target.
        Input is not shell code and is not executed by Gromozeka; the running program interprets it.
        Writes are serialized per task and flushed. Include newlines explicitly when required.
        Success means the bytes were written to the pipe, not that the program processed them.
        A write may block if the program does not read input; cancelling the source command can unblock it.
        Input is unavailable after a Worker restart loses the original pipe, even if the process survives.
        Never retry automatically: a failure, cancellation, or uncertain result may follow partial delivery.
        This is a potentially mutating operation, not a read-only inspection. Use grz_get_command_task for output.
    """.trimIndent()
    override fun execute(request: SendCommandInputRequest, context: ToolExecutionContext?): Map<String, Any>
}
