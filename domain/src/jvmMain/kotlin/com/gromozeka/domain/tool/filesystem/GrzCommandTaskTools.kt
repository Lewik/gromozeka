package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.LocalAgentToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext

data class GetCommandTaskRequest(
    val task_id: String,
    val after_byte: Long = 0,
    val wait_ms: Long = 10_000,
)

interface GrzGetCommandTaskTool : Tool<GetCommandTaskRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_get_command_task"

    override val metadata
        get() = LocalAgentToolMetadata

    override val description: String
        get() = """
            Wait for a command task and return only bounded output starting at after_byte.
            Reuse next_output_byte on the next call. Continue while status is WORKING or has_more_output is true.
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
        get() = LocalAgentToolMetadata

    override val description: String
        get() = "Cancel a running command task and terminate its process tree."

    override val requestType: Class<CancelCommandTaskRequest>
        get() = CancelCommandTaskRequest::class.java

    override fun execute(request: CancelCommandTaskRequest, context: ToolExecutionContext?): Map<String, Any>
}
