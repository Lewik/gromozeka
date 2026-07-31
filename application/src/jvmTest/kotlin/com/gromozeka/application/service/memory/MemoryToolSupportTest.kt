package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryToolSupportTest {
    @Test
    fun `memory pipeline excludes memory management and explicitly hidden tools`() {
        val tools = listOf(
            callback("regular_tool"),
            callback(MEMORY_REMEMBER_TOOL_NAME),
            callback("control_tool", visibleToMemoryPipeline = false),
        )

        assertEquals(
            listOf("regular_tool"),
            tools.forMemoryPipeline().map { it.definition.name },
        )
    }

    private fun callback(
        name: String,
        visibleToMemoryPipeline: Boolean = true,
    ): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = name,
                description = name,
                inputSchema = """{"type":"object"}""",
            )
            override val metadata = AiToolMetadata(
                visibleToMemoryPipeline = visibleToMemoryPipeline,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String = "{}"
        }
}
