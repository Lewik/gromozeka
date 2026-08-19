package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiReasoningMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClaudeCodeProcessCacheTest {
    @Test
    fun reusesProcessForMatchingSessionAndLaunchConfiguration() = runBlocking {
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            val first = executor.execute(command(cacheKey = "conversation"))
            val second = executor.execute(
                command(
                    cacheKey = "conversation",
                    resumeSessionId = first.sessionId,
                    userPrompt = "second",
                )
            )

            assertEquals(1, factory.started.size)
            assertEquals(listOf("first", "second"), factory.started.single().prompts)
            assertEquals(first.sessionId, second.sessionId)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun replacesProcessWhenLaunchConfigurationChanges() = runBlocking {
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            val first = executor.execute(command(cacheKey = "conversation"))
            executor.execute(
                command(
                    cacheKey = "conversation",
                    resumeSessionId = first.sessionId,
                    systemPrompt = "updated system prompt",
                )
            )

            assertEquals(2, factory.started.size)
            assertEquals(first.sessionId, factory.started[1].startCommand.resumeSessionId)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun replacesProcessWhenReasoningModeChanges() = runBlocking {
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            val first = executor.execute(
                command(
                    cacheKey = "conversation",
                    reasoningMode = AiReasoningMode.ADAPTIVE,
                )
            )
            executor.execute(
                command(
                    cacheKey = "conversation",
                    resumeSessionId = first.sessionId,
                    reasoningMode = AiReasoningMode.DISABLED,
                )
            )

            assertEquals(2, factory.started.size)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun appliesExplicitReasoningModeToClaudeCodeEnvironment() {
        val adaptive = mutableMapOf(
            "CLAUDE_CODE_DISABLE_THINKING" to "1",
            "CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING" to "1",
            "MAX_THINKING_TOKENS" to "16000",
        )
        adaptive.applyClaudeCodeReasoningMode(AiReasoningMode.ADAPTIVE)

        assertEquals(null, adaptive["CLAUDE_CODE_DISABLE_THINKING"])
        assertEquals(null, adaptive["CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING"])
        assertEquals(null, adaptive["MAX_THINKING_TOKENS"])

        val disabled = mutableMapOf("MAX_THINKING_TOKENS" to "16000")
        disabled.applyClaudeCodeReasoningMode(AiReasoningMode.DISABLED)

        assertEquals("1", disabled["CLAUDE_CODE_DISABLE_THINKING"])
        assertEquals(null, disabled["CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING"])
        assertEquals(null, disabled["MAX_THINKING_TOKENS"])
    }

    @Test
    fun resultParserKeepsThinkingFromLatestTopLevelAssistantMessage() {
        val parser = ClaudeCodeResultStreamParser()
        parser.accept(
            assistantEvent(
                thinking("old summary", "old-signature"),
                messageId = "old-message",
            )
        )
        parser.accept(
            assistantEvent(
                thinking("nested summary", "nested-signature"),
                messageId = "nested-message",
                parentToolUseId = "tool-1",
            )
        )
        parser.accept(
            assistantEvent(
                thinking("current summary", "current-signature"),
                messageId = "current-message",
            )
        )
        parser.accept(
            assistantEvent(
                redactedThinking("opaque-signature"),
                messageId = "current-message",
            )
        )
        parser.accept(
            assistantEvent(
                text("done"),
                messageId = "current-message",
            )
        )

        val response = requireNotNull(
            parser.accept(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("result"),
                        "subtype" to JsonPrimitive("success"),
                        "result" to JsonPrimitive("done"),
                        "session_id" to JsonPrimitive("session-1"),
                        "usage" to JsonObject(emptyMap()),
                        "is_error" to JsonPrimitive(false),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                ClaudeCodeThinkingBlock("current summary", "current-signature"),
                ClaudeCodeThinkingBlock("", "opaque-signature"),
            ),
            response.thinking,
        )
    }

    @Test
    fun resultParserKeepsCompactionBoundaries() {
        val parser = ClaudeCodeResultStreamParser()
        val boundary = JsonObject(
            mapOf(
                "type" to JsonPrimitive("system"),
                "subtype" to JsonPrimitive("compact_boundary"),
                "compact_metadata" to JsonObject(
                    mapOf(
                        "trigger" to JsonPrimitive("auto"),
                        "pre_tokens" to JsonPrimitive(180_000),
                    )
                ),
            )
        )

        assertEquals(null, parser.accept(boundary))
        val response = requireNotNull(
            parser.accept(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("result"),
                        "subtype" to JsonPrimitive("success"),
                        "result" to JsonPrimitive("done"),
                        "session_id" to JsonPrimitive("session-1"),
                        "usage" to JsonObject(emptyMap()),
                        "is_error" to JsonPrimitive(false),
                    )
                )
            )
        )

        assertEquals(listOf(boundary), response.compactionBoundaries)
    }

    @Test
    fun resultParserUsesLatestMainMessageIterationForContextUsage() {
        val parser = ClaudeCodeResultStreamParser()
        val response = requireNotNull(
            parser.accept(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("result"),
                        "subtype" to JsonPrimitive("success"),
                        "result" to JsonPrimitive("done"),
                        "session_id" to JsonPrimitive("session-1"),
                        "usage" to usage(
                            inputTokens = 1_030_000,
                            iterations = JsonArray(
                                listOf(
                                    usage(400_000, type = "message"),
                                    usage(112_000, type = "tool"),
                                    usage(518_000, type = "message"),
                                )
                            ),
                        ),
                        "is_error" to JsonPrimitive(false),
                    )
                )
            )
        )

        assertEquals(1_030_000, response.usage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
        assertEquals(518_000, response.contextUsage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun resultParserDoesNotTreatIterationRollupAsContextWithoutMainMessageUsage() {
        val response = requireNotNull(
            ClaudeCodeResultStreamParser().accept(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("result"),
                        "subtype" to JsonPrimitive("success"),
                        "result" to JsonPrimitive("done"),
                        "session_id" to JsonPrimitive("session-1"),
                        "usage" to usage(
                            inputTokens = 1_030_000,
                            iterations = JsonArray(listOf(usage(1_030_000, type = "tool"))),
                        ),
                        "is_error" to JsonPrimitive(false),
                    )
                )
            )
        )

        assertEquals(null, response.contextUsage)
    }

    @Test
    fun evictsLeastRecentlyUsedIdleProcessAtConfiguredLimit() = runBlocking {
        var now = 0L
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(
            processFactory = factory,
            nanoTime = { now },
        )
        try {
            val first = executor.execute(command(cacheKey = "first", maxCachedProcesses = 2))
            now++
            val second = executor.execute(command(cacheKey = "second", maxCachedProcesses = 2))
            now++
            executor.execute(
                command(
                    cacheKey = "first",
                    maxCachedProcesses = 2,
                    resumeSessionId = first.sessionId,
                )
            )
            now++
            executor.execute(command(cacheKey = "third", maxCachedProcesses = 2))
            now++
            executor.execute(
                command(
                    cacheKey = "second",
                    maxCachedProcesses = 2,
                    resumeSessionId = second.sessionId,
                )
            )

            assertEquals(4, factory.started.size)
            assertEquals("second", factory.started.last().startCommand.cacheKey)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun expiresIdleProcessAfterConfiguredTtl() = runBlocking {
        var now = 0L
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(
            processFactory = factory,
            nanoTime = { now },
        )
        try {
            val first = executor.execute(
                command(
                    cacheKey = "conversation",
                    processIdleTtlMinutes = 60,
                )
            )
            now = 61.minutes.inWholeNanoseconds
            executor.execute(
                command(
                    cacheKey = "conversation",
                    processIdleTtlMinutes = 60,
                    resumeSessionId = first.sessionId,
                )
            )

            assertEquals(2, factory.started.size)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun doesNotRetryFailedProcessAutomatically() = runBlocking {
        val factory = FakeProcessFactory(failFirstProcess = true)
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            assertFailsWith<IllegalStateException> {
                executor.execute(command(cacheKey = "conversation"))
            }
            assertEquals(1, factory.started.size)

            executor.execute(command(cacheKey = "conversation"))
            assertEquals(2, factory.started.size)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun bypassesCacheWithoutDurableSessionKey() = runBlocking {
        val factory = FakeProcessFactory()
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            executor.execute(command(cacheKey = null, noSessionPersistence = true))
            executor.execute(command(cacheKey = null, noSessionPersistence = true))

            assertEquals(2, factory.started.size)
            assertTrue(factory.started.all(FakeProcess::closed))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun removesAndClosesProcessWhenCallIsCancelled() = runBlocking {
        val process = BlockingProcess()
        val executor = ProcessClaudeCodeCliExecutor(
            processFactory = ClaudeCodeCliProcessFactory { process },
        )
        try {
            val call = launch {
                executor.execute(command(cacheKey = "conversation"))
            }
            process.started.await()
            call.cancelAndJoin()

            withTimeout(1.seconds) {
                while (!process.closed) yield()
            }
            assertTrue(process.closed)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun closesProcessCreatedAfterAcquireCallerWasCancelled() = runBlocking {
        val factoryStarted = CompletableDeferred<Unit>()
        val allowFactoryToFinish = CompletableDeferred<Unit>()
        val process = FakeProcess(
            startCommand = command(cacheKey = "conversation"),
            generatedSessionId = "session",
            fail = false,
        )
        val executor = ProcessClaudeCodeCliExecutor(
            processFactory = ClaudeCodeCliProcessFactory {
                factoryStarted.complete(Unit)
                allowFactoryToFinish.await()
                process
            },
        )
        try {
            val call = launch {
                executor.execute(command(cacheKey = "conversation"))
            }
            factoryStarted.await()
            call.cancel()
            allowFactoryToFinish.complete(Unit)
            call.join()

            withTimeout(1.seconds) {
                while (!process.closed) yield()
            }
            assertTrue(process.closed)
        } finally {
            executor.shutdown()
        }
    }

    private fun command(
        cacheKey: String?,
        maxCachedProcesses: Int = AiConnection.ClaudeCode.DEFAULT_MAX_CACHED_PROCESSES,
        processIdleTtlMinutes: Int = AiConnection.ClaudeCode.DEFAULT_PROCESS_IDLE_TTL_MINUTES,
        resumeSessionId: String? = null,
        systemPrompt: String = "system prompt",
        userPrompt: String = "first",
        noSessionPersistence: Boolean = false,
        reasoningMode: AiReasoningMode? = null,
    ): ClaudeCodeCommand =
        ClaudeCodeCommand(
            connectionId = "connection",
            executablePath = "claude",
            cacheKey = cacheKey,
            maxCachedProcesses = maxCachedProcesses,
            processIdleTtlMinutes = processIdleTtlMinutes,
            modelName = "haiku",
            workspaceDirectory = null,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonSchema = null,
            effort = null,
            reasoningMode = reasoningMode,
            resumeSessionId = resumeSessionId,
            noSessionPersistence = noSessionPersistence,
        )

    private fun assistantEvent(
        vararg content: JsonObject,
        messageId: String,
        parentToolUseId: String? = null,
    ): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("assistant"),
                "parent_tool_use_id" to (parentToolUseId?.let(::JsonPrimitive) ?: JsonNull),
                "message" to JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(messageId),
                        "content" to JsonArray(content.toList()),
                    )
                ),
            )
        )

    private fun thinking(text: String, signature: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("thinking"),
                "thinking" to JsonPrimitive(text),
                "signature" to JsonPrimitive(signature),
            )
        )

    private fun redactedThinking(signature: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("redacted_thinking"),
                "data" to JsonPrimitive(signature),
            )
        )

    private fun text(value: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(value),
            )
        )

    private fun usage(
        inputTokens: Int,
        type: String? = null,
        iterations: JsonArray? = null,
    ): JsonObject =
        JsonObject(
            buildMap {
                type?.let { put("type", JsonPrimitive(it)) }
                put("input_tokens", JsonPrimitive(inputTokens))
                put("output_tokens", JsonPrimitive(0))
                put("cache_creation_input_tokens", JsonPrimitive(0))
                put("cache_read_input_tokens", JsonPrimitive(0))
                iterations?.let { put("iterations", it) }
            }
        )

    private class FakeProcessFactory(
        private val failFirstProcess: Boolean = false,
    ) : ClaudeCodeCliProcessFactory {
        val started = mutableListOf<FakeProcess>()

        override suspend fun start(command: ClaudeCodeCommand): ClaudeCodeCliProcess =
            FakeProcess(
                startCommand = command,
                generatedSessionId = command.resumeSessionId ?: "session-${started.size + 1}",
                fail = failFirstProcess && started.isEmpty(),
            ).also(started::add)
    }

    private class FakeProcess(
        val startCommand: ClaudeCodeCommand,
        private val generatedSessionId: String,
        private val fail: Boolean,
    ) : ClaudeCodeCliProcess {
        val prompts = mutableListOf<String>()

        @Volatile
        override var sessionId: String? = null
            private set

        @Volatile
        var closed = false
            private set

        override val isAlive: Boolean
            get() = !closed

        override suspend fun execute(userPrompt: String): ClaudeCodeCliResponse {
            prompts += userPrompt
            if (fail) error("process failed")
            sessionId = generatedSessionId
            return ClaudeCodeCliResponse(
                result = "ok",
                structuredOutput = null,
                sessionId = generatedSessionId,
                usage = JsonObject(emptyMap()),
                finishReason = "success",
                raw = JsonObject(mapOf("type" to JsonPrimitive("result"))),
            )
        }

        override suspend fun close() {
            closed = true
        }
    }

    private class BlockingProcess : ClaudeCodeCliProcess {
        val started = CompletableDeferred<Unit>()

        @Volatile
        var closed = false
            private set

        override val sessionId: String? = null

        override val isAlive: Boolean
            get() = !closed

        override suspend fun execute(userPrompt: String): ClaudeCodeCliResponse {
            started.complete(Unit)
            awaitCancellation()
        }

        override suspend fun close() {
            closed = true
        }
    }
}
