package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component

@Component
class MemoryListNamespacesToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_LIST_NAMESPACES_TOOL_NAME,
        description = "Inspect the configured memory namespace, item counts, and any unexpected stored namespaces. Memory operations cannot select a namespace in the current runtime.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {}
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        memoryToolApplicationService.listNamespaces()
    }
}
