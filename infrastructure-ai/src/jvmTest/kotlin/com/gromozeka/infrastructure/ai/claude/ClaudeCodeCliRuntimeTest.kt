package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiStepOutcome

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    fun parsesWrapperToolCallsFromFakeExecutor() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("tool_calls"),
                    "content" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            jsonObject("kind" to JsonPrimitive("message"), "message" to JsonPrimitive("I'll read both files.")),
                            jsonObject(
                                "kind" to JsonPrimitive("tool_call"),
                                "action_name" to JsonPrimitive("read_file"),
                                "arguments" to jsonObject("path" to JsonPrimitive("README.md")),
                            ),
                            jsonObject(
                                "kind" to JsonPrimitive("tool_call"),
                                "action_name" to JsonPrimitive("read_file"),
                                "arguments" to jsonObject("path" to JsonPrimitive("LICENSE")),
                            ),
                        )
                    ),
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

        assertEquals(listOf("read_file", "read_file"), response.toolCalls.map { it.call.name })
        assertEquals(
            listOf("README.md", "LICENSE"),
            response.toolCalls.map { it.call.input.jsonObject["path"]?.jsonPrimitive?.contentOrNull },
        )
        assertEquals(2, response.toolCalls.map { it.id }.toSet().size)
        assertEquals("I'll read both files.", response.messages.single().text())
        assertTrue(response.messages.single().content.first() is Conversation.Message.ContentItem.AssistantMessage)
        assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
        val systemPrompt = executor.commands.single().systemPrompt
        assertTrue(systemPrompt.contains("<gromozeka_external_action_protocol>"))
        assertTrue(systemPrompt.contains("external Gromozeka actions, not Claude Code tools"))
        assertTrue(systemPrompt.contains("Never invoke an external action name through Claude Code native tool use"))
        assertTrue(systemPrompt.contains("Group every independent external action"))
        assertTrue(systemPrompt.contains("<action name=\"read_file\">"))
        assertTrue(executor.commands.single().userPrompt.endsWith("</system-reminder>"))
        val command = executor.commands.single()
        val schema = Json.parseToJsonElement(command.systemPrompt.substringAfter("<json_schema>\n").substringBefore("\n</json_schema>")).jsonObject
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
                setOf("kind", "content"),
            ),
            requiredPropertiesByBranch,
        )
        val toolCallsSchema = responseSchema["anyOf"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.single { branch ->
                branch["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ==
                    setOf("kind", "content")
            }
            ?.get("properties")
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?: error("Expected Claude Code tool_calls schema")
        assertEquals(1, toolCallsSchema["minItems"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            setOf("kind", "action_name", "arguments"),
            toolCallsSchema["items"]
                ?.jsonObject
                ?.get("anyOf")?.jsonArray?.first()?.jsonObject
                ?.get("required")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet(),
        )
        assertFalse(command.noSessionPersistence)
    }

    @Test
    fun resumesSessionWithExternalActionResult() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("tool_calls"),
                    "content" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            jsonObject(
                                "kind" to JsonPrimitive("tool_call"),
                                "action_name" to JsonPrimitive("read_file"),
                                "arguments" to jsonObject("path" to JsonPrimitive("README.md")),
                            ),
                            jsonObject(
                                "kind" to JsonPrimitive("tool_call"),
                                "action_name" to JsonPrimitive("read_file"),
                                "arguments" to jsonObject("path" to JsonPrimitive("LICENSE")),
                            ),
                        )
                    ),
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
        val toolCalls = firstResponse.toolCalls
        val assistantToolCall = Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = firstUser.conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = toolCalls,
            createdAt = Clock.System.now(),
        )
        val toolResult = Conversation.Message(
            id = Conversation.Message.Id("msg-${messageCounter++}"),
            conversationId = firstUser.conversationId,
            role = Conversation.Message.Role.USER,
            content = toolCalls.mapIndexed { index, toolCall ->
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCall.id,
                    toolName = toolCall.call.name,
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Text(
                            if (index == 0) "# Gromozeka" else "Gromozeka License"
                        )
                    ),
                )
            },
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
        assertTrue(executor.commands[1].userPrompt.contains("Gromozeka License"))
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
    fun exposesProviderCompactionAndResumesPastPersistedBoundary() = runBlocking {
        val boundary = jsonObject(
            "type" to JsonPrimitive("system"),
            "subtype" to JsonPrimitive("compact_boundary"),
            "compact_metadata" to jsonObject(
                "trigger" to JsonPrimitive("auto"),
                "pre_tokens" to JsonPrimitive(180_000),
            ),
        )
        val executor = FakeClaudeCodeCliExecutor(
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("First"),
                ),
                compactionBoundaries = listOf(boundary),
            ),
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("Second"),
                ),
            ),
        )
        val runtime = runtime(executor)
        val firstUser = userMessage("First prompt")
        val firstResponse = runtime.call(request(messages = listOf(firstUser), tools = listOf(readFileTool())))

        val compaction = firstResponse.messages.first().content.single() as
            Conversation.Message.ContentItem.ContextCompactionResult
        assertEquals(Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO, compaction.origin)
        assertEquals("CLAUDE_CODE", compaction.providerScope?.provider)
        assertTrue(runtime.capabilities.providerManagedAutoCompaction)

        val persistedResponses = firstResponse.messages.mapIndexed { index, message ->
            Conversation.Message(
                id = Conversation.Message.Id("persisted-$index"),
                conversationId = firstUser.conversationId,
                role = Conversation.Message.Role.ASSISTANT,
                content = message.content,
                createdAt = Clock.System.now(),
            )
        }
        runtime.call(
            request(
                messages = listOf(firstUser) + persistedResponses + userMessage("Second prompt"),
                tools = listOf(readFileTool()),
            )
        )

        assertEquals("session-1", executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("Second prompt"))
        assertFalse(executor.commands[1].userPrompt.contains("First prompt"))
    }

    @Test
    fun forkRecoversContextAfterProviderCompaction() = runBlocking {
        val boundary = jsonObject(
            "type" to JsonPrimitive("system"),
            "subtype" to JsonPrimitive("compact_boundary"),
            "compact_metadata" to jsonObject(
                "trigger" to JsonPrimitive("auto"),
                "pre_tokens" to JsonPrimitive(180_000),
            ),
        )
        val executor = FakeClaudeCodeCliExecutor(
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("First"),
                ),
                compactionBoundaries = listOf(boundary),
            ),
            response(
                sessionId = "session-1",
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("Second"),
                ),
            ),
        )
        val runtime = runtime(executor)
        val firstUser = userMessage("First prompt")
        val firstResponse = runtime.call(request(messages = listOf(firstUser), tools = listOf(readFileTool())))

        val compaction = firstResponse.messages.first().content.single() as
            Conversation.Message.ContentItem.ContextCompactionResult
        assertEquals(Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO, compaction.origin)
        assertEquals("CLAUDE_CODE", compaction.providerScope?.provider)
        assertTrue(runtime.capabilities.providerManagedAutoCompaction)

        val persistedResponses = firstResponse.messages.mapIndexed { index, message ->
            Conversation.Message(
                id = Conversation.Message.Id("persisted-$index"),
                conversationId = firstUser.conversationId,
                role = Conversation.Message.Role.ASSISTANT,
                content = message.content,
                createdAt = Clock.System.now(),
            )
        }
        runtime.call(
            request(
                messages = listOf(firstUser) + persistedResponses + userMessage("Second prompt"),
                options = AiRuntimeOptions(toolContext = testToolContext() + ("threadId" to "forked-thread")),
                tools = listOf(readFileTool()),
            )
        )

        assertNull(executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("AURORA_713"))
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
    fun passesOpus55ModelAndAdaptiveEffortToClaudeCode() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))))
        runtime(executor, modelName = "claude-opus-5-5").call(request(
            messages = listOf(userMessage("Reply with OK")), tools = emptyList(),
            options = AiRuntimeOptions(assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                reasoning = AiReasoningConfig(mode = AiReasoningMode.ADAPTIVE, effort = AiReasoningEffort.MEDIUM,
                    display = AiReasoningDisplay.SUMMARIZED), toolContext = testToolContext())))
        val command = executor.commands.single()
        assertEquals(AiReasoningMode.ADAPTIVE, command.reasoningMode)
        val args = ProcessClaudeCodeCliExecutor("claude").buildArgs(command, "/tmp/gromozeka-system.md")
        assertTrue(args.windowed(2).contains(listOf("--model", "claude-opus-5-5")))
        assertTrue(args.windowed(2).contains(listOf("--effort", "medium")))
    }

    @Test
    fun opus55RejectsDisabledThinkingBeforeLaunchingClaudeCode() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("unused"))))
        val error = assertFailsWith<IllegalArgumentException> {
            runtime(executor, modelName = "claude-opus-5-5").call(request(
                messages = listOf(userMessage("Reply with OK")), tools = emptyList(),
                options = AiRuntimeOptions(reasoning = AiReasoningConfig(mode = AiReasoningMode.DISABLED))))
        }
        assertTrue(error.message.orEmpty().contains("requires adaptive thinking"))
        assertTrue(executor.commands.isEmpty())
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
    fun preservesActuallyReceivedSummaryEvenWhenOmittedDisplayWasRequested() = runBlocking {
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
        assertEquals("Private summary.", thinking.thinking)
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
    fun `passes user document attachment through Claude Code stream json`() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(
            response(
                structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"),
                    "final_answer" to JsonPrimitive("Document received"),
                )
            )
        )
        val runtime = runtime(executor)
        val message = userMessage("Inspect this report").copy(
            content = listOf(
                Conversation.Message.ContentItem.UserMessage("Inspect this report"),
                Conversation.Message.ContentItem.DocumentItem(
                    Conversation.Message.DocumentSource.Base64DocumentSource(
                        data = "AQID",
                        mediaType = "application/pdf",
                        fileName = "report.pdf",
                    )
                ),
            )
        )

        runtime.call(request(messages = listOf(message), tools = emptyList()))

        val command = executor.commands.single()
        val document = command.userContentBlocks.single()
        assertEquals("document", document["type"]?.jsonPrimitive?.content)
        assertEquals("report.pdf", document["title"]?.jsonPrimitive?.content)
        assertEquals(
            "application/pdf",
            document["source"]?.jsonObject?.get("media_type")?.jsonPrimitive?.content,
        )
        assertTrue(command.userPrompt.contains("Inspect this report"))
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

        val executor = ProcessClaudeCodeCliExecutor(realClaudeExecutable())
        try {
            withTimeout(120_000L) {
                val runtime = runtime(executor)
                val initial = request(
                        messages = listOf(userMessage("First include a brief Russian user-facing remark saying you will read the file, then request the read_file action for README.md. Do not answer directly.")),
                        tools = listOf(readFileTool()),
                        options = AiRuntimeOptions(
                            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                            toolContext = testToolContext("real-claude-tool-call-test"),
                        ),
                    )
                val response = runtime.call(initial)

                val toolCall = response.toolCalls.single()
                assertEquals("read_file", toolCall.call.name)
                assertEquals("README.md", toolCall.call.input.jsonObject["path"]?.jsonPrimitive?.contentOrNull)
                assertTrue(response.messages.single().text().isNotBlank())
                val assistant = initial.messages.single().copy(
                    id = Conversation.Message.Id("real-remark-and-tool"),
                    role = Conversation.Message.Role.ASSISTANT,
                    content = response.messages.single().content,
                )
                val result = initial.messages.single().copy(
                    id = Conversation.Message.Id("real-tool-result"),
                    content = listOf(Conversation.Message.ContentItem.ToolResult(
                        toolUseId = toolCall.id,
                        toolName = toolCall.call.name,
                        result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("The file contains the marker PROGRESS_ROUNDTRIP_OK. Report it to the user.")),
                    )),
                )
                val final = runtime.call(initial.copy(messages = initial.messages + assistant + result))
                assertEquals(AiStepOutcome.COMPLETE, final.outcome)
                assertTrue(final.messages.joinToString { it.text() }.contains("PROGRESS_ROUNDTRIP_OK"))
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun realClaudeCodeReturnsParallelWrapperToolCallsWhenEnabled() = runBlocking {
        if (!realClaudeCodeEnabled()) return@runBlocking

        val runtime = runtime(ProcessClaudeCodeCliExecutor(realClaudeExecutable()))
        val response = runtime.call(
            request(
                messages = listOf(
                    userMessage(
                        "Request two independent external actions in one batch: read_file for README.md and " +
                            "read_file for LICENSE. Do not answer directly."
                    )
                ),
                tools = listOf(readFileTool()),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    toolContext = testToolContext("real-claude-parallel-tool-call-test"),
                ),
            )
        )

        assertEquals(2, response.toolCalls.size)
        assertTrue(response.toolCalls.all { it.call.name == "read_file" })
        assertEquals(
            setOf("README.md", "LICENSE"),
            response.toolCalls.map {
                it.call.input.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            }.toSet(),
        )
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

    @Test
    fun fullResponseRejectsHiddenSimulatedExecution(): Unit = runBlocking {
        val valid = response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Done"),
        ))
        val hidden = "<tool_call name=\"read_file\">README.md</tool_call>\n" +
            "<tool_result>FABRICATED_FILE_CONTENT</tool_result>"
        val parser = ClaudeCodeResultStreamParser()
        parser.accept(nativeAssistantFrame("invented-execution", hidden))
        parser.accept(nativeAssistantFrame("answer", valid.result))
        val parsed = requireNotNull(parser.accept(JsonObject(mapOf(
            "type" to JsonPrimitive("result"), "subtype" to JsonPrimitive("success"),
            "result" to JsonPrimitive(valid.result), "session_id" to JsonPrimitive("session-1"),
        ))))
        // The final JSON is valid; the complete provider response is not.
        val executor = FakeClaudeCodeCliExecutor(parsed, parsed)
        val error = assertFailsWith<ClaudeCodeResponseFormatException> {
            runtime(executor).call(request(listOf(userMessage("Read README.md")), listOf(readFileTool())))
        }
        assertTrue(error.cleanRetryFailed)
        assertEquals(2, executor.commands.size)
        assertTrue(executor.commands.all { it.resumeSessionId == null })
        assertFalse(error.message.orEmpty().contains("FABRICATED_FILE_CONTENT"))
        assertTrue(error.responseFingerprint?.isNotBlank() == true)
    }

    @Test
    fun fullResponseCorrectionDoesNotRetainRejectedExecutionClaims() = runBlocking {
        var saved: ClaudeCodeSessionState? = null
        val sessions = object : ClaudeCodeSessionStateRepository {
            override suspend fun find(key: ClaudeCodeSessionState.Key) = saved
            override suspend fun save(state: ClaudeCodeSessionState) = state.also { saved = it }
            override suspend fun delete(key: ClaudeCodeSessionState.Key) { saved = null }
        }
        val valid = actionResponse("README.md")
        val rejectedText = "<tool_result>FABRICATED_FILE_CONTENT</tool_result>\n${valid.result}"
        val rejected = valid.copy(
            result = rejectedText,
            replayEvents = listOf(nativeAssistantFrame("rejected", rejectedText)),
        )
        val corrected = valid.copy(replayEvents = listOf(nativeAssistantFrame("corrected", valid.result)))
        val result = runtime(FakeClaudeCodeCliExecutor(rejected, corrected), sessions)
            .call(request(listOf(userMessage("Read README.md")), listOf(readFileTool())))
        assertEquals(1, result.toolCalls.size)
        assertFalse(
            saved?.replayState?.toString()?.contains("FABRICATED_FILE_CONTENT") == true,
            "A corrected JSON response must not make rejected execution claims part of trusted replay",
        )
    }

    @Test
    fun fullResponseAllowsQuotedToolSyntaxInsideAnswer() = runBlocking {
        val explanation = "Documentation example, not execution: `<tool_call name=\"read_file\">README.md</tool_call>` " +
            "and `<tool_result>example</tool_result>`. No file was read."
        val valid = response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive(explanation),
        ))
        val result = runtime(FakeClaudeCodeCliExecutor(valid.copy(
            replayEvents = listOf(nativeAssistantFrame("quoted-example", valid.result)),
        ))).call(request(listOf(userMessage("Explain the tool transcript format")), listOf(readFileTool())))
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(explanation, result.messages.single().text())
    }

    @Test
    fun fullResponseKeepsSplitTextAndSignedThinking() = runBlocking {
        val explanation = "Quoted example: <tool_result>example only</tool_result>"
        val valid = response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive(explanation),
        ))
        val split = valid.result.length / 2
        val signed = nativeAssistantContent("thinking", listOf(
            jsonObject("type" to JsonPrimitive("thinking"), "thinking" to JsonPrimitive("Quoted <tool_call> syntax is not execution"),
                "signature" to JsonPrimitive("signed-block")),
            jsonObject("type" to JsonPrimitive("redacted_thinking"), "data" to JsonPrimitive("opaque-signature")),
        ), messageId = "same-model-message")
        val frames = listOf(signed,
            nativeAssistantFrame("part-1", valid.result.take(split), "same-model-message"),
            nativeAssistantFrame("part-2", valid.result.drop(split), "same-model-message"))
        val parser = ClaudeCodeResultStreamParser()
        frames.forEach(parser::accept)
        val parsed = requireNotNull(parser.accept(jsonObject(
            "type" to JsonPrimitive("result"), "result" to JsonPrimitive(valid.result.drop(split)),
            "session_id" to JsonPrimitive("session-1"), "subtype" to JsonPrimitive("success"),
        )))
        val sessions = InMemoryClaudeCodeSessionStateRepository()
        val executor = FakeClaudeCodeCliExecutor(parsed)
        val result = runtime(executor, sessions).call(request(listOf(userMessage("Explain the syntax")), listOf(readFileTool())))
        assertEquals(explanation, result.messages.single().text())
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(1, executor.commands.size)
        val thinking = result.messages.single().content.filterIsInstance<Conversation.Message.ContentItem.Thinking>()
        assertEquals(listOf("signed-block", "opaque-signature"), thinking.map { it.signature })
        assertEquals(frames, ClaudeCodeReplayState.readMessages(requireNotNull(sessions.savedStates.last().replayState)))
    }

    @Test
    fun fullResponseRejectsUncorrelatedOutputAndUnexpectedNativeExecutionBlocks() = runBlocking {
        val valid = actionResponse("README.md")
        val frame = valid.replayEvents.single()
        val nativeCall = nativeAssistantContent("native-call", listOf(jsonObject(
            "type" to JsonPrimitive("tool_use"), "id" to JsonPrimitive("invented"),
            "name" to JsonPrimitive("Read"), "input" to jsonObject("path" to JsonPrimitive("private-path")),
        )))
        val nativeResult = jsonObject("type" to JsonPrimitive("user"), "uuid" to JsonPrimitive("native-result"),
            "message" to jsonObject("role" to JsonPrimitive("user"), "content" to JsonArray(listOf(jsonObject(
                "type" to JsonPrimitive("tool_result"), "tool_use_id" to JsonPrimitive("invented"),
                "content" to JsonPrimitive("private fabricated result"),
            )))))
        val variants = listOf(
            valid.copy(replayEvents = emptyList()),
            valid.copy(result = "different terminal projection"),
            valid.copy(replayEvents = listOf(nativeCall, frame)),
            valid.copy(replayEvents = listOf(nativeResult, frame)),
            valid.copy(replayEvents = listOf(JsonObject(frame + ("parent_tool_use_id" to JsonPrimitive("subagent"))))),
            valid.copy(replayEvents = listOf(frame, JsonObject(frame + ("message" to nativeCall.getValue("message"))))),
            valid.copy(replayEvents = listOf(frame, nativeAssistantFrame("another-frame", valid.result))),
        )
        for (invalid in variants) {
            val sessions = InMemoryClaudeCodeSessionStateRepository()
            val executor = FakeClaudeCodeCliExecutor(invalid, valid.copy(sessionId = "clean"))
            val result = runtime(executor, sessions).call(request(listOf(userMessage("Read README.md")), listOf(readFileTool())))
            assertEquals(2, executor.commands.size)
            assertNull(executor.commands.last().resumeSessionId)
            assertEquals(1, result.toolCalls.size)
            assertEquals(valid.replayEvents, ClaudeCodeReplayState.readMessages(requireNotNull(sessions.savedStates.single().replayState)))
        }
    }

    @Test
    fun fullResponseIgnoresOnlyExactDuplicateFrames() = runBlocking {
        val valid = actionResponse("README.md")
        val duplicate = valid.copy(replayEvents = valid.replayEvents + valid.replayEvents)
        val executor = FakeClaudeCodeCliExecutor(duplicate)
        val result = runtime(executor).call(request(listOf(userMessage("Read README.md")), listOf(readFileTool())))
        assertEquals(1, executor.commands.size)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun cleanRetryUsesOnlyAcceptedHistoryForResumeForkAndReset() = runBlocking {
        val boundary = Json.parseToJsonElement("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto"}}""").jsonObject
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        for (scenario in listOf("resume", "fork", "reset")) {
            val rejected = response(structuredOutput = answer, compactionBoundaries = listOf(boundary))
                .withNativeText("REJECTED_EXECUTION_CLAIM")
                .let { r -> r.copy(replayEvents = r.replayEvents.map {
                    Json.parseToJsonElement(it.toString().replace("AURORA_713", "REJECTED_CHECKPOINT")).jsonObject
                }) }
            val executor = FakeClaudeCodeCliExecutor(
                response(structuredOutput = answer, compactionBoundaries = listOf(boundary)),
                rejected, response(sessionId = "clean", structuredOutput = answer),
            )
            val sessions = InMemoryClaudeCodeSessionStateRepository()
            val runtime = runtime(executor, sessions)
            val original = userMessage("Old context already compacted")
            val first = runtime.call(request(listOf(original), listOf(readFileTool())))
            val accepted = first.messages.map { assistantMessage("").copy(content = it.content) }
            val input = if (scenario == "reset") listOf(userMessage("Reset request"))
                else listOf(original) + accepted + userMessage("Next request")
            val context = testToolContext() + if (scenario == "fork") mapOf("threadId" to "forked-thread") else emptyMap()
            val result = runtime.call(request(input, listOf(readFileTool()), AiRuntimeOptions(
                assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT, toolContext = context,
            )))
            assertEquals(3, executor.commands.size)
            assertEquals(if (scenario == "resume") "session-1" else null, executor.commands[1].resumeSessionId)
            val retry = executor.commands.last()
            assertNull(retry.resumeSessionId)
            assertEquals(scenario != "reset", retry.userPrompt.contains("AURORA_713"))
            assertFalse(retry.userPrompt.contains("Old context already compacted"))
            assertFalse(retry.userPrompt.contains("REJECTED_"))
            assertFalse(result.messages.flatMap { it.content }.any { it is Conversation.Message.ContentItem.ContextCompactionResult })
            assertEquals("clean", sessions.savedStates.last().claudeSessionId)
            assertFalse(sessions.savedStates.last().replayState.toString().contains("REJECTED_"))
        }
    }

    @Test
    fun cleanRetryDoesNotCommitACompactionFromTheRejectedAttempt() = runBlocking {
        val boundary = Json.parseToJsonElement("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual"}}""").jsonObject
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        val executor = FakeClaudeCodeCliExecutor(
            response(structuredOutput = answer),
            response(structuredOutput = answer, compactionBoundaries = listOf(boundary)),
            response(structuredOutput = answer).withNativeText("REJECTED_EXECUTION_CLAIM"),
            response(sessionId = "clean", structuredOutput = answer),
        )
        val runtime = runtime(executor)
        val original = userMessage("Canonical original request")
        val first = runtime.call(request(listOf(original), listOf(readFileTool())))
        val input = listOf(original, assistantMessage("").copy(content = first.messages.single().content), userMessage("Next request"))
        val result = runtime.call(request(input, listOf(readFileTool()), AiRuntimeOptions(
            autoCompactionThresholdTokens = 10, toolContext = testToolContext(),
        )))
        assertEquals(4, executor.commands.size)
        assertEquals("/compact", executor.commands[1].userPrompt)
        assertNull(executor.commands.last().resumeSessionId)
        assertTrue(executor.commands.last().userPrompt.contains("Canonical original request"))
        assertFalse(executor.commands.last().userPrompt.contains("AURORA_713"))
        assertEquals(1, result.messages.size)
        assertEquals(30, result.usage?.promptTokens) // compact + rejected response + clean retry
        assertEquals(10, result.contextUsage?.inputTokens)
    }

    @Test
    fun cleanRetryReplacesTheFailedProcessInsteadOfCorrectingItInPlace() = runBlocking {
        val valid = actionResponse("README.md")
        val startCommands = mutableListOf<ClaudeCodeCommand>()
        val executed = mutableListOf<Int>()
        val closed = mutableSetOf<Int>()
        val factory = object : ClaudeCodeCliProcessFactory {
            override suspend fun start(command: ClaudeCodeCommand): ClaudeCodeCliProcess {
                val index = startCommands.size
                startCommands += command
                return object : ClaudeCodeCliProcess {
                    override var sessionId: String? = null
                    override var isAlive: Boolean = true
                    override suspend fun execute(userPrompt: String): ClaudeCodeCliResponse {
                        check(index !in executed) { "A rejected process must never receive an in-session correction" }
                        executed += index
                        sessionId = "native-$index"
                        val result = if (index == 0) valid.withNativeText("REJECTED_EXECUTION_CLAIM") else valid
                        return result.copy(sessionId = sessionId)
                    }
                    override suspend fun close() { isAlive = false; closed += index }
                }
            }
        }
        val executor = ProcessClaudeCodeCliExecutor(processFactory = factory)
        try {
            val result = runtime(executor).call(request(listOf(userMessage("Read README.md")), listOf(readFileTool())))
            assertEquals(1, result.toolCalls.size)
            assertEquals(2, startCommands.size)
            assertTrue(startCommands.all { it.resumeSessionId == null })
        } finally { executor.shutdown() }
        assertEquals(setOf(0, 1), closed)
        assertEquals(listOf(0, 1), executed)
    }

    @Test
    fun missingSessionRecoveryDoesNotCreateAdditionalResponseRetries() = runBlocking {
        val valid = actionResponse("README.md")
        val commands = mutableListOf<ClaudeCodeCommand>()
        val executor = object : ClaudeCodeCliExecutor {
            override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
                commands += command
                return when (commands.size) {
                    1 -> valid
                    2 -> throw ClaudeCodeSessionUnavailableException("Native session is missing")
                    else -> valid.withNativeText("REJECTED_EXECUTION_CLAIM")
                }
            }
        }
        val sessions = InMemoryClaudeCodeSessionStateRepository()
        val runtime = runtime(executor, sessions)
        val user = userMessage("Original request")
        val first = runtime.call(request(listOf(user), listOf(readFileTool())))
        val error = assertFailsWith<ClaudeCodeResponseFormatException> {
            runtime.call(request(listOf(user, assistantMessage("").copy(content = first.messages.single().content),
                userMessage("Next request")), listOf(readFileTool())))
        }
        assertTrue(error.cleanRetryFailed)
        assertEquals(4, commands.size) // first accepted, missing session, rejected reconstruction, rejected clean retry
        assertEquals("session-1", commands[1].resumeSessionId)
        assertTrue(commands.drop(2).all { it.resumeSessionId == null })
        assertEquals(0, sessions.activeSessionCount)
    }

    private fun nativeAssistantFrame(id: String, text: String, messageId: String = "message-$id"): JsonObject =
        nativeAssistantContent(id, listOf(jsonObject("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))), messageId)

    private fun nativeAssistantContent(id: String, content: List<JsonObject>, messageId: String = "message-$id"): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("assistant"), "uuid" to JsonPrimitive(id),
        "message" to JsonObject(mapOf(
            "id" to JsonPrimitive(messageId), "role" to JsonPrimitive("assistant"), "content" to JsonArray(content),
        )),
    ))

    @Test
    fun retriesInvalidResponseOnceFromCanonicalHistoryBeforeReturningValidatedActions() = runBlocking {
        val valid = actionResponse("README.md")
        for (invalidText in listOf(
            "```json\n${valid.result}\n```",
            """{"response":{"kind":"tool_calls","content":[{"kind":"message","message":"Working."}]}}""",
            valid.result.replace("\"README.md\"", "42"),
        )) {
            val executor = FakeClaudeCodeCliExecutor(
                valid.withNativeText(invalidText), valid.copy(sessionId = "clean-session"),
                response(sessionId = "clean-session", structuredOutput = jsonObject(
                    "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Done"))),
            )
            val runtime = runtime(executor)
            val user = userMessage("Read README.md without executing anything twice")
            val result = runtime.call(request(listOf(user), listOf(readFileTool())))
            assertEquals(1, result.toolCalls.size)
            assertEquals("README.md", result.toolCalls.single().call.input.jsonObject.getValue("path").jsonPrimitive.content)
            assertEquals(2, executor.commands.size)
            assertEquals(20, result.usage?.promptTokens)
            assertEquals(10, result.usage?.completionTokens)
            assertEquals(true, result.providerMetadata["claudeCodeCleanRetry"])
            val retry = executor.commands[1]
            assertNull(retry.resumeSessionId)
            assertEquals(executor.commands.first().systemPrompt, retry.systemPrompt)
            assertTrue(retry.userPrompt.contains("Read README.md without executing anything twice"))
            assertTrue(retry.userPrompt.contains("single automatic retry"))
            assertFalse(retry.userPrompt.contains(invalidText))
            val assistant = assistantMessage("").copy(content = result.messages.single().content)
            val toolResult = userMessage("").copy(content = listOf(Conversation.Message.ContentItem.ToolResult(
                toolUseId = result.toolCalls.single().id, toolName = "read_file",
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Contents")),
            )))
            val final = runtime.call(request(listOf(user, assistant, toolResult), listOf(readFileTool())))
            assertEquals("Done", final.messages.single().text())
            assertEquals("clean-session", executor.commands.last().resumeSessionId)
            assertFalse(executor.commands.last().userPrompt.contains("single automatic retry"))
        }
    }

    @Test
    fun rejectsEntireActionBatchWhenOneActionHasInvalidArguments() = runBlocking {
        val valid = actionResponse("README.md")
        val invalid = valid.withNativeText("""{"response":{"kind":"tool_calls","content":[
            {"kind":"tool_call","action_name":"read_file","arguments":{"path":"README.md"}},
            {"kind":"tool_call","action_name":"read_file","arguments":{"missing":"LICENSE"}}
        ]}}""")
        val executor = FakeClaudeCodeCliExecutor(invalid, invalid, valid)
        val runtime = runtime(executor)
        assertFailsWith<ClaudeCodeResponseFormatException> {
            runtime.call(request(listOf(userMessage("Read two files")), listOf(readFileTool())))
        }
        assertEquals(2, executor.commands.size)
        assertTrue(executor.commands[1].userPrompt.contains("single automatic retry"))
        runtime.call(request(listOf(userMessage("Try a new turn")), listOf(readFileTool())))
        assertNull(executor.commands.last().resumeSessionId)
    }

    @Test
    fun correctsRequiredToolChoiceAndUnknownActionNames() = runBlocking {
        val valid = actionResponse("README.md")
        for (invalid in listOf(
            response(structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Premature"))),
            valid.withNativeText(valid.result.replace("read_file", "unknown_action")),
        )) {
            val executor = FakeClaudeCodeCliExecutor(invalid, valid)
            val result = runtime(executor).call(request(listOf(userMessage("Read a file")), listOf(readFileTool()),
                AiRuntimeOptions(toolChoice = AiToolChoice.RequiredTool("read_file"), toolContext = testToolContext())))
            assertEquals("read_file", result.toolCalls.single().call.name)
            assertEquals(2, executor.commands.size)
            assertNull(executor.commands[1].resumeSessionId)
        }
    }

    @Test
    fun validatesJsonSchemaWithoutToolsAndDoesNotUseCliSchemaFlag() = runBlocking {
        val valid = actionResponse("unused").withNativeText("""{"answer":"Привет"}""")
        val executor = FakeClaudeCodeCliExecutor(valid.withNativeText("""{"answer":2}"""), valid)
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""").jsonObject
        val result = runtime(executor).call(request(listOf(userMessage("Say hello")), emptyList(), AiRuntimeOptions(
            responseFormat = AiResponseFormat.JsonSchema("answer", schema), toolContext = testToolContext())))
        assertEquals("""{"answer":"Привет"}""", result.messages.single().text())
        assertFalse(executor.commands.first().userPrompt.contains("tool_calls"))
        assertTrue(executor.commands.first().systemPrompt.contains(schema.toString()))
        val processExecutor = ProcessClaudeCodeCliExecutor()
        try {
            val args = processExecutor.buildArgs(executor.commands.first(), "/tmp/probe-system.md")
            assertFalse("--json-schema" in args)
            assertTrue(args.windowed(2).contains(listOf("--output-format", "stream-json")))
        } finally {
            processExecutor.shutdown()
        }
    }

    @Test
    fun doesNotRetryCancellationOrTransportFailures() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), java.io.IOException("transport failed"))) {
            var attempts = 0
            val executor = object : ClaudeCodeCliExecutor {
                override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
                    attempts++
                    throw failure
                }
            }
            val thrown = assertFailsWith<Exception> {
                runtime(executor).call(request(listOf(userMessage("Read")), listOf(readFileTool())))
            }
            assertTrue(thrown === failure)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun correctionRemainsInsideCallerTimeout() = runBlocking {
        var attempts = 0
        val executor = object : ClaudeCodeCliExecutor {
            override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
                attempts++
                if (attempts == 1) return actionResponse("README.md").withNativeText("not json")
                awaitCancellation()
            }
        }
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(200) { runtime(executor).call(request(listOf(userMessage("Read")), listOf(readFileTool()))) }
        }
        assertEquals(2, attempts)
    }

    private fun actionResponse(path: String) = response(structuredOutput = Json.parseToJsonElement(
        """{"kind":"tool_calls","content":[{"kind":"tool_call","action_name":"read_file","arguments":{"path":"$path"}}]}"""))

    @Test
    fun requestsDependentActionOnlyAfterPreviousResult() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(actionResponse("index.txt"), actionResponse("chapter.txt"),
            response(structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("Chapter"))))
        val runtime = runtime(executor)
        val messages = mutableListOf(userMessage("Read the index, then read the file it names"))
        for ((path, contents) in listOf("index.txt" to "chapter.txt", "chapter.txt" to "Chapter")) {
            val result = runtime.call(request(messages.toList(), listOf(readFileTool())))
            val action = result.toolCalls.single()
            assertEquals(path, action.call.input.jsonObject.getValue("path").jsonPrimitive.content)
            messages += assistantMessage("").copy(content = result.messages.single().content)
            messages += userMessage("").copy(content = listOf(Conversation.Message.ContentItem.ToolResult(
                toolUseId = action.id, toolName = action.call.name,
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(contents)),
            )))
        }
        val result = runtime.call(request(messages.toList(), listOf(readFileTool())))
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("Chapter", result.messages.single().text())
        assertTrue(executor.commands[1].userPrompt.contains("chapter.txt"))
        assertFalse(executor.commands[1].userPrompt.contains("Read the index"))
        assertTrue(executor.commands.drop(1).all { it.resumeSessionId == "session-1" })
    }

    @Test
    fun configuredThresholdCompactsBetweenRequestsAndRecordsPolicyOrigin() = runBlocking {
        val boundary = Json.parseToJsonElement("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual"}}""").jsonObject
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        val executor = FakeClaudeCodeCliExecutor(
            response(structuredOutput = answer),
            response(structuredOutput = answer, compactionBoundaries = listOf(boundary)),
            response(structuredOutput = answer),
        )
        val runtime = runtime(executor)
        val firstUser = userMessage("Original")
        val first = runtime.call(request(listOf(firstUser), emptyList()))
        val history = listOf(firstUser, assistantMessage("").copy(content = first.messages.single().content), userMessage("Next"))
        val result = runtime.call(request(history, emptyList(), AiRuntimeOptions(
            autoCompactionThresholdTokens = 10, toolContext = testToolContext(),
        )))
        assertEquals("/compact", executor.commands[1].userPrompt)
        assertTrue(executor.commands[2].userPrompt.contains("Next"))
        val checkpoint = result.messages.first().content.single() as Conversation.Message.ContentItem.ContextCompactionResult
        assertEquals(Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY, checkpoint.origin)
        assertEquals(history.map { it.id }, checkpoint.sourceMessageIds)
        assertTrue(runtime.capabilities.supportsAutoCompaction)
    }

    @Test
    fun doesNotCompactBelowConfiguredThreshold() = runBlocking {
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = answer), response(structuredOutput = answer))
        val runtime = runtime(executor)
        val firstUser = userMessage("Original")
        val first = runtime.call(request(listOf(firstUser), emptyList()))
        runtime.call(request(
            listOf(firstUser, assistantMessage("").copy(content = first.messages.single().content), userMessage("Next")),
            emptyList(), AiRuntimeOptions(autoCompactionThresholdTokens = 11, toolContext = testToolContext()),
        ))
        assertEquals(2, executor.commands.size)
        assertFalse(executor.commands.any { it.userPrompt == "/compact" })
    }

    @Test
    fun freshSessionKeepsUnrelatedHistoryBeforePartialReadableCompaction() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))))
        val summary = assistantMessage("").copy(content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Selected middle messages"),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
            coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
        )))
        runtime(executor).call(request(listOf(userMessage("Unrelated earlier instruction"), summary, userMessage("Next")), emptyList()))
        assertTrue(executor.commands.single().userPrompt.contains("Unrelated earlier instruction"))
        assertTrue(executor.commands.single().userPrompt.contains("Selected middle messages"))
    }

    @Test
    fun recoveredNativeCheckpointReattachesImagesWithoutReplayingThinking() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))))
        val state = Json.parseToJsonElement("""{"kind":"claude_code_transcript","messages":[{"uuid":"retained","type":"user","message":{"role":"user","content":[{"type":"text","text":"Retained visual context"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}},{"type":"thinking","thinking":"Do not flatten signed reasoning"}]}}]}""").jsonObject
        val checkpoint = assistantMessage("").copy(content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(state),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
            coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(provider = "CLAUDE_CODE"),
        )))
        runtime(executor).call(request(listOf(userMessage("Old context already compacted"), checkpoint, userMessage("Next")), emptyList()))
        val command = executor.commands.single()
        assertFalse(command.userPrompt.contains("Old context already compacted"))
        assertFalse(command.userPrompt.contains("Do not flatten signed reasoning"))
        assertTrue(command.userPrompt.contains("Retained visual context"))
        assertEquals("aGVsbG8=", command.userContentBlocks.single().getValue("source").jsonObject.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun doesNotResumeWhenTheMirroredTranscriptIsUnavailable() = runBlocking {
        var saved: ClaudeCodeSessionState? = null
        val sessions = object : ClaudeCodeSessionStateRepository {
            override suspend fun find(key: ClaudeCodeSessionState.Key) = saved?.copy(replayState = null)
            override suspend fun save(state: ClaudeCodeSessionState) = state.also { saved = it }
            override suspend fun delete(key: ClaudeCodeSessionState.Key) { saved = null }
        }
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = answer), response(structuredOutput = answer))
        val runtime = runtime(executor, sessions)
        val original = userMessage("Recover this original context")
        val first = runtime.call(request(listOf(original), emptyList()))
        runtime.call(request(listOf(original, assistantMessage("").copy(content = first.messages.single().content), userMessage("Next")), emptyList()))
        assertNull(executor.commands[1].resumeSessionId)
        assertTrue(executor.commands[1].userPrompt.contains("Recover this original context"))
    }

    @Test
    fun messageSquashDoesNotResumeOrOverwriteTheMainSession() = runBlocking {
        val answer = jsonObject("kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))
        val executor = FakeClaudeCodeCliExecutor(
            response(sessionId = "main", structuredOutput = answer),
            response(sessionId = "helper", structuredOutput = answer),
            response(sessionId = "main", structuredOutput = answer),
        )
        val runtime = runtime(executor)
        val original = userMessage("Original main context")
        val first = runtime.call(request(listOf(original), emptyList()))
        runtime.call(request(listOf(userMessage("Summarize selected material")), emptyList(), AiRuntimeOptions(
            usagePurpose = "MESSAGE_SQUASH", toolContext = testToolContext(),
        )))
        runtime.call(request(listOf(original, assistantMessage("").copy(content = first.messages.single().content),
            userMessage("Continue main")), emptyList()))
        assertNull(executor.commands[1].resumeSessionId)
        assertFalse(executor.commands[1].userPrompt.contains("Original main context"))
        assertEquals("main", executor.commands[2].resumeSessionId)
        assertFalse(executor.commands[2].userPrompt.contains("Summarize selected material"))
    }

    @Test
    fun selectiveCompactionOmitsCoveredSourcesButKeepsUnrelatedEarlierContext() = runBlocking {
        val executor = FakeClaudeCodeCliExecutor(response(structuredOutput = jsonObject(
            "kind" to JsonPrimitive("final_answer"), "final_answer" to JsonPrimitive("OK"))))
        val earlier = userMessage("Unrelated earlier instruction")
        val source = userMessage("Source no longer sent")
        val summary = assistantMessage("").copy(content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Selected summary"),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
            coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
            sourceMessageIds = listOf(source.id),
        )))
        runtime(executor).call(request(listOf(earlier, source, summary, userMessage("Next")), emptyList()))
        val prompt = executor.commands.single().userPrompt
        assertTrue(prompt.contains("Unrelated earlier instruction"))
        assertTrue(prompt.contains("Selected summary"))
        assertFalse(prompt.contains("Source no longer sent"))
    }

    private fun runtime(
        executor: ClaudeCodeCliExecutor,
        sessions: ClaudeCodeSessionStateRepository = InMemoryClaudeCodeSessionStateRepository(),
        modelName: String = realClaudeModel(),
    ): ClaudeCodeCliRuntime =
        ClaudeCodeCliRuntime(
            executor = executor,
            connectionId = "claude-code",
            modelConfigurationId = "claude-code-haiku",
            modelName = modelName,
            workspaceDirectory = null,
            sessionStateRepository = sessions,
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
        compactionBoundaries: List<JsonObject> = emptyList(),
    ): ClaudeCodeCliResponse {
        val envelope = jsonObject("response" to structuredOutput)
        return ClaudeCodeCliResponse(
            result = envelope.toString(),
            structuredOutput = null,
            sessionId = sessionId,
            usage = jsonObject(
                "input_tokens" to JsonPrimitive(10),
                "output_tokens" to JsonPrimitive(5),
                "cache_creation_input_tokens" to JsonPrimitive(0),
                "cache_read_input_tokens" to JsonPrimitive(0),
            ),
            contextUsage = jsonObject("input_tokens" to JsonPrimitive(10)),
            finishReason = "success",
            raw = jsonObject("type" to JsonPrimitive("result")),
            thinking = thinking,
            compactionBoundaries = compactionBoundaries,
            replayEvents = compactionBoundaries.flatMap { boundary -> listOf(
                boundary,
                Json.parseToJsonElement("""{"type":"user","uuid":"summary","isSynthetic":true,"message":{"role":"user","content":[{"type":"text","text":"Recovery summary: keep AURORA_713."}]}}""").jsonObject,
            ) } + nativeAssistantFrame("response-${messageCounter++}", envelope.toString()),
        )
    }

    private fun ClaudeCodeCliResponse.withNativeText(text: String): ClaudeCodeCliResponse = copy(
        result = text,
        replayEvents = replayEvents.filterNot { it["type"] == JsonPrimitive("assistant") } +
            nativeAssistantFrame("response-${messageCounter++}", text),
    )

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
        val savedStates = mutableListOf<ClaudeCodeSessionState>()
        val activeSessionCount: Int get() = states.size

        override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? =
            states[key]

        override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState {
            states[state.key] = state
            savedStates += state
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
