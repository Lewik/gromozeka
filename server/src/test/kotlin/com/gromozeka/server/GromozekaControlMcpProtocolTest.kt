package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolAccessPolicy

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.tool.AiToolResult
import java.util.Base64
import com.gromozeka.domain.model.User
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GromozekaControlMcpProtocolTest {
    @Test
    fun `factory exposes provider tools with structured results`() = runBlocking<Unit> {
        val tool = testServerToolFactory().create(CONTROL_TOOL_SOURCE,
            name = "grz_test_read",
            description = "Test tool.",
            readOnly = true,
        ) {
            buildJsonObject { put("value", "ok") }
        }
        val server = testUnifiedMcpFactory(
            testNativeToolProvider(listOf(provider(tool)))
        ).create(testControlMcpCaller())

        val result = server.tools.getValue("grz_test_read").callForTest(
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "grz_test_read",
                    arguments = buildJsonObject {},
                )
            )
        )

        assertFalse(result.isError == true)
        val output = result.textOutput()
        assertEquals("true", output["success"]?.jsonPrimitive?.content)
        assertEquals("ok", output["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
        assertEquals(output, result.structuredContent)
    }

    @Test
    fun `optional arguments reject wrong types instead of using defaults`() = runBlocking<Unit> {
        val tool = testServerToolFactory().create(CONTROL_TOOL_SOURCE,
            name = "grz_test_boolean",
            description = "Test tool.",
            readOnly = true,
        ) { input ->
            buildJsonObject { put("enabled", input.optionalBoolean("enabled", false)) }
        }

        val result = tool.invoke(
            testControlMcpContext(),
            buildJsonObject { put("enabled", "true") },
        )

        assertTrue(result.isError == true)
        val error = result.textOutput()["error"]!!.jsonObject
        assertEquals("invalid_argument", error["code"]?.jsonPrimitive?.content)
        assertContains(error["message"]?.jsonPrimitive?.content.orEmpty(), "must be a boolean")
    }

    @Test
    fun `unexpected failures are opaque to MCP clients`() = runBlocking<Unit> {
        val tool = testServerToolFactory().create(CONTROL_TOOL_SOURCE,
            name = "grz_test_failure",
            description = "Test tool.",
            readOnly = true,
        ) {
            throw RuntimeException("database password is secret")
        }

        val result = tool.invoke(testControlMcpContext(), buildJsonObject {})

        assertTrue(result.isError == true)
        val text = (result.content.single() as TextContent).text.orEmpty()
        assertContains(text, "internal_error")
        assertFalse(text.contains("database password"))
    }

    @Test
    fun `cancellation is never converted into an MCP result`() {
        val tool = testServerToolFactory().create(CONTROL_TOOL_SOURCE,
            name = "grz_test_cancel",
            description = "Test tool.",
            readOnly = true,
        ) {
            throw CancellationException("cancel")
        }

        assertFailsWith<CancellationException> {
            runBlocking { tool.invoke(testControlMcpContext(), buildJsonObject {}) }
        }
    }

    @Test
    fun `server owner policy rejects members before tool execution`() = runBlocking<Unit> {
        var executed = false
        val tool = testServerToolFactory().create(CONTROL_TOOL_SOURCE,
            name = "grz_test_owner_only",
            description = "Owner-only test tool.",
            readOnly = true,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        ) {
            executed = true
            buildJsonObject {}
        }

        val result = tool.invoke(
            testControlMcpContext(User.Role.MEMBER),
            buildJsonObject {},
        )

        assertTrue(result.isError == true)
        assertFalse(executed)
        assertEquals(
            "forbidden",
            result.textOutput()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `inline secret values are redacted recursively`() {
        val original = buildJsonObject {
            put(
                "apiKey",
                buildJsonObject {
                    put("secretType", "inline")
                    put("value", "sk-secret")
                }
            )
            put(
                "environmentSecret",
                buildJsonObject {
                    put("secretType", "environment_variable")
                    put("name", "OPENAI_API_KEY")
                }
            )
        }

        val redacted = original.redactInlineSecrets()
        val value = redacted.value.jsonObject

        assertFalse(value.toString().contains("sk-secret"))
        assertEquals(JsonNull, value["apiKey"])
        assertEquals(listOf("/apiKey"), redacted.configuredInlineSecretPaths)
        assertContains(serverToolJson.encodeToString(JsonObject.serializer(), value), "\"apiKey\":null")
        assertEquals(
            "OPENAI_API_KEY",
            value["environmentSecret"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `duplicate tool names fail during factory construction`() {
        val first = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "grz_duplicate", "First.", readOnly = true) {
            buildJsonObject {}
        }
        val second = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "grz_duplicate", "Second.", readOnly = true) {
            buildJsonObject {}
        }

        assertFailsWith<IllegalStateException> {
            testNativeToolProvider(listOf(provider(first), provider(second))).getTools()
        }
    }

    @Test
    fun `MCP export preserves mixed content structured data and explicit failure`() {
        val bytes = byteArrayOf(1, 2, 3)
        val structured = buildJsonObject { put("code", "rejected") }
        val outcome = AiToolCallResult(
            content = listOf(AiToolResult.Text("details"), AiToolResult.Binary(bytes, "result file.bin", "application/octet-stream")),
            structuredContent = structured,
            isError = true,
        ).toMcpResult()
        assertTrue(outcome.isError == true)
        assertEquals(structured, outcome.structuredContent)
        assertEquals("details", (outcome.content[0] as TextContent).text)
        val binary = (outcome.content[1] as EmbeddedResource).resource as BlobResourceContents
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(binary.blob)))
        assertEquals("application/octet-stream", binary.mimeType)
        val structuredOnly = AiToolCallResult(structuredContent = structured).toMcpResult()
        assertEquals(structured.toString(), (structuredOnly.content.single() as TextContent).text)
        assertFalse(structuredOnly.isError == true)
    }

    @Test
    fun `HTTP serialization preserves complete root schemas rather than SDK simplified view`() {
        val input = """{"type":"object","properties":{"text":{"type":"string"}},"additionalProperties":false,"minProperties":1,"oneOf":[{"required":["text"]}],"${'$'}defs":{"value":{"type":"string"}}}"""
        val output = """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}"""
        val definition = com.gromozeka.domain.tool.AiToolDefinition("complete_schema", "Schema", input, outputSchema = output)
        val mapper = SafeMcpJsonMapper()
        val response = io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.result(9L,
            io.modelcontextprotocol.spec.McpSchema.ListToolsResult.builder(listOf(definition.toMcpTool(mapper))).build())
        val body = serverToolJson.parseToJsonElement(mapper.writeValueAsString(response)).jsonObject
        val tool = body.getValue("result").jsonObject.getValue("tools").let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject
        assertEquals(serverToolJson.parseToJsonElement(input), tool["inputSchema"])
        assertEquals(serverToolJson.parseToJsonElement(output), tool["outputSchema"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(9), body["id"])
    }

    @Test
    fun `Servlet SDK throwable errors never serialize private text or exception internals`() {
        val mapper = SafeMcpJsonMapper()
        val sdkError = io.modelcontextprotocol.spec.McpError.builder(-32603)
            .message("PRIVATE_REQUEST_SENTINEL")
            .data(mapOf("request" to "PRIVATE_DATA_SENTINEL"))
            .build().apply {
                initCause(IllegalStateException("PRIVATE_CAUSE_SENTINEL"))
                addSuppressed(RuntimeException("PRIVATE_SUPPRESSED_SENTINEL"))
            }
        for (failure in listOf(sdkError, IllegalStateException("PRIVATE_OTHER_SENTINEL"))) {
            for (wire in listOf(mapper.writeValueAsString(failure), mapper.writeValueAsBytes(failure).decodeToString())) {
                assertFalse(wire.contains("PRIVATE_"))
                assertFalse(wire.contains("stackTrace"))
                assertFalse(wire.contains("suppressed"))
                assertFalse(wire.contains("cause"))
                val envelope = serverToolJson.parseToJsonElement(wire).jsonObject
                assertEquals(setOf("jsonrpc", "id", "error"), envelope.keys)
                assertEquals("2.0", envelope.getValue("jsonrpc").jsonPrimitive.content)
                assertEquals(JsonNull, envelope["id"])
                assertEquals(buildJsonObject {
                    put("code", -32603)
                    put("message", "MCP request failed")
                }, envelope["error"])
            }
        }
    }

    @Test
    fun `SDK JSON RPC error retains request id and code but removes raw message and data`() {
        val mapper = SafeMcpJsonMapper()
        val response = io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.error(17L,
            io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError(
                -32602, "PRIVATE_MESSAGE_SENTINEL", mapOf("raw" to "PRIVATE_DATA_SENTINEL")))
        val wire = mapper.writeValueAsString(response)
        assertFalse(wire.contains("PRIVATE_"))
        val envelope = serverToolJson.parseToJsonElement(wire).jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(17), envelope["id"])
        val error = envelope.getValue("error").jsonObject
        assertEquals("-32602", error.getValue("code").jsonPrimitive.content)
        assertEquals("MCP request failed", error.getValue("message").jsonPrimitive.content)
        assertTrue(error["data"] == null || error["data"] == JsonNull)
    }

    private fun provider(vararg tools: AiToolCallback): AiToolCallbackContributor =
        object : AiToolCallbackContributor {
            override val callbacks = tools.toList()
        }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.textOutput() =
        serverToolJson.parseToJsonElement((content.single() as TextContent).text.orEmpty()).jsonObject
}
