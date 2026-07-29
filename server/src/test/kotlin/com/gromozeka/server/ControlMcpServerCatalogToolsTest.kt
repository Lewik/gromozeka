package com.gromozeka.server

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ControlMcpServerCatalogToolsTest {
    @Test
    fun `stdio environment values are omitted from control MCP responses`() {
        val server = testServer(
            McpServerTransport.Stdio(
                command = "mcp-server",
                environment = mapOf(
                    "API_TOKEN" to "stdio-secret",
                    "REGION" to "eu-west-1",
                ),
            )
        )

        val redacted = server.toRedactedJson()
        val transport = redacted["config"]!!.jsonObject["transport"]!!.jsonObject
        val configured = redacted["configuredTransportValues"]!!.jsonObject

        assertFalse(redacted.toString().contains("stdio-secret"))
        assertFalse(redacted.toString().contains("eu-west-1"))
        assertFalse("environment" in transport)
        assertEquals(
            listOf("API_TOKEN", "REGION"),
            configured["environmentVariables"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `HTTP header values are omitted from control MCP responses`() {
        val server = testServer(
            McpServerTransport.StreamableHttp(
                url = "https://mcp.example.test",
                headers = mapOf(
                    "Authorization" to "Bearer header-secret",
                    "X-Tenant" to "tenant-secret",
                ),
            )
        )

        val redacted = server.toRedactedJson()
        val transport = redacted["config"]!!.jsonObject["transport"]!!.jsonObject
        val configured = redacted["configuredTransportValues"]!!.jsonObject

        assertFalse(redacted.toString().contains("header-secret"))
        assertFalse(redacted.toString().contains("tenant-secret"))
        assertFalse("headers" in transport)
        assertEquals(
            listOf("Authorization", "X-Tenant"),
            configured["httpHeaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

private fun testServer(transport: McpServerTransport): McpServer {
    val tools = listOf(
        McpToolSnapshot(
            remoteName = "test",
            description = "Test tool.",
            inputSchema = "{}",
        )
    )
    val snapshot = McpServerSnapshot(
        serverName = "test",
        serverVersion = "1",
        supportsToolsListChanged = false,
        tools = tools,
        fingerprint = McpServerSnapshot.calculateFingerprint(
            serverName = "test",
            serverVersion = "1",
            instructions = null,
            supportsToolsListChanged = false,
            tools = tools,
        ),
        capturedAt = Instant.parse("2026-07-30T00:00:00Z"),
    )
    return McpServer(
        config = McpServerConfig(
            id = McpServerId("test_server"),
            displayName = "Test Server",
            workerId = ConversationRuntimeWorkerId("worker"),
            transport = transport,
        ),
        snapshot = snapshot,
        revision = 1,
        refreshAvailable = false,
        createdAt = Instant.parse("2026-07-30T00:00:00Z"),
        updatedAt = Instant.parse("2026-07-30T00:00:00Z"),
    )
}
