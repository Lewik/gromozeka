package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.web.ClaudeCodeWebSearchRequest
import com.gromozeka.domain.tool.web.ClaudeCodeWebSearchTool as WebSearchContract
import com.gromozeka.infrastructure.ai.config.TypedToolCallbackAdapter
import kotlinx.serialization.json.*
import kotlin.test.*

class ClaudeCodeWebSearchToolTest {
    @Test
    fun `schema exposes optional native mode values and documents the default`() {
        val schema = Json.parseToJsonElement(callback().definition.inputSchema).jsonObject
        val mode = schema.getValue("properties").jsonObject.getValue("mode").jsonObject
        assertEquals("string", mode.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("standard", "extended"), mode.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(mode.getValue("description").jsonPrimitive.content.contains("standard (default)"))
        assertFalse(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "mode" })
    }

    @Test
    fun `omitted mode is sent to the native tool explicitly as standard`() {
        assertEquals(
            json("""{"query":"Kotlin coroutines","mode":"standard"}"""),
            json(callback().call("""{"query":"Kotlin coroutines"}""", null)),
        )
    }

    @Test
    fun `both modes preserve query and domain filters verbatim`() {
        for (mode in listOf("standard", "extended")) {
            for (filter in listOf("allowed_domains", "blocked_domains")) {
                val input = """{"query":"  Kotlin coroutines  ","mode":"$mode","$filter":["kotlinlang.org","example.com"]}"""
                assertEquals(json(input), json(callback().call(input, null)))
            }
        }
    }

    @Test
    fun `invalid modes and invalid domain combinations are rejected before execution`() {
        var executions = 0
        val callback = TypedToolCallbackAdapter().adapt(object : WebSearchContract {
            override fun execute(request: ClaudeCodeWebSearchRequest, context: ToolExecutionContext?): String {
                executions++
                return request.toNativeWebSearchInput().toString()
            }
        })
        for (input in listOf(
            """{"query":"Kotlin","mode":"auto"}""",
            """{"query":"Kotlin","mode":"STANDARD"}""",
            """{"query":"Kotlin","mode":null}""",
            """{"query":"Kotlin","allowed_domains":["example.com"],"blocked_domains":["other.example"]}""",
            """{"query":"Kotlin","allowed_domains":[""]}""",
            """{"query":"   "}""",
        )) assertFails(input) { callback.call(input, null) }
        assertEquals(0, executions)
    }

    private fun callback() = TypedToolCallbackAdapter().adapt(object : WebSearchContract {
        override fun execute(request: ClaudeCodeWebSearchRequest, context: ToolExecutionContext?): String =
            request.toNativeWebSearchInput().toString()
    })

    private fun json(value: String) = Json.parseToJsonElement(value)
}
