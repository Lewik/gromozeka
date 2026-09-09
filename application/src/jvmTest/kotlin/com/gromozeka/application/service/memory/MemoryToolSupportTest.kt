package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryToolSupportTest {
    @Test
    fun `memory pipeline cannot recover tools denied to the agent`() {
        val tools = listOf(callback("public_search"), callback("private_read"))
        val search = tools.first()
        val policy = com.gromozeka.domain.tool.ToolAccessPolicy.AllowOnly(setOf(
            com.gromozeka.domain.tool.ToolSelector.ByName(com.gromozeka.domain.tool.QualifiedToolName(search.definition.source, search.definition.name)),
        ))
        assertEquals(listOf("public_search"), tools.forMemoryPipeline(policy).map { it.definition.name })
        assertEquals(emptyList(), tools.forMemoryPipeline(com.gromozeka.domain.tool.ToolAccessPolicy.AllowOnly()))
    }

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
                executionScope = AiToolExecutionScope.SERVER,
                visibleToMemoryPipeline = visibleToMemoryPipeline,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String = "{}"
        }
}
