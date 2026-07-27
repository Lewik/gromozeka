package com.gromozeka.infrastructure.ai.claude

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `real Claude Code returns a native WebSearch result when enabled`() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking
        val executor = ProcessClaudeCodeCliExecutor(realClaudeExecutable())
        val invocation = invocation()

        try {
            val response = executor.executeNativeTool(
                command(ClaudeCodeNativeTool.WEB_SEARCH),
                invocation,
            )

            assertEquals(invocation.input, response.input)
            assertTrue(response.result !is JsonNull)
        } finally {
            executor.shutdown()
        }
    }

    private fun command(tool: ClaudeCodeNativeTool): ClaudeCodeCommand =
        ClaudeCodeCommand(
            modelName = "haiku",
            workspaceDirectory = null,
            systemPrompt = "Use the selected native tool exactly once.",
            userPrompt = "Search for Kotlin coroutines.",
            jsonSchema = null,
            effort = null,
            resumeSessionId = null,
            noSessionPersistence = true,
            nativeTools = setOf(tool),
        )

    private fun invocation(): ClaudeCodeNativeToolInvocation =
        ClaudeCodeNativeToolInvocation(
            tool = ClaudeCodeNativeTool.WEB_SEARCH,
            input = jsonObject("query" to JsonPrimitive("Kotlin coroutines")),
        )

    private fun toolUseEvent(input: JsonObject): JsonObject =
        jsonObject(
            "type" to JsonPrimitive("assistant"),
            "message" to jsonObject(
                "content" to JsonArray(
                    listOf(
                        jsonObject(
                            "type" to JsonPrimitive("tool_use"),
                            "id" to JsonPrimitive(TOOL_USE_ID),
                            "name" to JsonPrimitive("WebSearch"),
                            "input" to input,
                        )
                    )
                )
            ),
        )

    private fun toolResultEvent(nativeResult: JsonObject): JsonObject =
        jsonObject(
            "type" to JsonPrimitive("user"),
            "message" to jsonObject(
                "content" to JsonArray(
                    listOf(
                        jsonObject(
                            "type" to JsonPrimitive("tool_result"),
                            "tool_use_id" to JsonPrimitive(TOOL_USE_ID),
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
