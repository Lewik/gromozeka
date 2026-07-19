package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.CommandTaskOwnerToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MAX_COMMAND_TASK_WAIT_MILLIS = 300_000L

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

interface GrzGetCommandTaskTool : Tool<GetCommandTaskRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_get_command_task"

    override val metadata
        get() = CommandTaskOwnerToolMetadata

    override val description: String
        get() = """
            Wait for a command task and return only bounded output starting at after_byte.
            Reuse next_output_byte on the next call. Continue while status is WORKING or has_more_output is true.
            wait_ms may be from 0 to $MAX_COMMAND_TASK_WAIT_MILLIS; prefer one wait covering the expected remaining duration, capped at this limit.
            The terminal statuses are COMPLETED, FAILED, and CANCELLED.
        """.trimIndent()

    override val requestType: Class<GetCommandTaskRequest>
        get() = GetCommandTaskRequest::class.java

    override fun execute(request: GetCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any>
}

data class CancelCommandTaskRequest(
    val task_id: String,
)

interface GrzCancelCommandTaskTool : Tool<CancelCommandTaskRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_cancel_command_task"

    override val metadata
        get() = CommandTaskOwnerToolMetadata

    override val description: String
        get() = "Cancel a running command task and terminate its process tree."

    override val requestType: Class<CancelCommandTaskRequest>
        get() = CancelCommandTaskRequest::class.java

    override fun execute(request: CancelCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any>
}
