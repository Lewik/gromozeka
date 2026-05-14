package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Component

@Component
class MemoryQueueStatusToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_QUEUE_STATUS_TOOL_NAME,
        description = "Read process-local memory document ingest queue status: " +
            "pending jobs, active run, and lifetime queue counters.",
        inputSchema = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String =
        memoryToolApplicationService.memoryQueueStatus()
}
