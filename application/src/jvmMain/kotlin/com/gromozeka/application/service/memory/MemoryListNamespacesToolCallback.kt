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
        description = "List readable memory namespaces available in the typed memory store, including item counts and the configured default namespace. Use this before choosing a namespace for memory_remember, memory_enrich_context, or memory_maintenance.",
        inputSchema = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        memoryToolApplicationService.listNamespaces()
    }
}
