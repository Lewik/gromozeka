package com.gromozeka.infrastructure.ai.openai.subscription

import kotlinx.serialization.json.JsonElement

import com.gromozeka.application.service.ConversationPinnedPrompt
import com.gromozeka.application.service.StickyMessageInstructionService
import com.gromozeka.application.service.SuggestedRepliesGenerationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinSource
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings
import com.gromozeka.domain.model.putSyntheticMessageMetadata
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.repository.ConversationPinCreation
import com.gromozeka.domain.repository.ConversationPinRepository
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiSubscriptionRequestMapperTest {
    @Test
    fun `sticky instruction after a full checkpoint reaches the wire through a stable runtime carrier`() {
        val instruction = Conversation.Message.Instruction.UserInstruction(
            id = "access-readonly",
            title = "Read only",
            description = "STICKY_INSTRUCTION_REACHED_WIRE",
        )
        val group = MessageInstructionGroup(
            id = "access",
            title = "Access",
            controls = listOf(MessageInstructionGroup.Control(instruction, "R")),
            retentionMode = MessageInstructionGroup.RetentionMode.STICKY_LATEST,
        )
        val checkpoint = assistantCompactionMessage("checkpoint", null)
        val tail = Conversation.Message(
            id = Conversation.Message.Id("tail"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Continue after compact")),
            createdAt = createdAt,
        )
        val service = StickyMessageInstructionService()
        val continuation = Conversation.Message.ContentItem.ContextCompactionResult.ContinuationState(
            stickyInstructionCandidates = listOf(instruction),
        )
        val checkpointOnly = service.materialize(listOf(checkpoint), listOf(group), continuation)
        val messages = service.materialize(listOf(checkpoint, tail), listOf(group), continuation)

        assertEquals(
            listOf(
                Conversation.Message.Role.ASSISTANT,
                Conversation.Message.Role.RUNTIME,
                Conversation.Message.Role.USER,
            ),
            messages.map { it.role },
        )
        val checkpointInput = mapper.toRequest(
            AiRuntimeRequest(emptyList(), checkpointOnly),
            modelProfile = modelProfile("gpt-5"),
            conversationKey = "sticky-continuation",
        ).input
        val input = mapper.toRequest(
            AiRuntimeRequest(emptyList(), messages),
            modelProfile = modelProfile("gpt-5"),
            conversationKey = "sticky-continuation",
        ).input
        val carrierIndex = input.indexOfFirst { "STICKY_INSTRUCTION_REACHED_WIRE" in it.toString() }
        val tailIndex = input.indexOfFirst { "Continue after compact" in it.toString() }

        assertEquals(checkpointInput, input.take(checkpointInput.size))
        assertTrue(carrierIndex > 0)
        assertTrue(tailIndex > carrierIndex)
        assertEquals(JsonPrimitive("user"), input[carrierIndex]["role"])
    }

    @Test
    fun `suggested replies media projection reaches the wire instead of matching provider replay`() = runBlocking {
        val rawMarker = "RAW_REPLAY_SHOULD_NOT_SURVIVE"
        val source = Conversation.Message(
            id = Conversation.Message.Id("assistant-media"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ImageItem(
                Conversation.Message.ImageSource.Base64ImageSource("AQID", "image/png"),
            )),
            providerMetadata = buildJsonObject {
                put("provider", AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
                put("connectionId", "connection")
                put("model", "gpt-5")
                put(AI_REPLAY_THREAD_METADATA_KEY, "thread")
                put("openaiSubscriptionProviderItems", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put("content", rawMarker)
                    })
                })
            },
            createdAt = createdAt,
        )
        lateinit var mapped: OpenAiSubscriptionResponsesRequest
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                mapped = mapper.toRequest(
                    request = request,
                    modelProfile = modelProfile("gpt-5"),
                    conversationKey = "suggested-replies",
                    connectionId = "connection",
                )
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
                    Conversation.Message.ContentItem.AssistantMessage(
                        Conversation.Message.StructuredText("", suggestedReplies = listOf("Continue")),
                    ),
                ))))
            }

            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("Unexpected streaming")
        }
        val service = SuggestedRepliesGenerationService(
            aiRuntimeProvider = object : AiRuntimeProvider {
                override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime = runtime
            },
            conversationPins = emptyPinnedPrompt(),
            aiConfigurationProvider = unusedProxy(),
        )
        val agentId = AgentDefinition.Id("agent")
        val conversation = Conversation(
            id = conversationId,
            projectId = Project.Id("project"),
            participants = setOf(
                Conversation.Participant.User(User.Id("user")),
                Conversation.Participant.Agent(agentId),
            ),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        service.generate(
            conversation = conversation,
            context = SuggestedRepliesGenerationService.Context.Full(listOf(source)),
            sourceMessage = source,
            agentDefinitionId = agentId,
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("suggestions-model")),
            actorUserId = User.Id("user"),
            usageId = "suggestions",
            settings = SuggestedRepliesSettings(contextMode = SuggestedRepliesSettings.ContextMode.FULL_THREAD),
        )

        val wire = mapped.input.toString()
        assertFalse(rawMarker in wire)
        assertTrue("Image omitted from suggested replies" in wire)
    }

    @Test
    fun `runtime role maps its instructions and content to user input`() {
        val runtimeMessage = Conversation.Message(
            id = Conversation.Message.Id("runtime-observation"),
            conversationId = conversationId,
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(buildJsonObject {
                put("kind", "runtime_state")
                put("value", "RUNTIME_JSON")
            })),
            instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("CURRENT_RUNTIME_STATE")),
            createdAt = createdAt,
        )

        val message = mapper.toRequest(
            request = AiRuntimeRequest(systemPrompts = emptyList(), messages = listOf(runtimeMessage)),
            modelName = "gpt-5",
            conversationKey = "runtime-role",
        ).input.single()

        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertTrue(message.toString().contains("CURRENT_RUNTIME_STATE"))
        assertTrue(message.toString().contains("RUNTIME_JSON"))
    }

    @Test fun `synthetic async completion uses native function output with model visible provenance`() {
        val source = Conversation.Message.ContentItem.ToolCall.Id("source-call")
        val completion = com.gromozeka.domain.model.syntheticToolCallId("async-tool:operation:completion")
        val metadata = buildJsonObject {
            putSyntheticMessageMetadata(
                com.gromozeka.domain.model.SyntheticMessageKind.ASYNC_TOOL_COMPLETION,
                com.gromozeka.domain.model.BackgroundActivityMessageSource(source, com.gromozeka.domain.model.AgentDefinition.Id("agent")))
            put("asyncToolOperationId", "operation")
        }
        val call = Conversation.Message(Conversation.Message.Id("completion-call"), conversationId,
            role = Conversation.Message.Role.ASSISTANT, author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.ToolCall(completion,
                Conversation.Message.ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("Runtime completion for operation; source ${source.value}; not another execution.")),
            providerMetadata = metadata, createdAt = createdAt)
        val result = call.copy(id = Conversation.Message.Id("completion-result"), role = Conversation.Message.Role.USER,
            instructions = emptyList(), content = listOf(Conversation.Message.ContentItem.ToolResult(completion, "tool",
                listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("{\"answer\":42}")))))
        val request = AiRuntimeRequest(emptyList(), listOf(call, result))
        val actual = mapper.toRequest(request, modelName = "gpt-5", conversationKey = "async-wire").input
        val ordinaryCall = call.copy(author = null, instructions = emptyList(), providerMetadata = JsonObject(emptyMap()),
            content = listOf(Conversation.Message.ContentItem.ToolCall(source, Conversation.Message.ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))))
        val ordinaryResult = result.copy(author = null, providerMetadata = JsonObject(emptyMap()), content = listOf(
            (result.content.single() as Conversation.Message.ContentItem.ToolResult).copy(toolUseId = source)))
        val ordinary = mapper.toRequest(request.copy(messages = listOf(ordinaryCall, ordinaryResult)), modelName = "gpt-5", conversationKey = "sync-wire").input
        val output = actual.single { it["type"] == JsonPrimitive("function_call_output") }
        val expected = ordinary.single { it["type"] == JsonPrimitive("function_call_output") }
        assertEquals(completion.value, output.getValue("call_id").jsonPrimitive.content)
        assertEquals(JsonObject(expected - "call_id"), JsonObject(output - "call_id"))
        assertTrue(actual.single { it["role"] == JsonPrimitive("assistant") }.getValue("content").toString().contains(source.value))
        assertTrue(actual.none { it["role"] == JsonPrimitive("user") })
    }

    @Test
    fun visualActionIsUserInputWithTheFullSnapshotAndExplicitProvenance() {
        val snapshot = Json.parseToJsonElement("""{"form":{"query":"saved"},"data":{"count":7}}""").jsonObject
        val event = Conversation.Message.ContentItem.VisualInteraction("v", "Pipeline Runs", 3, "event-1", "refresh", "Refresh", snapshot)
        val message = Conversation.Message(Conversation.Message.Id("click"), conversationId,
            role = Conversation.Message.Role.USER, content = listOf(event), createdAt = createdAt)
        val input = mapper.toRequest(AiRuntimeRequest(emptyList(), listOf(message)), modelName = "gpt-5", conversationKey = "test").input
        val user = input.single { it["role"] == JsonPrimitive("user") }
        val text = user["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(event.modelText(), text)
        assertTrue(text.contains("not a typed chat message"))
        assertTrue(text.contains("\"query\":\"saved\""))
        assertTrue(text.contains("\"count\":7"))
    }

    @Test
    fun selectiveReadableSummaryDoesNotTruncateEarlierUnselectedHistory() {
        fun text(id: String, text: String) = Conversation.Message(
            id = Conversation.Message.Id(id), conversationId = conversationId, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)), createdAt = createdAt,
        )
        val earlier = text("a", "Keep earlier instruction")
        val source = text("b", "Covered original")
        val summary = text("s", "").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(
            Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Selective summary"),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                sourceMessageIds = listOf(source.id),
            ),
        ))
        val input = mapper.toRequest(AiRuntimeRequest(emptyList(), listOf(earlier, source, summary, text("d", "Tail"))),
            modelName = "gpt-5", conversationKey = "test").input.toString()
        assertTrue(input.contains("Keep earlier instruction"))
        assertTrue(input.contains("Selective summary"))
        assertTrue(input.contains("Tail"))
        assertFalse(input.contains("Covered original"))
    }

    @Test
    fun `opaque reasoning marker is replayed exactly once and only to its provider scope`() {
        val raw = Json.parseToJsonElement("""{"type":"reasoning","id":"rs-1","summary":[],"encrypted_content":"opaque"}""").jsonObject
        val runtime = OpenAiSubscriptionResponseMapper().toRuntimeResponse(
            outputItems = listOf(raw),
            completed = OpenAiSubscriptionCompletedResponse("response", "completed"),
            conversationKey = "test", connectionId = "connection", modelConfigurationId = "model", modelName = "gpt-5",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
        val assistant = runtime.messages.single()
        assertTrue((assistant.content.single() as Conversation.Message.ContentItem.Thinking).isVisible)
        val request = AiRuntimeRequest(emptyList(), listOf(Conversation.Message(
            id = Conversation.Message.Id("reasoning"), conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT, content = assistant.content, createdAt = createdAt,
            providerMetadata = JsonObject((runtime.providerMetadata + assistant.metadata).mapValues { (_, value) ->
                value as? JsonElement ?: JsonPrimitive(value.toString())
            }),
        )))
        val matching = mapper.toRequest(request, modelProfile("gpt-5"), "test", connectionId = "connection")
        assertEquals(listOf(raw), matching.input)
        assertEquals(listOf(raw), mapper.toReplayItems(listOf(raw), AiModelConfiguration.AssistantResponseFormat.TEXT, modelProfile("gpt-5")))
        val foreign = mapper.toRequest(request, modelProfile("gpt-5"), "test", connectionId = "other")
        assertTrue(foreign.input.isEmpty())
    }
    private val mapper = OpenAiSubscriptionRequestMapper()
    private val conversationId = Conversation.Id("conversation-test")
    private val createdAt = Instant.parse("2026-05-08T00:00:00Z")

    @Test
    fun keepsToolCallsFromCompactionAnchorSoFollowingToolResultsRemainMatched() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("call_keep")
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    assistantCompactionMessage(
                        id = "assistant-anchor",
                        toolCallId = toolCallId,
                    ),
                    userToolResultMessage(
                        id = "tool-result",
                        toolCallId = toolCallId,
                    ),
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val itemTypes = request.input.map { it.string("type") }
        assertEquals(listOf("compaction", "function_call", "function_call_output"), itemTypes)
        assertEquals("call_keep", request.input[1].string("call_id"))
        assertEquals("call_keep", request.input[2].string("call_id"))
    }

    @Test
    fun `maps binary screenshot tool result to a Responses image output`() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("call-screenshot")
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("tool-call-image"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.ASSISTANT,
                        content = listOf(
                            Conversation.Message.ContentItem.ToolCall(
                                id = toolCallId,
                                call = Conversation.Message.ContentItem.ToolCall.Data(
                                    name = "grz_capture_screenshot",
                                    input = buildJsonObject {},
                                ),
                            )
                        ),
                        createdAt = createdAt,
                    ),
                    Conversation.Message(
                        id = Conversation.Message.Id("tool-result-image"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.ToolResult(
                                toolUseId = toolCallId,
                                toolName = "grz_capture_screenshot",
                                result = listOf(
                                    Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                                        data = "AQID",
                                        mediaType = Conversation.Message.MediaType.parse("image/png"),
                                        fileName = "worker-screen.png",
                                    )
                                ),
                            )
                        ),
                        createdAt = createdAt,
                    )
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val output = request.input.last().jsonArray("output").single().jsonObject
        assertEquals("input_image", output.string("type"))
        assertEquals("data:image/png;base64,AQID", output.string("image_url"))
    }

    @Test
    fun `maps user document attachment to a Responses file input`() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("user-document"),
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = listOf(
                            Conversation.Message.ContentItem.UserMessage("Inspect this report"),
                            Conversation.Message.ContentItem.DocumentItem(
                                Conversation.Message.DocumentSource.Base64DocumentSource(
                                    data = "AQID",
                                    mediaType = "application/pdf",
                                    fileName = "report.pdf",
                                )
                            ),
                        ),
                        createdAt = createdAt,
                    )
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val file = request.input.single().jsonArray("content")[1].jsonObject
        assertEquals("input_file", file.string("type"))
        assertEquals("report.pdf", file.string("filename"))
        assertEquals("data:application/pdf;base64,AQID", file.string("file_data"))
    }

    @Test
    fun shortensLongToolCallIdsWithoutBreakingToolResultPairing() {
        val originalId = "call_" + "x".repeat(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH)
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id(originalId)
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    assistantCompactionMessage(
                        id = "assistant-anchor",
                        toolCallId = toolCallId,
                    ),
                    userToolResultMessage(
                        id = "tool-result",
                        toolCallId = toolCallId,
                    ),
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val functionCallId = requireNotNull(request.input[1].string("call_id"))
        val functionCallOutputId = requireNotNull(request.input[2].string("call_id"))
        assertEquals(OPENAI_SUBSCRIPTION_KEY_MAX_LENGTH, functionCallId.length)
        assertEquals(functionCallId, functionCallOutputId)
        assertFalse(functionCallId == originalId)
    }

    @Test
    fun dropsFunctionCallOutputsThatDoNotHaveAMatchingFunctionCallInTheReplayWindow() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(
                    assistantCompactionMessage(
                        id = "assistant-anchor",
                        toolCallId = null,
                    ),
                    userToolResultMessage(
                        id = "orphan-result",
                        toolCallId = Conversation.Message.ContentItem.ToolCall.Id("call_orphan"),
                    ),
                ),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        val itemTypes = request.input.map { it.string("type") }
        assertEquals(listOf("compaction"), itemTypes)
        assertFalse(request.input.any { it.string("type") == "function_call_output" })
    }

    @Test
    fun ordinaryRequestsNeverConfigureInlineCompaction() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertFalse(Json.encodeToString(OpenAiSubscriptionResponsesRequest.serializer(), request).contains("\"context_management\""))
    }

    @Test
    fun ordinaryRequestsOmitContextManagement() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertFalse(Json.encodeToString(OpenAiSubscriptionResponsesRequest.serializer(), request).contains("\"context_management\""))
    }

    @Test
    fun omitsToolsWhenToolChoiceIsNone() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(toolChoice = AiToolChoice.None),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals(emptyList(), request.tools)
        assertNull(request.toolChoice)
    }

    @Test
    fun forcesDefaultServiceTierForMemoryStages() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                options = AiRuntimeOptions(
                    toolContext = mapOf("memoryNamespace" to "project:test"),
                ),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals("default", request.serviceTier)
        assertEquals(
            "default",
            OpenAiSubscriptionResponsesWebSocketRequest.from(request, useResponsesLite = false).serviceTier,
        )
    }

    @Test
    fun leavesServiceTierUnsetForNormalRequests() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertNull(request.serviceTier)
    }

    @Test
    fun includesToolsWhenToolChoiceAllowsTools() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(toolChoice = AiToolChoice.Auto),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        )

        assertEquals(1, request.tools.orEmpty().size)
    }

    @Test
    fun includesHostedWebSearchWhenSubscriptionConnectionEnablesIt() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
            ),
            modelProfile = modelProfile(slug = "gpt-5.5"),
            conversationKey = "test-conversation",
            webSearchEnabled = true,
        )

        assertEquals(listOf("function", "web_search"), request.tools.orEmpty().map { it.string("type") })
        assertTrue("web_search_call.action.sources" in request.include)
    }

    @Test
    fun agentAllowlistControlsHostedSearchInBothRequestFormats() {
        for (lite in listOf(false, true)) {
            val profile = modelProfile(slug = "gpt-5.6-luna", useResponsesLite = lite)
            val input = AiRuntimeRequest(emptyList(), emptyList(), options = AiRuntimeOptions(
                toolAccess = com.gromozeka.domain.tool.ToolAccessPolicy.AllowOnly(),
            ))
            val blocked = mapper.toTransportRequest(mapper.toRequest(input, profile, "test", webSearchEnabled = true), profile)
            assertTrue(blocked.tools.orEmpty().none { it.string("type") == "web_search" })
            assertTrue(blocked.input.filter { it.string("type") == "additional_tools" }
                .flatMap { it.jsonArray("tools") }.none { it.jsonObject.string("type") == "web_search" })
            assertTrue("web_search_call.action.sources" !in blocked.include)
            val policy = com.gromozeka.domain.tool.ToolAccessPolicy.AllowOnly(setOf(
                com.gromozeka.domain.tool.ProviderNativeTool.OPENAI_SUBSCRIPTION_WEB_SEARCH.catalogEntry().selector(false),
            ))
            val allowed = mapper.toRequest(input.copy(options = input.options.copy(toolAccess = policy)), profile, "test", webSearchEnabled = true)
            assertTrue(allowed.tools.orEmpty().any { it.string("type") == if (lite) "namespace" else "web_search" })
        }
    }

    @Test
    fun framesStandaloneWebSearchAndFunctionsInsideResponsesLiteNamespaces() {
        val profile = modelProfile(slug = "gpt-5.6-sol", useResponsesLite = true)
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
            webSearchEnabled = true,
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)
        val additionalTools = transportRequest.input.first().jsonArray("tools")
        assertEquals(listOf("namespace", "namespace"), additionalTools.map { it.jsonObject.string("type") })
        assertEquals(listOf("functions", "web"), additionalTools.map { it.jsonObject.string("name") })
        assertEquals("run", additionalTools.last().jsonObject.jsonArray("tools").single().jsonObject.string("name"))
        assertNull(transportRequest.tools)
        assertFalse("web_search_call.action.sources" in transportRequest.include)
    }

    @Test
    fun rejectsRequiredToolChoiceForResponsesLiteBeforeSendingInvalidRequest() {
        val profile = modelProfile(slug = "gpt-5.6-sol", useResponsesLite = true)
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(toolChoice = AiToolChoice.RequiredAny),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
            webSearchEnabled = true,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            mapper.toTransportRequest(logicalRequest, profile)
        }

        assertEquals("OpenAI Responses Lite supports only automatic tool choice", error.message)
    }

    @Test
    fun mapsSystemMessagesAsDeveloperInputItemsForSubscriptionEndpoint() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(systemMessage(id = "system-note", text = "Runtime context.")),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        assertEquals(listOf("message"), request.input.map { it.string("type") })
        assertEquals("developer", request.input.single().string("role"))
        assertEquals("input_text", request.input.single().jsonArray("content").single().jsonObject.string("type"))
    }

    @Test
    fun dropsErrorSystemMessagesFromSubscriptionReplayInput() {
        val request = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(systemMessage(id = "system-error", text = "Previous API error.", isError = true)),
            ),
            modelName = "gpt-5",
            conversationKey = "test-conversation",
        )

        assertEquals(emptyList(), request.input)
    }

    @Test
    fun framesResponsesLiteToolsAndInstructionsInsideInput() {
        val profile = modelProfile(slug = "gpt-5.6-sol", useResponsesLite = true)
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = listOf("Base instructions"),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
                options = AiRuntimeOptions(
                    reasoning = AiReasoningConfig(effort = AiReasoningEffort.HIGH),
                    ),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)

        assertNull(transportRequest.instructions)
        assertNull(transportRequest.tools)
        assertFalse(Json.encodeToString(OpenAiSubscriptionResponsesRequest.serializer(), transportRequest).contains("\"context_management\""))
        assertFalse(transportRequest.parallelToolCalls)
        assertEquals("additional_tools", transportRequest.input[0].string("type"))
        assertEquals("developer", transportRequest.input[0].string("role"))
        assertEquals(1, transportRequest.input[0].jsonArray("tools").size)
        assertEquals("message", transportRequest.input[1].string("type"))
        assertEquals("developer", transportRequest.input[1].string("role"))
        assertEquals("all_turns", transportRequest.reasoning?.string("context"))
        assertEquals(listOf("reasoning.encrypted_content"), transportRequest.include)

        val websocketRequest = OpenAiSubscriptionResponsesWebSocketRequest.from(
            request = transportRequest,
            useResponsesLite = true,
        )
        assertEquals(
            "true",
            websocketRequest.clientMetadata
                ?.get("ws_request_header_x_openai_internal_codex_responses_lite"),
        )
    }

    @Test
    fun addsAllTurnsReasoningContextForResponsesLiteWithoutExplicitReasoning() {
        val profile = modelProfile(slug = "gpt-5.6-sol", useResponsesLite = true)
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = listOf("Base instructions"),
                messages = emptyList(),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)

        assertEquals("all_turns", transportRequest.reasoning?.string("context"))
    }

    @Test
    fun keepsNormalResponsesToolsAndInstructionsAtTopLevel() {
        val profile = modelProfile(slug = "gpt-5.5")
        val logicalRequest = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = listOf("Base instructions"),
                messages = emptyList(),
                tools = listOf(testTool("memory_remember")),
            ),
            modelProfile = profile,
            conversationKey = "test-conversation",
        )

        val transportRequest = mapper.toTransportRequest(logicalRequest, profile)

        assertEquals("Base instructions", transportRequest.instructions)
        assertEquals(1, transportRequest.tools.orEmpty().size)
        assertTrue(transportRequest.parallelToolCalls)
        assertFalse(transportRequest.input.any { it.string("type") == "additional_tools" })
    }

    @Test
    fun normalizesStructuredOutputToThePersistedAssistantReplayProjection() {
        val structured = Conversation.Message.StructuredText(
            fullText = "Visible answer",
            ttsText = "Spoken answer",
            voiceTone = "warm",
        )
        val persistedMessage = Conversation.Message(
            id = Conversation.Message.Id("assistant"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(structured = structured)
            ),
            providerMetadata = buildJsonObject { put("phase", "final_answer") },
            createdAt = createdAt,
        )
        val persistedReplayItem = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(persistedMessage),
            ),
            modelName = "gpt-5.6-luna",
            conversationKey = "test-conversation",
        ).input.single()
        val rawOutputItem = buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("phase", "final_answer")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", Json.encodeToString(structured))
                })
            })
        }

        val responseReplayItem = mapper.toReplayItems(
            outputItems = listOf(rawOutputItem),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            modelProfile = modelProfile("gpt-5.6-luna"),
        ).single()

        assertEquals(persistedReplayItem, responseReplayItem)
        assertEquals("Visible answer", responseReplayItem.string("content"))
    }

    @Test
    fun normalizesToolArgumentsToThePersistedToolCallProjection() {
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "test_tool",
                input = buildJsonObject {
                    put("first", 1)
                    put("second", "two")
                },
            ),
        )
        val persistedMessage = Conversation.Message(
            id = Conversation.Message.Id("assistant-tool"),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(toolCall),
            createdAt = createdAt,
        )
        val persistedReplayItem = mapper.toRequest(
            request = AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = listOf(persistedMessage),
            ),
            modelName = "gpt-5.5",
            conversationKey = "test-conversation",
        ).input.single()
        val rawOutputItem = buildJsonObject {
            put("type", "function_call")
            put("call_id", "call-1")
            put("name", "test_tool")
            put("arguments", "{ \"first\" : 1, \"second\" : \"two\" }")
        }

        val responseReplayItem = mapper.toReplayItems(
            outputItems = listOf(rawOutputItem),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
            modelProfile = modelProfile("gpt-5.5"),
        ).single()

        assertEquals(persistedReplayItem, responseReplayItem)
        assertEquals("{\"first\":1,\"second\":\"two\"}", responseReplayItem.string("arguments"))
    }

    @Test
    fun mapsMaximumReasoningToHighestSupportedNonUltraEffort() {
        val request = AiRuntimeRequest(
            systemPrompts = emptyList(),
            messages = emptyList(),
            options = AiRuntimeOptions(
                reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX),
            ),
        )

        val gpt55 = mapper.toRequest(
            request = request,
            modelProfile = modelProfile(slug = "gpt-5.5"),
            conversationKey = "test-conversation",
        )
        val gpt56 = mapper.toRequest(
            request = request,
            modelProfile = modelProfile(
                slug = "gpt-5.6-sol",
                useResponsesLite = true,
                supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            ),
            conversationKey = "test-conversation",
        )

        assertEquals("xhigh", assertNotNull(gpt55.reasoning).string("effort"))
        assertEquals("max", assertNotNull(gpt56.reasoning).string("effort"))
    }

    private fun assistantCompactionMessage(
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id?,
    ): Conversation.Message {
        val content = buildList {
            add(
                Conversation.Message.ContentItem.ContextCompactionResult(
                    payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                        state = buildJsonObject {
                            put(
                                "replay_item",
                                buildJsonObject {
                                    put("type", "compaction")
                                    put("encrypted_content", "encrypted")
                                }
                            )
                        },
                    ),
                    origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                    coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(
                        provider = AiConnection.Kind.OPENAI_SUBSCRIPTION.name,
                    ),
                )
            )
            if (toolCallId != null) {
                add(
                    Conversation.Message.ContentItem.ToolCall(
                        id = toolCallId,
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = "grz_read_file",
                            input = buildJsonObject { put("file_path", "/tmp/example.txt") },
                        ),
                    )
                )
            }
        }

        return Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = content,
            createdAt = createdAt,
        )
    }

    private fun userToolResultMessage(
        id: String,
        toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = "grz_read_file",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Text("file contents")
                    ),
                )
            ),
            createdAt = createdAt,
        )

    private fun systemMessage(
        id: String,
        text: String,
        isError: Boolean = false,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
                    content = text,
                )
            ),
            error = if (isError) Conversation.Message.GenerationError(message = text, type = "api") else null,
            createdAt = createdAt,
        )

    private fun testTool(name: String): AiToolCallback =
        object : AiToolCallback {
            override val metadata = ServerToolMetadata
            override val definition = AiToolDefinition(
                name = name,
                description = "Test tool",
                inputSchema = """{"type":"object","properties":{}}""",
            )

            override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String =
                "ok"
        }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.jsonArray(key: String): JsonArray =
        this.getValue(key).jsonArray
}

