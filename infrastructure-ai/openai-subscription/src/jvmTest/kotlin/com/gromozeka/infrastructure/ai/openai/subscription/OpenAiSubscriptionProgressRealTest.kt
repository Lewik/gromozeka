package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.recordRetainedOpenAiReplay
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class OpenAiSubscriptionProgressRealTest {
    @Test
    fun detachedCompactionPreservesForegroundReasoningAcrossCommittedCheckpoint() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_REAL") != "true") return@runBlocking
        val session = authenticatedSession()
        val requestedModel = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL"))
        val profile = OpenAiSubscriptionModelsClient(baseUrl, clientVersion, 60_000, 30_000)
            .getProfile(session, requestedModel)
        assertTrue(profile.useResponsesLite)

        val requestMapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        fun client() = OpenAiSubscriptionResponsesClient(
            responseMapper, requestMapper, baseUrl, clientVersion,
            websocketIdleMs = 30_000, websocketResponseTimeoutMs = 180_000,
            websocketTransportTimeoutMs = 30_000, httpResponseTimeoutMs = 180_000,
            admission = TestAiAdmission(),
        )
        val sharedClient = client()
        val compactor = OpenAiSubscriptionCompaction(sharedClient, requestMapper, responseMapper)
        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        val sourceThread = Conversation.Thread.Id(UUID.randomUUID().toString())
        val targetThread = Conversation.Thread.Id(UUID.randomUUID().toString())
        val prefixMarker = "DETACHED_PREFIX_${UUID.randomUUID()}"
        val tailMarker = "DETACHED_TAIL_${UUID.randomUUID()}"
        val configuration = testModelConfiguration(connectionId, profile.slug, "live-compaction-${profile.slug}")
        val options = AiRuntimeOptions(
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX),
            toolContext = mapOf(
                "conversationId" to conversationId.value,
                "promptCacheKey" to conversationId.value,
                "threadId" to sourceThread.value,
            ),
        )
        val systemPrompts = listOf(
            "This is a synthetic protocol test. Preserve exact markers from tool results and user messages. " +
                "Never call a tool when the user says not to.",
        )
        fun request(messages: List<Conversation.Message>, requestOptions: AiRuntimeOptions = options) = AiRuntimeRequest(
            systemPrompts = systemPrompts,
            messages = messages,
            tools = listOf(readFileTool),
            options = requestOptions,
        )
        fun persisted(response: AiRuntimeResponse, thread: Conversation.Thread.Id): List<Conversation.Message> =
            response.messages.map { assistant ->
                message(conversationId, Conversation.Message.Role.ASSISTANT, assistant.content).copy(
                    providerMetadata = JsonObject(
                        (response.providerMetadata + assistant.metadata + (AI_REPLAY_THREAD_METADATA_KEY to thread.value))
                            .mapValues { (_, value) ->
                                value as? JsonElement ?: when (value) {
                                    is Number -> JsonPrimitive(value)
                                    is Boolean -> JsonPrimitive(value)
                                    else -> JsonPrimitive(value.toString())
                                }
                            },
                    ),
                )
            }

        val callId = Conversation.Message.ContentItem.ToolCall.Id("detached-read-file-call")
        val prefix = listOf(
            message(conversationId, Conversation.Message.Role.USER, listOf(
                Conversation.Message.ContentItem.UserMessage(
                    "Read the supplied file result and preserve its exact project marker. Do not call more tools.",
                ),
            )),
            message(conversationId, Conversation.Message.Role.ASSISTANT, listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = callId,
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        "read_file",
                        Json.parseToJsonElement("""{"path":"README.md"}"""),
                    ),
                ),
            )),
            message(conversationId, Conversation.Message.Role.USER, listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = callId,
                    toolName = "read_file",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(
                        (1..1000).joinToString("\n") { "Build record $it: synthetic compilation completed successfully." } +
                            "\nThe exact project marker is $prefixMarker.",
                    )),
                ),
            )),
        )
        val compactionId = UUID.randomUUID().toString()
        val compactionRequest = request(
            prefix,
            options.copy(
                usagePurpose = "CONTEXT_COMPACTION",
                toolContext = options.toolContext + ("compactionTransportId" to compactionId),
            ),
        )
        val promptCacheKey = conversationId.value.toOpenAiSubscriptionKey()
        val compactionBody = requestMapper.toRequest(
            compactionRequest, profile, promptCacheKey, connectionId = connectionId,
        )
        val compactionTransportKey = compactionRequest.subscriptionTransportNamespace("unused").toOpenAiSubscriptionKey()
        val preparedCheckpoint = async {
            compactor.execute(
                compactionRequest, compactionBody, profile, session,
                compactionTransportKey, connectionId, configuration,
            )
        }

        val tailUser = message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.UserMessage(
                "The exact tail marker is $tailMarker. Calculate 137 * 149, then report the exact project marker, " +
                    "the exact tail marker, and the product. Do not call tools.",
            ),
        ))
        val foregroundRequest = request(prefix + tailUser)
        val foregroundBody = requestMapper.toRequest(
            foregroundRequest, profile, promptCacheKey, connectionId = connectionId,
        )
        val foregroundParsed = sharedClient.create(
            configuration, session,
            foregroundRequest.subscriptionTransportNamespace("unused").toOpenAiSubscriptionKey(),
            foregroundBody, profile, options.assistantResponseFormat,
        )
        assertEquals("completed", assertNotNull(foregroundParsed.completed).status)
        val foregroundReasoning = foregroundParsed.outputItems.filter {
            it["type"] == JsonPrimitive("reasoning") &&
                !it["encrypted_content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        }
        assertTrue(
            foregroundReasoning.isNotEmpty(),
            "Foreground response supplied no replayable reasoning: " + foregroundParsed.outputItems.map {
                "${it["type"]?.jsonPrimitive?.contentOrNull}:${it.keys.sorted()}"
            },
        )
        val foregroundResponse = responseMapper.toRuntimeResponse(
            foregroundParsed.outputItems, foregroundParsed.completed, conversationId.value,
            connectionId, configuration.id.value, profile.slug, options.assistantResponseFormat,
        )
        assertEquals(AiStepOutcome.COMPLETE, foregroundResponse.outcome)
        assertTrue(foregroundResponse.toolCalls.isEmpty())
        val tail = listOf(tailUser) + persisted(
            compactor.recordContextUsage(
                foregroundBody, foregroundParsed, foregroundResponse,
                options.assistantResponseFormat, profile,
            ),
            sourceThread,
        )

        val prepared = persisted(preparedCheckpoint.await(), sourceThread).single()
        val checkpointItem = prepared.content.single() as Conversation.Message.ContentItem.ContextCompactionResult
        val committedCheckpoint = prepared.copy(content = listOf(checkpointItem.copy(
            historyTransition = Conversation.Message.ContentItem.ContextCompactionResult.HistoryTransition(
                sourceThread, targetThread,
            ),
        ))).recordRetainedOpenAiReplay(previous = null, tail.map(CompactionMessageHeader::from))

        val finalUser = message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.UserMessage(
                "Repeat the exact project marker, the exact tail marker, and the product from the previous turn. " +
                    "Do not call tools.",
            ),
        ))
        val replayRequest = request(
            listOf(committedCheckpoint) + tail + finalUser,
            options.copy(toolContext = options.toolContext + ("threadId" to targetThread.value)),
        )
        val replayBody = requestMapper.toRequest(
            replayRequest, profile, promptCacheKey, connectionId = connectionId,
        )
        foregroundReasoning.forEach { reasoning ->
            assertTrue(reasoning in replayBody.input, "Committed COW replay omitted foreground reasoning")
        }
        val replayParsed = client().create(
            configuration, session,
            replayRequest.subscriptionTransportNamespace("unused").toOpenAiSubscriptionKey(),
            replayBody, profile, options.assistantResponseFormat,
        )
        val replayCompleted = assertNotNull(replayParsed.completed)
        assertEquals("completed", replayCompleted.status)
        assertNull(
            replayCompleted.inputTransformations.removedReasoningLogSummary(),
            "Provider removed retained foreground reasoning",
        )
        val replayResponse = responseMapper.toRuntimeResponse(
            replayParsed.outputItems, replayParsed.completed, conversationId.value,
            connectionId, configuration.id.value, profile.slug, options.assistantResponseFormat,
        )
        assertEquals(AiStepOutcome.COMPLETE, replayResponse.outcome)
        assertTrue(replayResponse.toolCalls.isEmpty())
        val finalText = replayResponse.messages.flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
        assertTrue(prefixMarker in finalText, "Committed checkpoint lost the prefix marker")
        assertTrue(tailMarker in finalText, "Committed checkpoint lost the foreground tail marker")
        assertTrue("20413" in finalText, "Committed replay lost the foreground result")
        println(
            "Live detached compaction replay: model=${profile.slug} " +
                "foregroundReasoning=${foregroundReasoning.size} " +
                "inputTransformations=${replayParsed.completed?.inputTransformations.orEmpty().size}",
        )
    }

    @Test
    fun compactionRetainsToolKnowledgeAcrossPersistedHistoryAndFreshConnections() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_REAL") != "true") return@runBlocking
        val session = authenticatedSession()
        val model = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL"))
        val profile = OpenAiSubscriptionModelsClient(baseUrl, clientVersion, 60_000, 30_000).getProfile(session, model)
        assertTrue(profile.useResponsesLite)
        val requestMapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        fun client() = OpenAiSubscriptionResponsesClient(responseMapper, requestMapper, baseUrl, clientVersion,
            websocketIdleMs = 30_000, websocketResponseTimeoutMs = 180_000,
            websocketTransportTimeoutMs = 30_000, httpResponseTimeoutMs = 180_000, admission = TestAiAdmission())
        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        val fileMarker = "COMPACT_${UUID.randomUUID()}"
        val callId = Conversation.Message.ContentItem.ToolCall.Id("read-file-call")
        val history = mutableListOf(
            message(conversationId, Conversation.Message.Role.USER, listOf(
                Conversation.Message.ContentItem.UserMessage("Report the exact project marker from the supplied file result. Do not call more tools.")
            )),
            message(conversationId, Conversation.Message.Role.ASSISTANT, listOf(
                Conversation.Message.ContentItem.ToolCall(id = callId,
                    call = Conversation.Message.ContentItem.ToolCall.Data("read_file",
                        Json.parseToJsonElement("""{"path":"README.md"}""")))
            )),
            message(conversationId, Conversation.Message.Role.USER, listOf(
                Conversation.Message.ContentItem.ToolResult(toolUseId = callId, toolName = "read_file",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(
                        (1..1000).joinToString("\n") { "Build record $it: synthetic compilation completed successfully." } +
                            "\nThe exact project marker is $fileMarker."
                    )))
            )),
        )
        val options = AiRuntimeOptions(reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW),
            )
        fun runtimeRequest() = AiRuntimeRequest(
            listOf("This is a synthetic protocol test. Preserve the project marker from tool results and report it exactly."),
            history.toList(), listOf(readFileTool), options,
        )
        fun persist(response: AiRuntimeResponse) {
            response.messages.forEach { assistant ->
                val saved = message(conversationId, Conversation.Message.Role.ASSISTANT, assistant.content).copy(
                    providerMetadata = JsonObject((response.providerMetadata + assistant.metadata).mapValues { (_, value) ->
                        value as? JsonElement ?: when (value) {
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        }
                    }),
                )
                history += Json.decodeFromString(Conversation.Message.serializer(),
                    Json.encodeToString(Conversation.Message.serializer(), saved))
            }
        }
        val initial = runtimeRequest()
        val mapped = requestMapper.toRequest(initial, profile, conversationId.value, connectionId = connectionId)
        val compactor = OpenAiSubscriptionCompaction(client(), requestMapper, responseMapper)
        val compacted = assertNotNull(compactor.execute(initial, mapped, profile, session,
            conversationId.value, connectionId, testModelConfiguration(connectionId, model, "live-compaction-$model")))
        assertEquals(AiStepOutcome.CONTINUE, compacted.outcome)
        assertTrue(compacted.toolCalls.isEmpty())
        assertNull(compacted.contextUsage)
        persist(compacted)
        repeat(2) { iteration ->
            val freshClient = client()
            val next = runtimeRequest()
            val replay = requestMapper.toRequest(next, profile, conversationId.value, connectionId = connectionId)
            assertTrue(replay.input.none { it.toString().contains("Build record 1000:") })
            assertTrue(compactor.estimateRequestTokens(replay) < 4_000, "Compacted replay should be smaller; this is an estimate")
            val parsed = freshClient.create(testModelConfiguration(connectionId, model, "live-compaction-$model"), session, conversationId.value, replay, profile, options.assistantResponseFormat)
            val response = responseMapper.toRuntimeResponse(parsed.outputItems, parsed.completed, conversationId.value,
                connectionId, "live-compaction-$model", model, options.assistantResponseFormat)
            assertEquals(AiStepOutcome.COMPLETE, response.outcome)
            assertTrue(response.toolCalls.isEmpty())
            assertTrue(response.messages.flatMap { it.content }
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .any { fileMarker in it.structured.fullText }, "Compaction lost the tool result marker")
            println("Live compaction replay: model=$model iteration=$iteration inputTokens=${response.contextUsage?.inputTokens}")
            persist(compactor.recordContextUsage(replay, parsed, response, options.assistantResponseFormat, profile))
            history += message(conversationId, Conversation.Message.Role.USER, listOf(
                Conversation.Message.ContentItem.UserMessage("Repeat the same project marker. Do not use tools.")
            ))
        }
    }

    @Test
    fun remarksToolResultsAndFinalAnswersSurviveIncrementalAndFreshConnections() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_REAL") != "true") return@runBlocking
        val session = authenticatedSession()
        val model = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL"))
        val profile = OpenAiSubscriptionModelsClient(baseUrl, clientVersion, 60_000, 30_000)
            .getProfile(session, model)
        assertTrue("low" in profile.supportedReasoningEfforts)
        println("Live progress check: model=$model effort=low responsesLite=${profile.useResponsesLite}")

        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        val requestMapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        fun client() = OpenAiSubscriptionResponsesClient(
            responseMapper, requestMapper, baseUrl, clientVersion,
            websocketIdleMs = 30_000,
            websocketResponseTimeoutMs = 120_000,
            websocketTransportTimeoutMs = 30_000,
            httpResponseTimeoutMs = 120_000,
            admission = TestAiAdmission(),
        )
        val history = mutableListOf(message(
            conversationId,
            Conversation.Message.Role.USER,
            listOf(Conversation.Message.ContentItem.UserMessage(
                "First send a brief Russian commentary sentence saying you will read the file. " +
                    "Then call read_file for README.md. Do not invent the file contents. " +
                    "After the result arrives, report its marker in your final answer."
            )),
        ))
        val options = AiRuntimeOptions(
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            responseFormat = AiResponseFormat.JsonSchema(
                name = "gromozeka_assistant_response",
                schema = Json.parseToJsonElement("""{
                    "type":"object","additionalProperties":false,
                    "properties":{
                        "fullText":{"type":"string"},"ttsText":{"type":"string"},
                        "voiceTone":{"type":"string"},"attentionRequested":{"type":"boolean"}
                    },"required":["fullText","ttsText","voiceTone","attentionRequested"]
                }""").jsonObject,
            ),
        )
        suspend fun step(client: OpenAiSubscriptionResponsesClient): AiRuntimeResponse {
            val request = requestMapper.toRequest(
                AiRuntimeRequest(
                    systemPrompts = listOf(
                        "You are testing a chat protocol. Send a short plain-text commentary before using tools. " +
                            "Use the configured JSON schema for final answers: fullText is the visible answer; " +
                            "ttsText and voiceTone are empty; attentionRequested is false."
                    ),
                    messages = history.toList(), tools = listOf(readFileTool), options = options,
                ),
                profile, conversationId.value, connectionId = connectionId,
            )
            assertEquals("low", request.reasoning?.get("effort")?.jsonPrimitive?.content)
            val parsed = client.create(testModelConfiguration(), session, conversationId.value, request, profile, options.assistantResponseFormat)
            val response = responseMapper.toRuntimeResponse(
                parsed.outputItems, parsed.completed, conversationId.value, connectionId,
                "live-progress-$model", model, options.assistantResponseFormat,
            )
            println("Live progress step: model=$model outcome=${response.outcome} items=" +
                parsed.outputItems.map { it["type"]?.jsonPrimitive?.contentOrNull })
            response.messages.forEach { assistant ->
                history += message(conversationId, Conversation.Message.Role.ASSISTANT, assistant.content).copy(
                    providerMetadata = JsonObject((response.providerMetadata + assistant.metadata).mapValues { (_, value) ->
                        value as? JsonElement ?: JsonPrimitive(value.toString())
                    }),
                )
            }
            return response
        }

        val connectedClient = client()
        val initial = step(connectedClient)
        assertEquals(AiStepOutcome.TOOL_CALLS, initial.outcome)
        val blocks = initial.messages.flatMap { it.content }
        val remarkIndex = blocks.indexOfFirst { it is Conversation.Message.ContentItem.AssistantMessage }
        val callIndex = blocks.indexOfFirst { it is Conversation.Message.ContentItem.ToolCall }
        assertTrue(remarkIndex in 0 until callIndex, "Expected a readable remark before the tool call")
        val toolCall = initial.toolCalls.single()
        assertEquals("read_file", toolCall.call.name)
        assertEquals("README.md", toolCall.call.input.jsonObject["path"]?.jsonPrimitive?.content)
        history += message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCall.id, toolName = toolCall.call.name,
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("File marker: $marker")),
            )
        ))
        assertFinal(step(connectedClient))

        history += message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.UserMessage("Repeat the same file marker from the previous result. Do not call any tools.")
        ))
        assertFinal(step(client()))
    }

    private fun assertFinal(response: AiRuntimeResponse) {
        assertEquals(AiStepOutcome.COMPLETE, response.outcome)
        assertTrue(response.toolCalls.isEmpty())
        val text = response.messages.flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
        assertTrue(marker in text, "Expected the tool result marker in the final answer")
    }

    private fun authenticatedSession(): OpenAiSubscriptionSession {
        val authFile = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE")) {
            "Set GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE to an existing Codex auth.json; the test never refreshes or modifies it"
        }
        val tokens = Json.parseToJsonElement(File(authFile).readText()).jsonObject.getValue("tokens").jsonObject
        val accessToken = tokens.getValue("access_token").jsonPrimitive.content
        val claims = Json.parseToJsonElement(
            Base64.getUrlDecoder().decode(accessToken.split('.')[1]).decodeToString()
        ).jsonObject
        val expiresAt = claims.getValue("exp").jsonPrimitive.long
        require(expiresAt > Clock.System.now().epochSeconds + 300) { "Codex access token needs renewal before this test" }
        return OpenAiSubscriptionSession(accessToken, "unused", null,
            tokens.getValue("account_id").jsonPrimitive.content, expiresAt)
    }

    private fun message(
        conversationId: Conversation.Id,
        role: Conversation.Message.Role,
        content: List<Conversation.Message.ContentItem>,
    ) = Conversation.Message(
        id = Conversation.Message.Id(UUID.randomUUID().toString()),
        conversationId = conversationId, role = role, content = content, createdAt = Clock.System.now(),
    )

    private val readFileTool = object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "read_file", description = "Read a project file by relative path.",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"}},"required":["path"]}""",
        )
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            error("The test supplies synthetic tool results; providers must not execute tools")
    }

    private companion object {
        const val baseUrl = "https://chatgpt.com/backend-api/codex"
        const val clientVersion = "1.4.9"
        const val connectionId = "live-progress-connection"
        const val marker = "PROGRESS_ROUNDTRIP_OK"
    }
}
