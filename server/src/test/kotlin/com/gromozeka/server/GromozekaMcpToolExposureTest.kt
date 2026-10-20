package com.gromozeka.server

import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GromozekaMcpToolExposureTest {
    @Test fun `unified catalog includes native Control and ordinary Server callbacks`() = runBlocking<Unit> {
        val control = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "test_control", "Control", readOnly = true) { ServerToolSchemas.empty }
        val ordinary = mcpTestTool("test_native")
        val factory = testUnifiedMcpFactory(mcpTestProvider(control, ordinary))
        assertEquals(setOf("test_control", "test_native"), factory.create(testControlMcpCaller()).tools.keys)
    }

    @Test fun `memory help and all legacy memory names are excluded without changing native callbacks`() = runBlocking<Unit> {
        val names = listOf("memory_help", "memory_remember", "memory_enrich_context", "memory_answer_question",
            "memory_forget_source", "memory_list_namespaces", "memory_maintenance", "memory_queue_status",
            "memory_run_status", "memory_embedding_status", "memory_rebuild_embeddings")
        val callbacks = names.map { mcpTestTool(it) }
        val server = testUnifiedMcpFactory(mcpTestProvider(*callbacks.toTypedArray())).create(testControlMcpCaller())
        assertTrue(server.tools.isEmpty())
        assertTrue(callbacks.all { it.available })
        assertEquals(names, callbacks.map { it.definition.name })
    }

    @Test fun `renaming a namespace dependent callback cannot reopen memory publication`() {
        val callback = mcpTestTool("renamed_memory_operation", requirements = AiToolContextRequirements(setOf(AiToolContextField.MEMORY_NAMESPACE)))
        assertFalse(isMcpExposed(AiToolDescriptor(callback.definition, callback.metadata)))
    }

    @Test fun `retired wildcard allowlist and opt in settings cannot reopen memory`() = runBlocking<Unit> {
        val keys = listOf("gromozeka.mcp.exposed.tools", "gromozeka.mcp.memory-tools-enabled")
        val old = keys.associateWith(System::getProperty)
        try {
            System.setProperty(keys[1], "true")
            for (setting in listOf("all", "*", "memory_help,memory_remember")) {
                System.setProperty(keys[0], setting)
                val factory = testUnifiedMcpFactory(mcpTestProvider(mcpTestTool("memory_help"), mcpTestTool("memory_remember"), mcpTestTool("ordinary")))
                assertEquals(setOf("ordinary"), factory.create(testControlMcpCaller()).tools.keys)
            }
        } finally { old.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) } }
    }
}
