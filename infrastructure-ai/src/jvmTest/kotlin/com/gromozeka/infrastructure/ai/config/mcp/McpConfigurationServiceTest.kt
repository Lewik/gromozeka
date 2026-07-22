package com.gromozeka.infrastructure.ai.config.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals

class McpConfigurationServiceTest {
    private val service = McpConfigurationService(
        gromozemkaHome = "/tmp/gromozeka-mcp-test",
        workerId = "test-worker",
        coroutineScope = CoroutineScope(SupervisorJob()),
    )

    @Test
    fun deserializesAllowedToolsFromConfig() {
        val config = ObjectMapper().readValue(
            """
            {
              "mcpServers": {
                "claude-code": {
                  "command": "claude",
                  "args": ["mcp", "serve"],
                  "allowedTools": ["WebSearch", "WebFetch"],
                  "forwardGrzConversationContext": true,
                  "workerIds": ["test-worker"]
                }
              }
            }
            """.trimIndent(),
            McpConfig::class.java,
        )

        assertEquals(listOf("WebSearch", "WebFetch"), config.mcpServers["claude-code"]?.allowedTools)
        assertEquals(true, config.mcpServers["claude-code"]?.forwardGrzConversationContext)
        assertEquals(setOf("test-worker"), config.mcpServers["claude-code"]?.workerIds)
    }

    @Test
    fun allowedToolsLimitsVisibleMcpTools() {
        val result = service.filterToolsForServer(
            serverName = "claude-code",
            allTools = tools("WebSearch", "WebFetch", "Bash", "Read"),
            serverConfig = ServerConfig(allowedTools = listOf("WebSearch", "WebFetch")),
        )

        assertEquals(listOf("WebSearch", "WebFetch"), result.names())
    }

    @Test
    fun emptyAllowedToolsDisablesAllServerTools() {
        val result = service.filterToolsForServer(
            serverName = "claude-code",
            allTools = tools("WebSearch", "WebFetch"),
            serverConfig = ServerConfig(allowedTools = emptyList()),
        )

        assertEquals(emptyList(), result.names())
    }

    @Test
    fun excludedToolsAreAppliedAfterAllowedTools() {
        val result = service.filterToolsForServer(
            serverName = "claude-code",
            allTools = tools("WebSearch", "WebFetch", "Bash"),
            serverConfig = ServerConfig(
                allowedTools = listOf("WebSearch", "WebFetch", "Bash"),
                excludedTools = listOf("Bash"),
            ),
        )

        assertEquals(listOf("WebSearch", "WebFetch"), result.names())
    }

    @Test
    fun absentAllowedToolsKeepsExistingExcludedToolsBehavior() {
        val result = service.filterToolsForServer(
            serverName = "example",
            allTools = tools("Search", "Read", "Write"),
            serverConfig = ServerConfig(excludedTools = listOf("Write")),
        )

        assertEquals(listOf("Search", "Read"), result.names())
    }

    private fun tools(vararg names: String): List<Tool> =
        names.map { name ->
            Tool(
                name = name,
                description = null,
                inputSchema = ToolSchema(),
                outputSchema = null,
                annotations = null,
            )
        }

    private fun List<Tool>.names(): List<String> =
        map { it.name }
}
