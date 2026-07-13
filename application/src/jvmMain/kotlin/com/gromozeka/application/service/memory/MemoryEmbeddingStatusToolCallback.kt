package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component

@Component
class MemoryEmbeddingStatusToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_EMBEDDING_STATUS_TOOL_NAME,
        description = "Report vector embedding coverage for the configured memory namespace under the currently configured embedding model. Counts embeddable memory items, expected embedding rows, existing rows, and missing rows. This does not modify memory.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {}
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        memoryToolApplicationService.memoryEmbeddingStatus(
            conversationIdValue = context?.getString("conversationId"),
        )
    }
}
