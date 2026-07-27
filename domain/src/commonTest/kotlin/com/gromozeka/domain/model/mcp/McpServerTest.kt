package com.gromozeka.domain.model.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpServerTest {
    @Test
    fun `keeps provider-compatible tool names readable`() {
        assertEquals(
            "mcp__test_server__read_file",
            tool("read_file").toAiToolDefinition(McpServerId("test_server")).name,
        )
    }

    @Test
    fun `normalizes incompatible and oversized tool names without collisions`() {
        val serverId = McpServerId("an_extremely_long_external_mcp_server_identifier")
        val first = tool("documents.search/${"nested_".repeat(12)}first")
            .toAiToolDefinition(serverId)
            .name
        val second = tool("documents/search/${"nested_".repeat(12)}first")
            .toAiToolDefinition(serverId)
            .name

        assertNotEquals(first, second)
        listOf(first, second).forEach { name ->
            assertTrue(name.length <= 64)
            assertTrue(name.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }

    private fun tool(name: String): McpToolSnapshot =
        McpToolSnapshot(
            remoteName = name,
            description = "",
            inputSchema = """{"type":"object"}""",
        )
}
