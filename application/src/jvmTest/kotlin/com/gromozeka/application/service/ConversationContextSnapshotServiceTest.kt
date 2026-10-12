package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationContextSnapshotServiceTest {
    @Test fun `pinned prefix includes available runtime layers without turn preparation or catalog writes`() = runBlocking<Unit> {
        val f = Fixture()
        val original = listOf(f.user, f.incompleteCall)
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(original)
        val snapshot = f.capture()
        assertEquals(f.target, snapshot.target)
        assertEquals(2, snapshot.sourceMessageCount)
        assertEquals(1, snapshot.omittedTrailingMessageCount)
        assertEquals(listOf(f.user.id), snapshot.request.messages.map { it.id })
        assertEquals(f.user.id, snapshot.lastIncludedMessageId)
        assertEquals("base", snapshot.request.systemPrompts.first())
        assertTrue(snapshot.request.systemPrompts.any { "<current_agent>" in it })
        for (block in listOf("catalog", "environment", "skills", "read-only-runtime")) assertContains(snapshot.request.systemPrompts, block)
        assertEquals(AiToolChoice.None, snapshot.request.options.toolChoice)
        assertEquals(ToolAccessPolicy.AllowOnly(), snapshot.request.options.toolAccess)
        assertNull(snapshot.request.options.autoCompactionThresholdTokens)
        assertEquals(1, f.snapshotEnrichments)
        assertEquals(original, listOf(f.user, f.incompleteCall))
        Mockito.verify(f.history).getMessagesByThread(f.target.threadId)
        Mockito.verifyNoMoreInteractions(f.history)
        Mockito.verify(f.catalog).readSnapshot(f.project, f.agent.toolAccess, f.conversation)
        Mockito.verifyNoMoreInteractions(f.catalog)
        Mockito.verify(f.capabilities).cachedPromptFor(emptyList())
        Mockito.verifyNoMoreInteractions(f.capabilities)
        Mockito.verify(f.conversations).getProject(f.conversation.id)
        Mockito.verifyNoMoreInteractions(f.conversations)
        Mockito.verifyNoInteractions(f.artifactRepository, f.contentStore)
    }

    @Test fun `history reader never follows an advanced current branch`() = runBlocking<Unit> {
        val f = Fixture()
        val advanced = f.conversation.copy(currentThread = Conversation.Thread.Id("advanced"))
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(advanced)
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        val snapshot = f.capture()
        assertEquals(f.target.threadId, snapshot.target.threadId)
        Mockito.verify(f.history).getMessagesByThread(f.target.threadId)
        Mockito.verifyNoMoreInteractions(f.history)
        Mockito.verify(f.conversations, Mockito.never()).findById(f.conversation.id)
    }

    @Test fun `missing mandatory cached catalog fails instead of reduced history-only view`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        Mockito.`when`(f.capabilities.cachedPromptFor(emptyList())).thenThrow(ContextSnapshotUnavailableException("not ready"))
        assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
        assertEquals(0, f.snapshotEnrichments)
        Mockito.verifyNoInteractions(f.artifactRepository, f.contentStore)
    }

    @Test fun `excluded incomplete tail cannot erase sticky instructions from retained prefix`() = runBlocking<Unit> {
        val f = Fixture()
        val retained = Conversation.Message.Instruction.UserInstruction("retained", "Retained", "Retained instruction")
        val newer = Conversation.Message.Instruction.UserInstruction("newer", "Newer", "Excluded instruction")
        val group = MessageInstructionGroup("mode", "Mode", controls = listOf(
            MessageInstructionGroup.Control(retained, "R"), MessageInstructionGroup.Control(newer, "N")),
            retentionMode = MessageInstructionGroup.RetentionMode.STICKY_LATEST)
        Mockito.`when`(f.settings.userProfile).thenReturn(UserProfile(messageInstructionGroups = listOf(group)))
        val first = f.user.copy(instructions = listOf(retained))
        val tail = f.user.copy(id = Conversation.Message.Id("excluded"), instructions = listOf(newer))
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(first, f.incompleteCall, tail))
        val snapshot = f.capture()
        assertEquals(listOf(retained), snapshot.request.messages.single().instructions.filterIsInstance<Conversation.Message.Instruction.UserInstruction>())
        assertEquals(2, snapshot.omittedTrailingMessageCount)
    }

    @Test fun `empty history streaming first message and incomplete first parallel group permit an empty snapshot`() = runBlocking<Unit> {
        val f = Fixture()
        val streaming = f.user.copy(content = listOf(Conversation.Message.ContentItem.UserMessage("unfinished", state = Conversation.Message.BlockState.STREAMING)))
        val calls = f.incompleteCall.copy(content = listOf(toolCall("a"), toolCall("b")))
        val onlyFirstResult = f.user.copy(id = Conversation.Message.Id("result-a"), content = listOf(toolResult("a")))
        for (raw in listOf(emptyList(), listOf(streaming), listOf(calls, onlyFirstResult))) {
            Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(raw)
            val snapshot = f.capture()
            assertTrue(snapshot.request.messages.isEmpty())
            assertEquals(raw.size, snapshot.sourceMessageCount)
            val (sent, answer) = invokeOnce(snapshot, f.configuration)
            assertEquals("Not enough information", answer)
            assertEquals(1, sent.messages.size) // Only the temporary question, not a technical failure.
        }
    }

    @Test fun `excluded native owner full checkpoint cannot erase otherwise complete history`() = runBlocking<Unit> {
        val f = Fixture(nativeClaude = true)
        val checkpoint = f.user.copy(id = Conversation.Message.Id("native-checkpoint"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(summary(listOf(f.user.id), Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS, native = true),
                Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("still streaming"), state = Conversation.Message.BlockState.STREAMING)))
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user, checkpoint))
        assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
    }

    @Test fun `excluded readable selective summary cannot erase otherwise complete history`() = runBlocking<Unit> {
        val f = Fixture()
        val checkpoint = f.user.copy(id = Conversation.Message.Id("selective-checkpoint"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(summary(listOf(f.user.id), Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES),
                Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("still streaming"), state = Conversation.Message.BlockState.STREAMING)))
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user, checkpoint))
        assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
    }

    @Test fun `completed selective summary behind an unfinished group cannot erase the preceding complete prefix`() = runBlocking<Unit> {
        val f = Fixture()
        val checkpoint = f.user.copy(id = Conversation.Message.Id("selective-checkpoint"), content = listOf(
            summary(listOf(f.user.id), Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val raw = listOf(f.user, f.incompleteCall, checkpoint)
        assertTrue(raw.flatMap { it.content }.none { it.state == Conversation.Message.BlockState.STREAMING })
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(raw)
        assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
    }

    @Test fun `full and selective coverage of completed parallel groups stays valid`() = runBlocking<Unit> {
        for (coverage in Conversation.Message.ContentItem.ContextCompactionResult.Coverage.entries) {
            val f = Fixture()
            val calls = f.incompleteCall.copy(content = listOf(toolCall("a"), toolCall("b")))
            val first = f.user.copy(id = Conversation.Message.Id("result-a"), content = listOf(toolResult("a")))
            val second = f.user.copy(id = Conversation.Message.Id("result-b"), content = listOf(toolResult("b")))
            val checkpoint = f.user.copy(id = Conversation.Message.Id("completed-checkpoint"), content = listOf(
                summary(listOf(calls.id, first.id, second.id), coverage)))
            Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user, calls, first, second, checkpoint))
            val snapshot = f.capture()
            val expected = if (coverage == Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS)
                listOf(checkpoint.id) else listOf(f.user.id, checkpoint.id)
            assertEquals(expected, snapshot.request.messages.map { it.id })
            assertTrue(snapshot.request.messages.flatMap { it.content }.none {
                it is Conversation.Message.ContentItem.ToolCall || it is Conversation.Message.ContentItem.ToolResult })
            assertEquals(0, snapshot.omittedTrailingMessageCount)
        }
    }

    @Test fun `native fork fixture passes through production capture and isolated invoker without material loss`() = runBlocking<Unit> {
        val f = Fixture(nativeClaude = true)
        fun user(id: String, text: String) = f.user.copy(id = Conversation.Message.Id(id),
            content = listOf(Conversation.Message.ContentItem.UserMessage(text)))
        val preNative = user("pre-native", "PRE_NATIVE_SOURCE_SENTINEL")
        val checkpoint = user("checkpoint", "").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(
            summary(listOf(preNative.id), Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS, native = true)))
        val selected = user("selected-source", "SELECTIVE_SOURCE_SENTINEL")
        val retained = user("retained", "UNRELATED_RETAINED_SENTINEL")
        val calls = f.incompleteCall.copy(id = Conversation.Message.Id("calls"),
            content = listOf(Conversation.Message.ContentItem.Thinking("CANONICAL_PRIVATE_THINKING_SENTINEL"), toolCall("a"), toolCall("b")),
            providerMetadata = buildJsonObject { put(AI_REPLAY_THREAD_METADATA_KEY, "original-thread") })
        val first = user("result-a", "").copy(content = listOf(toolResult("a")))
        val second = user("result-b", "").copy(content = listOf(toolResult("b")))
        val readable = user("summary", "").copy(content = listOf(
            summary(listOf(selected.id), Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val tail = user("tail", "CAPTURE_TAIL_SENTINEL")
        val originals = listOf(preNative, checkpoint, selected, retained, calls, first, second, readable, tail)
            .map { it.copy(conversationId = Conversation.Id("source-before-fork")) }
        // The persisted post-fork shape: canonical message/coverage IDs change; native transcript UUIDs do not.
        val ids = originals.associate { it.id to Conversation.Message.Id("fork-${it.id.value}") }
        val forked = originals.map { message -> message.copy(id = ids.getValue(message.id), conversationId = f.conversation.id,
            content = message.content.map { item -> if (item is Conversation.Message.ContentItem.ContextCompactionResult) item.copy(
                sourceMessageIds = item.sourceMessageIds.map(ids::getValue),
                invalidatedReplayMessageIds = item.invalidatedReplayMessageIds.map { ids[it] ?: it },
            ) else item }) }
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(forked)
        val snapshot = f.capture()
        val expectedIds = listOf("fork-checkpoint", "fork-retained", "fork-calls", "fork-result-a", "fork-result-b", "fork-summary", "fork-tail")
        assertEquals(expectedIds, snapshot.request.messages.map { it.id.value })
        val (sent, answer) = invokeOnce(snapshot, f.configuration)
        assertEquals("Not enough information", answer)
        val history = sent.messages.dropLast(1)
        assertEquals(expectedIds, history.map { it.id.value })
        val content = history.flatMap { it.content }
        val checkpoints = content.filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()
        assertEquals(nativeState(), assertIs<Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState>(checkpoints.first().payload).state)
        assertEquals(listOf(Conversation.Message.Id("fork-selected-source")), checkpoints.last().sourceMessageIds)
        assertEquals(listOf("a", "b"), content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>().map { it.id.value })
        assertEquals(listOf("a", "b"), content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>().map { it.toolUseId.value })
        assertEquals(history, completeToolGroupPrefix(history))
        assertTrue(content.none { it is Conversation.Message.ContentItem.Thinking })
        assertFalse("PRE_NATIVE_SOURCE_SENTINEL" in content.toString())
        assertFalse("SELECTIVE_SOURCE_SENTINEL" in content.toString())
        assertEquals(AI_CONTEXT_QUERY_PURPOSE, sent.options.usagePurpose)
        assertEquals(AiToolChoice.None, sent.options.toolChoice)
        assertTrue(sent.tools.isEmpty())
        assertFalse(TOOL_CONTEXT_THREAD_ID in sent.options.toolContext)
        assertTrue(sent.messages.all { it.conversationId.value.startsWith("context-query:") })
        assertEquals(9, forked.size)
        assertEquals(listOf(selected.id), (readable.content.single() as Conversation.Message.ContentItem.ContextCompactionResult).sourceMessageIds)
    }

    @Test fun `ordinary and snapshot assemblers share the unchanged ordered system prefix`() {
        val f = Fixture()
        for (format in AiModelConfiguration.AssistantResponseFormat.entries) {
            val result = assembleConversationSystemPrompts(listOf("first", "second"), f.agent, "catalog", "env", "skills", format, false)
            assertEquals(listOf("first", "second"), result.take(2))
            assertEquals("""
                <current_agent>
                Current server-managed Agent identity (JSON data): {"agent_definition_id":"agent","model_configuration_id":"model"}
                Use `agent_definition_id` when a tool asks for the current Agent id.
                </current_agent>
            """.trimIndent(), result[2])
            assertEquals(listOf("catalog", "env", "skills"), result.subList(3, 6))
            assertEquals(listOfNotNull(AssistantResponseFormatContract.instruction(format, false)), result.drop(6))
        }
    }

    private fun toolCall(id: String) = Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id(id),
        Conversation.Message.ContentItem.ToolCall.Data("synthetic_read", buildJsonObject { put("sentinel", "CALL_${id.uppercase()}_SENTINEL") }))
    private fun toolResult(id: String) = Conversation.Message.ContentItem.ToolResult(Conversation.Message.ContentItem.ToolCall.Id(id),
        "synthetic_read", listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("RESULT_${id.uppercase()}_SENTINEL")))

    private fun summary(ids: List<Conversation.Message.Id>, coverage: Conversation.Message.ContentItem.ContextCompactionResult.Coverage,
        native: Boolean = false) = Conversation.Message.ContentItem.ContextCompactionResult(
        payload = if (native) Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(nativeState())
            else Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("READABLE_SUMMARY_SENTINEL"),
        origin = if (native) Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO
            else Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
        providerScope = if (native) Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(AiConnection.Kind.CLAUDE_CODE.name) else null,
        coverage = coverage, sourceMessageIds = ids,
    )

    // CTX_QUERY_NATIVE_FORK_V1: the consumer half is ClaudeCodeCliRuntimeTest's isolated query reconstruction test.
    private fun nativeState() = Json.parseToJsonElement("""{"kind":"claude_code_transcript","messages":[{"uuid":"provider-summary","type":"user","isSynthetic":true,"message":{"role":"user","content":"NATIVE_SUMMARY_SENTINEL"}},{"uuid":"provider-tail","type":"user","message":{"role":"user","content":[{"type":"text","text":"NATIVE_RETAINED_TAIL_SENTINEL"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}},{"type":"thinking","thinking":"OPAQUE_PRIVATE_THINKING_SENTINEL"}]}}]}""").jsonObject

    private suspend fun invokeOnce(snapshot: ConversationContextSnapshot, configuration: AiConfigurationProvider): Pair<AiRuntimeRequest, String> {
        var sent: AiRuntimeRequest? = null
        var calls = 0
        val provider = object : AiRuntimeProvider {
            override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime {
                assertEquals(snapshot.runtimeSelection, selection)
                assertNull(workspaceRootPath)
                return object : AiRuntime {
                    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                        calls++
                        sent = request
                        return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(Conversation.Message.ContentItem.AssistantMessage(
                            Conversation.Message.StructuredText("Not enough information"))))))
                    }
                    override fun stream(request: AiRuntimeRequest): kotlinx.coroutines.flow.Flow<AiRuntimeResponse> = error("No streaming/turn loop")
                }
            }
        }
        val answer = IsolatedContextQueryInvoker(provider, configuration).answer(snapshot, "ISOLATED_QUESTION_SENTINEL: What does this snapshot establish?")
        assertEquals(1, calls)
        return requireNotNull(sent) to answer
    }

    @Test fun `pins are the last application block and query keeps the target owner under synthetic invocation`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        f.pins.values = listOf(testPin(f.conversation.id, "  PIN_TARGET_A\n", 2), testPin(f.conversation.id, "PIN_TARGET_B", 1))
        val snapshot = f.capture()
        val block = snapshot.request.systemPrompts.last()
        assertTrue(block.indexOf("PIN_TARGET_B") < block.indexOf("  PIN_TARGET_A\n"))
        assertEquals("read-only-runtime", snapshot.request.systemPrompts[snapshot.request.systemPrompts.lastIndex - 1])
        assertFalse(block.contains("PRIVATE_"))
        f.pins.values = emptyList()
        val (sent, _) = invokeOnce(snapshot, f.configuration)
        assertEquals(block, sent.systemPrompts.last())
        assertEquals(listOf(f.conversation.id), f.pins.reads)
        assertNotEquals(f.conversation.id.value, sent.options.toolContext[TOOL_CONTEXT_CONVERSATION_ID])
        assertTrue(sent.tools.isEmpty())
        assertEquals(AiToolChoice.None, sent.options.toolChoice)
        assertNull(sent.options.autoCompactionThresholdTokens)
        assertEquals(snapshot.request.systemPrompts.dropLast(1), f.capture().request.systemPrompts)
    }

    @Test fun `pin read error is content free and does not produce an empty snapshot`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        f.pins.failure = IllegalStateException("PRIVATE_PIN_TEXT PRIVATE_CREDENTIAL")
        val error = assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
        assertFalse(error.toString().contains("PRIVATE_")); assertNull(error.cause)
    }

    private class Fixture(nativeClaude: Boolean = false) {
        val now = Instant.fromEpochMilliseconds(0)
        val actor = User.Id("operator")
        val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
        val agent = AgentDefinition(AgentDefinition.Id("agent"), name = "Agent", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global,
            createdAt = now, updatedAt = now)
        val conversation = Conversation(Conversation.Id("target"), project.id,
            setOf(Conversation.Participant.User(actor), Conversation.Participant.Agent(agent.id)),
            currentThread = Conversation.Thread.Id("pinned"), createdAt = now, updatedAt = now)
        val target = AgentEndpoint(conversation.id, conversation.currentThread, agent.id)
        val user = Conversation.Message(Conversation.Message.Id("user"), conversation.id, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("history")), createdAt = now)
        val incompleteCall = user.copy(id = Conversation.Message.Id("pending"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("tool"),
                Conversation.Message.ContentItem.ToolCall.Data("read", JsonObject(emptyMap())))))
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val history = Mockito.mock(ThreadMessageRepository::class.java)
        val configuration = Mockito.mock(AiConfigurationProvider::class.java)
        val prompts = Mockito.mock(AgentPromptAssemblyService::class.java)
        val settings = Mockito.mock(SettingsProvider::class.java)
        val catalog = Mockito.mock(DistributedAiToolCatalog::class.java)
        val capabilities = Mockito.mock(AiToolCapabilityCatalogService::class.java)
        val skills = Mockito.mock(AgentSkillRuntimeCatalogService::class.java)
        val artifactRepository = Mockito.mock(ArtifactRepository::class.java)
        val contentStore = Mockito.mock(ArtifactContentStore::class.java)
        val pins = ReadOnlyTestPins()
        val baseCatalog = DistributedAiToolCatalogSnapshot(emptyList(), emptyMap(), emptyList(), "revision", "environment")
        var snapshotEnrichments = 0
        val enricher = object : ConversationRequestEnricher {
            override suspend fun enrich(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest = error("Mutable enrichment forbidden")
            override suspend fun enrichSnapshot(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest {
                snapshotEnrichments++
                assertEquals(conversation.id, conversationId)
                return request.copy(systemPrompts = request.systemPrompts + "read-only-runtime")
            }
        }
        init {
            val connection = if (nativeClaude) AiConnection.ClaudeCode(AiConnection.Id("connection"), "Connection", enabled = true, executionTarget = AiExecutionTarget.Worker("synthetic-native-worker"))
                else AiConnection.OpenAiSubscription(AiConnection.Id("connection"), "Connection", enabled = true)
            val model = AiModelConfiguration(agent.runtimeSelection.modelConfigurationId, connection.id, "test", "Test")
            val resolved = ResolvedAiRuntime(connection, model, AiModelSpec("test", connection.kind.provider,
                setOf(AiModelCapability.TEXT_GENERATION, AiModelCapability.IMAGE_INPUT), AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(100_000))))
            Mockito.`when`(configuration.resolveAiRuntime(agent.runtimeSelection)).thenReturn(resolved)
            Mockito.`when`(settings.userProfile).thenReturn(UserProfile())
            runBlocking {
                Mockito.`when`(conversations.getProject(conversation.id)).thenReturn(project)
                Mockito.`when`(catalog.readSnapshot(project, agent.toolAccess, conversation)).thenReturn(baseCatalog)
                Mockito.`when`(skills.prepare(agent, project.id, baseCatalog)).thenReturn(AgentSkillRuntimeCatalog(baseCatalog, "skills"))
                Mockito.`when`(capabilities.cachedPromptFor(emptyList())).thenReturn("catalog")
                Mockito.`when`(prompts.assembleSystemPrompt(agent, RuntimeEnvironmentContext.ProjectBound(project,
                    RuntimeEnvironmentExecutor.Server, RuntimeEnvironmentContext.ConversationInfo(conversation.id, conversation.displayName))))
                    .thenReturn(listOf("base"))
            }
        }
        suspend fun capture() = ConversationContextSnapshotService(conversations, history, configuration, prompts, settings,
            catalog, skills, capabilities, AiToolRuntimeCatalogService(), MessageTemporalContextService(), StickyMessageInstructionService(),
            ConversationArtifactApplicationService(artifactRepository, contentStore), ConversationPinnedPrompt(pins), listOf(enricher)).capture(target, conversation, agent, actor)
    }
}
