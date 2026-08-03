package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.ClaudeCodeSessionState
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeCliRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun parsesWrapperToolCallFromFakeExecutor() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("tool_call"),
                    "action_name" to JsonPrimitive("read_file"),
                    "arguments" to jsonObject("path" to JsonPrimitive("README.md")),
                )
            )
        )
        val runtime = runtime(executor)

        val response = runtime.call(
            request(
                messages = listOf(userMessage("Read README.md")),
                tools = listOf(readFileTool()),
            )
        )

        val toolCall = response.toolCalls.single()
        assertEquals("read_file", toolCall.call.name)
        assertEquals("README.md", toolCall.call.input.jsonObject["path"]?.jsonPrimitive?.contentOrNull)
        val systemPrompt = executor.commands.single().systemPrompt
        assertTrue(systemPrompt.contains("<gromozeka_external_action_protocol>"))
        assertTrue(systemPrompt.contains("external Gromozeka actions, not Claude Code tools"))
        assertTrue(systemPrompt.contains("Never invoke an external action name through Claude Code native tool use"))
        assertTrue(systemPrompt.contains("<action name=\"read_file\">"))
        assertTrue(executor.commands.single().userPrompt.endsWith("</gromozeka_external_action_reminder>"))
        val command = executor.commands.single()
        val schema = command.jsonSchema?.jsonObject ?: error("Expected Claude Code wrapper schema")
        assertEquals(
            listOf("response"),
            schema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        val responseSchema = schema["properties"]
            ?.jsonObject
            ?.get("response")
            ?.jsonObject
            ?: error("Expected nested Claude Code response schema")
        val requiredPropertiesByBranch = responseSchema["anyOf"]
            ?.jsonArray
            ?.map { branch ->
                branch.jsonObject["required"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?.toSet()
            }
            ?.toSet()
        assertEquals(
            setOf(
                setOf("kind", "final_answer"),
                setOf("kind", "action_name", "arguments"),
            ),
            requiredPropertiesByBranch,
        )
        assertFalse(command.noSessionPersistence)
    }

    @Test
    fun resumesSessionWithExternalActionResult() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("tool_call"),
                    "action_name" to JsonPrimitive("read_file"),
                    "arguments" to jsonObject("path" to JsonPrimitive("README.md")),
                )
            ),
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("Gromozeka"),
                )
            ),
        )
        val runtime = runtime(executor)
        val firstUser = userMessage("Read README.md")
        val firstResponse = runtime.call(request(messages = listOf(firstUser), tools = listOf(readFileTool())))
        val toolCall = firstResponse.toolCalls.single()
        val assistantToolCall = Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = firstUser.conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(toolCall),
            createdAt = Clock.System.now(),
        )
        val toolResult = Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = firstUser.conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCall.id,
                    toolName = toolCall.call.name,
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("# Gromozeka")),
                )
            ),
            createdAt = Clock.System.now(),
        )

        val finalResponse = runtime.call(
            request(
                messages = listOf(firstUser, assistantToolCall, toolResult),
                tools = listOf(readFileTool()),
            )
        )

        assertEquals("Gromozeka", finalResponse.messages.single().text())
        assertEquals("session-1", executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("<tool_result"))
        assertTrue(executor.commands[1].userPrompt.contains("# Gromozeka"))
    }

    @Test
    fun resumesSessionWithOnlyNewMessagesWhenHistoryMatches() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("First")),
            ),
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Second")),
            ),
        )
        val runtime = runtime(executor)
        val firstUser = userMessage("First prompt")

        val first = runtime.call(request(messages = listOf(firstUser), tools = listOf(readFileTool())))
        val assistant = assistantMessage("First")
        runtime.call(request(messages = listOf(firstUser, assistant, userMessage("Second prompt")), tools = listOf(readFileTool())))

        assertEquals("First", first.messages.single().text())
        assertNull(executor.commands[0].resumeSessionId)
        assertEquals("session-1", executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("Second prompt"))
        assertFalse(executor.commands[1].userPrompt.contains("First prompt"))
    }

    @Test
    fun fallsBackToFullTranscriptWhenHistoryChangedBeforeResumePoint() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("First")),
            ),
            response(
                sessionId = "session-2",
                structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Second")),
            ),
        )
        val runtime = runtime(executor)

        runtime.call(request(messages = listOf(userMessage("Original")), tools = listOf(readFileTool())))
        runtime.call(request(messages = listOf(userMessage("Edited"), assistantMessage("First"), userMessage("Second")), tools = listOf(readFileTool())))

        assertNull(executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("Edited"))
        assertTrue(executor.commands[1].userPrompt.contains("First"))
    }

    @Test
    fun passesConfiguredOpus5ReasoningToClaudeCode() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("OK"),
                )
            )
        )
        val runtime = runtime(executor)

        runtime.call(
            request(
                messages = listOf(userMessage("Reply with OK")),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    reasoning = AiReasoningConfig(
                        mode = AiReasoningMode.ADAPTIVE,
                        effort = AiReasoningEffort.XHIGH,
                        display = AiReasoningDisplay.SUMMARIZED,
                    ),
                    toolContext = testToolContext(),
                ),
            )
        )

        val command = executor.commands.single()
        assertEquals(AiReasoningEffort.XHIGH, command.effort)
        assertEquals(AiReasoningMode.ADAPTIVE, command.reasoningMode)
        val args = ProcessClaudeCodeCliExecutor("claude")
            .buildArgs(command, "/tmp/gromozeka-system.md")
        assertTrue(args.windowed(2).contains(listOf("--effort", "xhigh")))
        assertTrue(args.windowed(2).contains(listOf("--input-format", "stream-json")))
        assertTrue(args.windowed(2).contains(listOf("--output-format", "stream-json")))
        assertTrue(args.contains("--verbose"))
    }

    @Test
    fun preservesProviderThinkingSummaryAndSignature() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("OK"),
                ),
                thinking = listOf(ClaudeCodeThinkingBlock("Checked the constraints.", "signed-thinking")),
            )
        )

        val response = runtime(executor).call(
            request(
                messages = listOf(userMessage("Reply with OK")),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    reasoning = AiReasoningConfig(
                        mode = AiReasoningMode.ADAPTIVE,
                        effort = AiReasoningEffort.XHIGH,
                        display = AiReasoningDisplay.SUMMARIZED,
                    ),
                    toolContext = testToolContext(),
                ),
            )
        )

        val thinking = response.messages.single().content
            .filterIsInstance<Conversation.Message.ContentItem.Thinking>()
            .single()
        assertEquals("Checked the constraints.", thinking.thinking)
        assertEquals("signed-thinking", thinking.signature)
    }

    @Test
    fun omittedDisplayPreservesSignatureWithoutExposingSummary() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("OK"),
                ),
                thinking = listOf(ClaudeCodeThinkingBlock("Private summary.", "signed-thinking")),
            )
        )

        val response = runtime(executor).call(
            request(
                messages = listOf(userMessage("Reply with OK")),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    reasoning = AiReasoningConfig(
                        mode = AiReasoningMode.ADAPTIVE,
                        effort = AiReasoningEffort.HIGH,
                        display = AiReasoningDisplay.OMITTED,
                    ),
                    toolContext = testToolContext(),
                ),
            )
        )

        val thinking = response.messages.single().content
            .filterIsInstance<Conversation.Message.ContentItem.Thinking>()
            .single()
        assertEquals("", thinking.thinking)
        assertEquals("signed-thinking", thinking.signature)
    }

    @Test
    fun `passes user image attachment through Claude Code stream json`() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("Image received"),
                )
            )
        )
        val runtime = runtime(executor)
        val message = userMessage("Inspect this screenshot").copy(
            content = listOf(
                Conversation.Message.ContentItem.UserMessage("Inspect this screenshot"),
                Conversation.Message.ContentItem.ImageItem(
                    Conversation.Message.ImageSource.Base64ImageSource(
                        data = "AQID",
                        mediaType = "image/png",
                    )
                ),
            )
        )

        runtime.call(request(messages = listOf(message), tools = emptyList()))

        val command = executor.commands.single()
        assertEquals(1, command.userContentBlocks.size)
        assertEquals("image", command.userContentBlocks.single()["type"]?.jsonPrimitive?.content)
        assertTrue(command.userPrompt.contains("Inspect this screenshot"))
    }

    @Test
    fun rejectsUnsupportedClaudeCodeReasoningControls() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("OK"),
                )
            )
        )
        val runtime = runtime(executor)

        listOf(
            AiReasoningConfig(mode = AiReasoningMode.TOKEN_BUDGET, budgetTokens = 16_000),
            AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, display = AiReasoningDisplay.FULL),
            AiReasoningConfig(mode = AiReasoningMode.DISABLED, effort = AiReasoningEffort.XHIGH),
        ).forEach { reasoning ->
            assertFailsWith<IllegalArgumentException> {
                runtime.call(
                    request(
                        messages = listOf(userMessage("Reply with OK")),
                        tools = emptyList(),
                        options = AiRuntimeOptions(
                            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                            reasoning = reasoning,
                            toolContext = testToolContext(),
                        ),
                    )
                )
            }
        }

        assertTrue(executor.commands.isEmpty())
    }

    @Test
    fun realClaudeCodeReturnsStructuredFinalAnswerWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val response = runtime.call(
            request(
                messages = listOf(userMessage("Return exactly: OK")),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    responseFormat = AiResponseFormat.JsonSchema(
                        name = "answer",
                        schema = jsonObject(
                            "type" to JsonPrimitive("object"),
                            "additionalProperties" to JsonPrimitive(false),
                            "properties" to jsonObject("answer" to jsonObject("type" to JsonPrimitive("string"))),
                            "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("answer"))),
                        ),
                    ),
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext("real-claude-final-answer-test"),
                ),
            )
        )

        assertTrue(response.messages.single().text().contains("OK"))
    }

    @Test
    fun realClaudeCodePreservesOpus5ThinkingEnvelopeWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled() || realClaudeModel() != "claude-opus-5") return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val response = runtime.call(
            request(
                messages = listOf(
                    userMessage(
                        "Determine the smallest positive integer divisible by every integer from 1 through 10. " +
                            "Return exactly OPUS5_REASONING_OK:<integer>."
                    )
                ),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    reasoning = AiReasoningConfig(
                        mode = AiReasoningMode.ADAPTIVE,
                        effort = AiReasoningEffort.XHIGH,
                        display = AiReasoningDisplay.SUMMARIZED,
                    ),
                    toolContext = testToolContext("real-claude-opus5-thinking-test"),
                ),
            )
        )

        assertTrue(response.messages.single().text().contains("OPUS5_REASONING_OK:2520"))
        val thinking = response.messages.single().content
            .filterIsInstance<Conversation.Message.ContentItem.Thinking>()
        assertTrue(thinking.isNotEmpty(), "Expected Claude Code Opus 5 to return a thinking envelope")
        assertTrue(
            thinking.all { !it.signature.isNullOrBlank() },
            "Expected every Claude Code Opus 5 thinking block to preserve its signature",
        )
        println("Claude Code Opus 5 thinking summary lengths: ${thinking.map { it.thinking.length }}")
    }

    @Test
    fun realClaudeCodeReturnsWrapperToolCallWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val response = runtime.call(
            request(
                messages = listOf(userMessage("Call the read_file tool for README.md. Do not answer directly.")),
                tools = listOf(readFileTool()),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext("real-claude-tool-call-test"),
                ),
            )
        )

        val toolCall = response.toolCalls.single()
        assertEquals("read_file", toolCall.call.name)
        assertEquals("README.md", toolCall.call.input.jsonObject["path"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun realClaudeCodeReturnsWrapperFinalAnswerWithToolsInAutoModeWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val response = runtime.call(
            request(
                messages = listOf(userMessage("Do not use external actions. Return exactly: AUTO_FINAL_OK")),
                tools = listOf(readFileTool()),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext("real-claude-auto-final-answer-test"),
                ),
            )
        )

        assertTrue(response.toolCalls.isEmpty())
        assertTrue(response.messages.single().text().contains("AUTO_FINAL_OK"))
    }

    @Test
    fun realClaudeCodeResumesSessionAndReadsPromptCacheWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val conversationId = "real-claude-session-cache-test-${Clock.System.now().toEpochMilliseconds()}"
        val referenceText = largeReferenceText(conversationId)
        val firstUser = userMessage(
            "$referenceText\n\n" +
                "Acknowledge that you received the reference text. Reply exactly: FIRST_READY"
        )

        val firstResponse = runtime.call(
            request(
                messages = listOf(firstUser),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext(conversationId),
                ),
            )
        )
        val firstAssistant = assistantMessage(firstResponse.messages.single().text())

        val secondResponse = runtime.call(
            request(
                messages = listOf(
                    firstUser,
                    firstAssistant,
                    userMessage("Using the existing reference text, reply exactly: SECOND_READY"),
                ),
                tools = emptyList(),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext(conversationId),
                ),
            )
        )

        assertEquals(true, secondResponse.providerMetadata["resumed"])
        val firstUsage = firstResponse.usage ?: error("Claude Code real cache test expected first usage data")
        val usage = secondResponse.usage ?: error("Claude Code real cache test expected usage data")
        println(
            "Claude Code real cache usage: firstUsage=$firstUsage, " +
                "secondUsage=$usage, minimumExpectedCacheReadTokens=$MIN_SIGNIFICANT_CACHE_READ_TOKENS"
        )
        assertTrue(
            firstUsage.cacheCreationTokens + firstUsage.cacheReadTokens >= MIN_SIGNIFICANT_CACHE_READ_TOKENS,
            "Expected first Claude Code call to create or read a significant prompt cache block. firstUsage=$firstUsage",
        )
        assertTrue(
            usage.cacheReadTokens >= MIN_SIGNIFICANT_CACHE_READ_TOKENS,
            "Expected prompt cache read on resumed Claude Code session. firstUsage=$firstUsage, secondUsage=$usage",
        )
        assertTrue(secondResponse.messages.single().text().contains("SECOND_READY"))
    }

    @Test
    fun realClaudeCodePromptCacheContinuesAcrossMultipleResumedTurnsWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val conversationId = "real-claude-multi-turn-cache-test-${Clock.System.now().toEpochMilliseconds()}"
        val firstUser = userMessage(
            "${largeReferenceText(conversationId)}\n\n" +
                "Acknowledge that you received the reference text. Reply exactly: TURN_1_READY"
        )

        val messages = mutableListOf<Conversation.Message>(firstUser)
        val usages = mutableListOf<Pair<String, com.gromozeka.domain.model.ai.AiUsage>>()

        repeat(4) { index ->
            val turnNumber = index + 1
            if (turnNumber > 1) {
                messages += userMessage("Using the existing reference text, reply exactly: TURN_${turnNumber}_READY")
            }

            val response = runtime.call(
                request(
                    messages = messages.toList(),
                    tools = emptyList(),
                    options = AiRuntimeOptions(
                        assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                        toolContext = testToolContext(conversationId),
                    ),
                )
            )
            usages += "turn_$turnNumber" to (response.usage ?: error("Claude Code real multi-turn cache test expected usage data"))
            val assistantText = response.messages.single().text()
            assertTrue(assistantText.contains("TURN_${turnNumber}_READY"), assistantText)
            messages += assistantMessage(assistantText)
        }

        println(
            "Claude Code multi-turn cache usage: " +
                usages.joinToString { (turn, usage) -> "$turn=$usage" }
        )

        usages.drop(1).forEach { (turn, usage) ->
            assertTrue(
                usage.cacheReadTokens >= MIN_SIGNIFICANT_CACHE_READ_TOKENS,
                "Expected significant prompt cache read on $turn. usages=$usages",
            )
        }
        val resumedCacheReads = usages.drop(1).map { it.second.cacheReadTokens }
        assertTrue(
            resumedCacheReads.zipWithNext().all { (previous, next) -> next >= previous },
            "Expected resumed cache reads to be non-decreasing. usages=$usages",
        )
        assertTrue(
            resumedCacheReads.last() > resumedCacheReads.first(),
            "Expected resumed cache reads to grow after appended turns. usages=$usages",
        )
    }

    private fun runtime(executor: ClaudeCodeCliExecutor): ClaudeCodeCliRuntime =
        ClaudeCodeCliRuntime(
            executor = executor,
            connectionId = "claude-code",
            modelConfigurationId = "claude-code-haiku",
            modelName = realClaudeModel(),
            workspaceDirectory = null,
            sessionStateRepository = InMemoryClaudeCodeSessionStateRepository(),
            sessionLocks = java.util.concurrent.ConcurrentHashMap(),
        )

    private fun request(
        messages: List<Conversation.Message>,
        tools: List<AiToolCallback>,
        options: AiRuntimeOptions = AiRuntimeOptions(
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
            toolContext = testToolContext(),
        ),
    ): AiRuntimeRequest =
        AiRuntimeRequest(
            systemPrompts = listOf("You are a precise test assistant."),
            messages = messages,
            tools = tools,
            options = options,
        )

    private fun userMessage(text: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = Conversation.Id("test-conversation"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
            createdAt = Clock.System.now(),
        )

    private fun assistantMessage(text: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = Conversation.Id("test-conversation"),
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    structured = Conversation.Message.StructuredText(fullText = text),
                )
            ),
            createdAt = Clock.System.now(),
        )

    private fun response(
        sessionId: String = "session-1",
        structuredOutput: JsonElement,
        thinking: List<ClaudeCodeThinkingBlock> = emptyList(),
    ): ClaudeCodeCliResponse {
        val envelope = jsonObject("response" to structuredOutput)
        return ClaudeCodeCliResponse(
            result = envelope.toString(),
            structuredOutput = envelope,
            sessionId = sessionId,
            usage = jsonObject(
                "input_tokens" to JsonPrimitive(10),
                "output_tokens" to JsonPrimitive(5),
                "cache_creation_input_tokens" to JsonPrimitive(0),
                "cache_read_input_tokens" to JsonPrimitive(0),
            ),
            finishReason = "success",
            raw = jsonObject("type" to JsonPrimitive("result")),
            thinking = thinking,
        )
    }

    private fun readFileTool(): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = "read_file",
                description = "Read a project file by relative path.",
                inputSchema = """{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"}},"required":["path"]}""",
            )
            override val metadata = com.gromozeka.domain.tool.AiToolMetadata(
                executionScope = com.gromozeka.domain.tool.AiToolExecutionScope.WORKSPACE
            )

            override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String =
                error("Unit tests must not execute tools")
        }

    private fun jsonObject(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf(*entries))

    private fun AiAssistantMessage.text(): String =
        content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }

    private fun realClaudeCodeEnabled(): Boolean =
        System.getProperty("gromozeka.claudeCode.real") == "true" ||
            System.getenv("GROMOZEKA_CLAUDE_CODE_REAL") == "true"

    private fun realClaudeExecutable(): String =
        System.getProperty("gromozeka.claudeCode.executable")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GROMOZEKA_CLAUDE_CODE_EXECUTABLE")?.takeIf { it.isNotBlank() }
            ?: "claude"

    private fun realClaudeModel(): String =
        System.getProperty("gromozeka.claudeCode.model")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GROMOZEKA_CLAUDE_CODE_MODEL")?.takeIf { it.isNotBlank() }
            ?: "haiku"

    private fun largeReferenceText(cacheSeed: String): String =
        buildString {
            appendLine("Reference dossier for Claude Code prompt-cache verification.")
            appendLine("Unique cache seed for this test run: $cacheSeed.")
            repeat(600) { index ->
                appendLine(
                    "Section $index: Gromozeka stores conversation state, memory context, queued commands, " +
                        "tool results, provider metadata, runtime assignments, and user situation context as " +
                        "separate durable facts. The stable marker for this section is CACHE_MARKER_$index."
                )
            }
        }

    private class FakeClaudeCodeCliExecutor(
        vararg responses: ClaudeCodeCliResponse,
    ) : ClaudeCodeCliExecutor {
        private val responses = ArrayDeque(responses.toList())
        val commands = mutableListOf<ClaudeCodeCommand>()

        override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
            commands += command
            return responses.removeFirst()
        }
    }

    private class InMemoryClaudeCodeSessionStateRepository : ClaudeCodeSessionStateRepository {
        private val states = mutableMapOf<ClaudeCodeSessionState.Key, ClaudeCodeSessionState>()

        override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? =
            states[key]

        override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState {
            states[state.key] = state
            return state
        }

        override suspend fun delete(key: ClaudeCodeSessionState.Key) {
            states.remove(key)
        }
    }

    private fun testToolContext(conversationId: String = "test-conversation"): Map<String, String> =
        mapOf(
            "conversationId" to conversationId,
            "threadId" to "$conversationId-thread",
            "projectId" to "test-project",
        )

    private companion object {
        private const val MIN_SIGNIFICANT_CACHE_READ_TOKENS = 10_000
        private var messageCounter = 1
    }
}
