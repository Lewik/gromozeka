package com.gromozeka.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
    fun `factory exposes provider tools with structured results`() = runBlocking {
        val tool = controlMcpTool(
            name = "grz_test_read",
            description = "Test tool.",
            readOnly = true,
        ) {
            buildJsonObject { put("value", "ok") }
        }
        val server = GromozekaControlMcpServerFactory(
            listOf(provider(tool))
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
    fun `optional arguments reject wrong types instead of using defaults`() = runBlocking {
        val tool = controlMcpTool(
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
    fun `unexpected failures are opaque to MCP clients`() = runBlocking {
        val tool = controlMcpTool(
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
        val tool = controlMcpTool(
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
    fun `server owner policy rejects members before tool execution`() = runBlocking {
        var executed = false
        val tool = controlMcpTool(
            name = "grz_test_owner_only",
            description = "Owner-only test tool.",
            readOnly = true,
            accessPolicy = ControlMcpAccessPolicy.SERVER_OWNER,
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
        assertContains(controlMcpJson.encodeToString(JsonObject.serializer(), value), "\"apiKey\":null")
        assertEquals(
            "OPENAI_API_KEY",
            value["environmentSecret"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `duplicate tool names fail during factory construction`() {
        val first = controlMcpTool("grz_duplicate", "First.", readOnly = true) {
            buildJsonObject {}
        }
        val second = controlMcpTool("grz_duplicate", "Second.", readOnly = true) {
            buildJsonObject {}
        }

        assertFailsWith<IllegalArgumentException> {
            GromozekaControlMcpServerFactory(listOf(provider(first), provider(second)))
        }
    }

    private fun provider(vararg tools: ControlMcpTool): ControlMcpToolProvider =
        object : ControlMcpToolProvider {
            override val tools = tools.toList()
        }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.textOutput() =
        controlMcpJson.parseToJsonElement((content.single() as TextContent).text.orEmpty()).jsonObject
}