private fun OpenAiSubscriptionRequestMapper.toRequest(
    request: AiRuntimeRequest,
    modelName: String,
    conversationKey: String,
): OpenAiSubscriptionResponsesRequest = toRequest(
    request = request,
    modelProfile = modelProfile(slug = modelName),
    conversationKey = conversationKey,
    webSearchEnabled = false,
)

private fun modelProfile(
    slug: String,
    useResponsesLite: Boolean = false,
    supportedReasoningEfforts: List<String> = listOf("low", "medium", "high", "xhigh"),
): OpenAiSubscriptionModelProfile = OpenAiSubscriptionModelProfile(
    slug = slug,
    useResponsesLite = useResponsesLite,
    supportsReasoningSummaries = true,
    supportedReasoningEfforts = supportedReasoningEfforts,
    supportsVerbosity = true,
    defaultVerbosity = "low",
    supportsParallelToolCalls = true,
)

private fun emptyPinnedPrompt(): ConversationPinnedPrompt = ConversationPinnedPrompt(object : ConversationPinRepository {
    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> = emptyList()
    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? = error("Unexpected get")
    override suspend fun create(
        conversationId: Conversation.Id,
        actor: User.Id,
        text: String,
        source: ConversationPinSource?,
        creationKey: String?,
    ): ConversationPinCreation = error("Unexpected create")
    override suspend fun update(
        conversationId: Conversation.Id,
        pinId: ConversationPin.Id,
        expectedRevision: Long,
        text: String,
    ): ConversationPin = error("Unexpected update")
    override suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long) {
        error("Unexpected delete")
    }
})

private inline fun <reified T : Any> unusedProxy(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("Unexpected ${T::class.simpleName}.${method.name}") } as T
