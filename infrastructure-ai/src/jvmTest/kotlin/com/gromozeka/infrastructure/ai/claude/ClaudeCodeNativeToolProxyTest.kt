package com.gromozeka.infrastructure.ai.claude

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeNativeToolProxyTest {
    @Test
    fun `native command exposes only the selected Claude Code tool`() {
        val executor = ProcessClaudeCodeCliExecutor("claude")
        val command = command(ClaudeCodeNativeTool.WEB_SEARCH)

        val args = executor.buildArgs(command, "/tmp/gromozeka-system.md")

        assertTrue(args.windowed(2).contains(listOf("--tools", "WebSearch")))
        assertTrue(args.windowed(2).contains(listOf("--allowedTools", "WebSearch")))
        assertTrue("--json-schema" !in args)
    }

    @Test
    fun `native execution always uses and closes a fresh process`() = runBlocking {
        val processes = mutableListOf<NativeToolProcess>()
        val executor = ProcessClaudeCodeCliExecutor(
            processFactory = ClaudeCodeCliProcessFactory {
                NativeToolProcess().also(processes::add)
            },
        )
        val invocation = invocation()

        try {
            executor.executeNativeTool(command(ClaudeCodeNativeTool.WEB_SEARCH), invocation)
            executor.executeNativeTool(command(ClaudeCodeNativeTool.WEB_SEARCH), invocation)

            assertEquals(2, processes.size)
            assertTrue(processes.all(NativeToolProcess::closed))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `stream parser returns the exact native result`() {
        val invocation = invocation()
        val parser = ClaudeCodeNativeToolStreamParser(invocation)
        val nativeResult = jsonObject(
            "query" to JsonPrimitive("Kotlin coroutines"),
            "results" to JsonArray(
                listOf(jsonObject("url" to JsonPrimitive("https://kotlinlang.org/docs/coroutines-overview.html")))
            ),
        )

        assertNull(parser.accept(toolUseEvent(invocation.input)))
        val response = parser.accept(toolResultEvent(nativeResult))

        assertEquals(invocation.tool, response?.tool)
        assertEquals(invocation.input, response?.input)
        assertEquals(nativeResult, response?.result)
    }

    @Test
    fun `stream parser rejects arguments changed by the model`() {
        val parser = ClaudeCodeNativeToolStreamParser(invocation())

        assertFailsWith<IllegalArgumentException> {
            parser.accept(
                toolUseEvent(
                    jsonObject("query" to JsonPrimitive("different query"))
                )
            )
        }
    }

    @Test
    fun `both modes accept only the exact requested query filters and mode`() {
        for (mode in listOf("standard", "extended")) {
            for (filter in listOf("allowed_domains", "blocked_domains")) {
                val input = JsonObject(invocation(mode).input + (filter to JsonArray(listOf(JsonPrimitive("kotlinlang.org")))))
                val request = ClaudeCodeNativeToolInvocation(ClaudeCodeNativeTool.WEB_SEARCH, input)
                val parser = ClaudeCodeNativeToolStreamParser(request)
                assertNull(parser.accept(toolUseEvent(input)))
                assertEquals(input, parser.accept(toolResultEvent(jsonObject("ok" to JsonPrimitive(true))))?.input)
                val changed = listOf(
                    JsonObject(input + ("query" to JsonPrimitive("changed query"))),
                    JsonObject(input - filter),
                    JsonObject(input + (filter to JsonArray(listOf(JsonPrimitive("other.example"))))),
                    JsonObject(input + ("mode" to JsonPrimitive(if (mode == "standard") "extended" else "standard"))),
                    JsonObject(input - "mode"),
                    JsonObject(input + ("mode" to JsonPrimitive("unknown"))),
                    JsonObject(input + ("unexpected" to JsonPrimitive(true))),
                )
                changed.forEach { actual ->
                    assertFailsWith<IllegalArgumentException> {
                        ClaudeCodeNativeToolStreamParser(request).accept(toolUseEvent(actual))
                    }
                }
            }
        }
    }

    @Test
    fun `legacy exact input remains valid but unrequested fields are not ignored`() {
        val input = jsonObject("query" to JsonPrimitive("Kotlin coroutines"))
        val request = ClaudeCodeNativeToolInvocation(ClaudeCodeNativeTool.WEB_SEARCH, input)
        val parser = ClaudeCodeNativeToolStreamParser(request)
        assertNull(parser.accept(toolUseEvent(input)))
        assertEquals(input, parser.accept(toolResultEvent(JsonPrimitive("result")))?.input)
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeNativeToolStreamParser(request).accept(toolUseEvent(invocation().input))
        }
    }

    @Test
    fun `mismatch diagnostics identify fields without leaking query or domain values`() {
        val request = invocation().copy(input = jsonObject(
            "query" to JsonPrimitive("private-search-needle"), "mode" to JsonPrimitive("standard"),
            "allowed_domains" to JsonArray(listOf(JsonPrimitive("private-domain.example"))),
        ))
        val error = assertFailsWith<IllegalArgumentException> {
            ClaudeCodeNativeToolStreamParser(request).accept(toolUseEvent(JsonObject(request.input +
                ("query" to JsonPrimitive("changed-private-needle")))))
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains("changed_fields=[\"query\"]"))
        assertTrue(message.contains("expected_sha256="))
        assertTrue(message.contains("actual_sha256="))
        for (privateValue in listOf("private-search-needle", "private-domain.example", "changed-private-needle")) {
            assertFalse(message.contains(privateValue))
        }
    }

    @Test
    fun `native tool identity uniqueness and result correlation stay strict`() {
        val request = invocation()
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeNativeToolStreamParser(request).accept(toolUseEvent(request.input, toolName = "WebFetch"))
        }
        val parser = ClaudeCodeNativeToolStreamParser(request)
        assertNull(parser.accept(toolResultEvent(JsonPrimitive("before call"))))
        assertNull(parser.accept(toolUseEvent(request.input)))
        assertNull(parser.accept(toolResultEvent(JsonPrimitive("wrong call"), toolUseId = "other-id")))
        assertEquals(JsonPrimitive("correct"), parser.accept(toolResultEvent(JsonPrimitive("correct")))?.result)
        assertFailsWith<IllegalStateException> { parser.accept(toolUseEvent(request.input, toolUseId = "second-call")) }
        assertFailsWith<IllegalStateException> {
            ClaudeCodeNativeToolStreamParser(request).accept(jsonObject("type" to JsonPrimitive("result")))
        }
    }

    @Test
    fun `WebFetch input is not normalized as WebSearch`() {
        val input = jsonObject("url" to JsonPrimitive("https://example.com"), "prompt" to JsonPrimitive("Summarize"))
        val request = ClaudeCodeNativeToolInvocation(ClaudeCodeNativeTool.WEB_FETCH, input)
        val parser = ClaudeCodeNativeToolStreamParser(request)
        assertNull(parser.accept(toolUseEvent(input, toolName = "WebFetch")))
        assertEquals(input, parser.accept(toolResultEvent(JsonPrimitive("page")))?.input)
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeNativeToolStreamParser(request).accept(toolUseEvent(
                JsonObject(input + ("mode" to JsonPrimitive("standard"))), toolName = "WebFetch"))
        }
    }

    @Test
    fun `real Claude Code returns standard WebSearch sources when enabled`(): Unit = realSearch("standard")

    @Test
    fun `real Claude Code returns extended WebSearch sources when enabled`(): Unit = realSearch("extended")

    private fun realSearch(mode: String): Unit = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking
        val executor = ProcessClaudeCodeCliExecutor(realClaudeExecutable())
        val invocation = invocation(mode)
        try {
            val response = withTimeout(120_000L) {
                executor.executeNativeTool(command(ClaudeCodeNativeTool.WEB_SEARCH, invocation.input), invocation)
            }
            assertEquals(invocation.input, response.input)
            val result = response.result.jsonObject
            assertEquals(invocation.input.getValue("query"), result["query"])
            assertTrue(containsSource(result.getValue("results")), "Expected native search sources, not a prose completion or an error")
        } finally {
            executor.shutdown()
        }
    }

    private fun containsSource(value: JsonElement): Boolean = when (value) {
        is JsonObject -> (value["url"] as? JsonPrimitive)?.contentOrNull?.startsWith("https://") == true || value.values.any(::containsSource)
        is JsonArray -> value.any(::containsSource)
        else -> false
    }

    private fun command(tool: ClaudeCodeNativeTool, input: JsonObject = invocation().input): ClaudeCodeCommand =
        ClaudeCodeCommand(
            modelName = "haiku",
            workspaceDirectory = null,
            systemPrompt = "Use the selected native tool exactly once with the exact JSON arguments supplied. Do not change the query, mode or domain filters.",
            userPrompt = "Invoke ${tool.cliName} exactly once with these exact arguments: $input",
            effort = null,
            reasoningMode = null,
            resumeSessionId = null,
            noSessionPersistence = true,
            nativeTools = setOf(tool),
        )

    private fun invocation(mode: String = "standard"): ClaudeCodeNativeToolInvocation =
        ClaudeCodeNativeToolInvocation(
            tool = ClaudeCodeNativeTool.WEB_SEARCH,
            input = jsonObject("query" to JsonPrimitive("Kotlin coroutines"), "mode" to JsonPrimitive(mode)),
        )

    private fun toolUseEvent(input: JsonObject, toolName: String = "WebSearch", toolUseId: String = TOOL_USE_ID): JsonObject =
        jsonObject(
            "type" to JsonPrimitive("assistant"),
            "message" to jsonObject(
                "content" to JsonArray(
                    listOf(
                        jsonObject(
                            "type" to JsonPrimitive("tool_use"),
                            "id" to JsonPrimitive(toolUseId),
                            "name" to JsonPrimitive(toolName),
                            "input" to input,
                        )
                    )
                )
            ),
        )

    private fun toolResultEvent(nativeResult: JsonElement, toolUseId: String = TOOL_USE_ID): JsonObject =
        jsonObject(
            "type" to JsonPrimitive("user"),
            "message" to jsonObject(
                "content" to JsonArray(
                    listOf(
                        jsonObject(
                            "type" to JsonPrimitive("tool_result"),
                            "tool_use_id" to JsonPrimitive(toolUseId),
                            "content" to JsonPrimitive("fallback"),
                        )
                    )
                )
            ),
            "tool_use_result" to nativeResult,
        )

    private fun jsonObject(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(mapOf(*entries))

    private fun realClaudeCodeEnabled(): Boolean =
        System.getProperty("gromozeka.claudeCode.real") == "true" ||
            System.getenv("GROMOZEKA_CLAUDE_CODE_REAL") == "true"

    private fun realClaudeExecutable(): String =
        System.getProperty("gromozeka.claudeCode.executable")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GROMOZEKA_CLAUDE_CODE_EXECUTABLE")?.takeIf { it.isNotBlank() }
            ?: "claude"

    private class NativeToolProcess : ClaudeCodeCliProcess {
        override val sessionId: String? = null
        override val isAlive: Boolean
            get() = !closed
        var closed = false
            private set

        override suspend fun execute(userPrompt: String): ClaudeCodeCliResponse =
            error("Semantic execution is not expected")

        override suspend fun executeNativeTool(
            userPrompt: String,
            invocation: ClaudeCodeNativeToolInvocation,
        ): ClaudeCodeNativeToolResponse =
            ClaudeCodeNativeToolResponse(
                tool = invocation.tool,
                input = invocation.input,
                result = JsonNull,
            )

        override suspend fun close() {
            closed = true
        }
    }

    private companion object {
        const val TOOL_USE_ID = "tool-use-1"
    }
}
