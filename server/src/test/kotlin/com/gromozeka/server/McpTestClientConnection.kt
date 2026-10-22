package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.tool.AiToolDefinition
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Independent Kotlin SDK decoding of the actual Java SDK Servlet wire output. */
internal val NativeMcpHttpSession.tools: Map<String, ServletMcpTestTool>
    get() = runBlocking {
        val result = rpcForTest("tools/list")
        McpJson.decodeFromJsonElement(ListToolsResult.serializer(), result).tools.associate {
            it.name to ServletMcpTestTool(this@tools, it)
        }
    }

internal class ServletMcpTestTool(private val session: NativeMcpHttpSession, val tool: Tool) {
    suspend fun callForTest(request: CallToolRequest): CallToolResult =
        McpJson.decodeFromJsonElement(CallToolResult.serializer(), session.rpcForTest("tools/call", buildJsonObject {
            put("name", request.params.name)
            put("arguments", request.arguments ?: JsonObject(emptyMap()))
        }))
}

internal suspend fun NativeMcpHttpSession.rpcForTest(
    method: String, params: JsonObject = JsonObject(emptyMap()),
): JsonObject {
    val response = runInterruptible(Dispatchers.IO) {
        val request = mockMcpRequest(method, params)
        MockHttpServletResponse().also { transport.service(request as ServletRequest, it as ServletResponse) }
    }
    // Inspect a response only after the coroutine cancellation boundary. The Servlet
    // may translate an interrupted blocking subscription into an HTTP error internally.
    assertEquals(200, response.status, response.contentAsString)
    val envelope = serverToolJson.parseToJsonElement(response.contentAsString).jsonObject
    assertNull(envelope["error"], response.contentAsString)
    return envelope.getValue("result").jsonObject
}

internal fun mockMcpRequest(method: String, params: JsonObject = JsonObject(emptyMap())) =
    MockHttpServletRequest("POST", "/mcp").apply {
        contentType = "application/json"
        addHeader("Accept", "application/json, text/event-stream")
        addHeader("Host", "localhost")
        addHeader("MCP-Protocol-Version", "2025-06-18")
        setContent(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
        }.toString().encodeToByteArray())
    }

internal fun AiToolCallResult.toMcpResult(): CallToolResult {
    val mapper = SafeMcpJsonMapper()
    return McpJson.decodeFromString(CallToolResult.serializer(), mapper.writeValueAsString(toMcpResult(mapper)))
}

internal fun AiToolDefinition.toMcpTool(): Tool {
    val mapper = SafeMcpJsonMapper()
    return McpJson.decodeFromString(Tool.serializer(), mapper.writeValueAsString(toMcpTool(mapper)))
}
