package com.gromozeka.presentation.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.AgentPreloadedTools
import com.gromozeka.domain.tool.ToolAccessPolicy
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ConversationRuntimePanelUiTest {
    private val translation = EnglishTranslation()

    @Test
    fun configurationSurvivesActiveTaskGapsAndIdle() = runComposeUiTest {
        val fixture = Fixture()
        val llm = fixture.llmTask(fixture.alpha)
        val tools = fixture.toolTask(fixture.alpha)
        val runtime = mutableStateOf<ConversationRuntimeSnapshot?>(fixture.snapshot(active = llm))
        setContent { fixture.Panel(runtime.value) }
        onNodeWithText("Model A").assertExists()
        onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assertExists()
        for (next in listOf(fixture.snapshot(continuation = tools), fixture.snapshot(active = tools), fixture.snapshot(), null)) {
            runOnIdle { runtime.value = next }
            onNodeWithText("Model A").assertExists()
            onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
            onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assertExists()
        }
        runOnIdle {
            assertEquals(0, fixture.agentService.singleAgentLookups)
            assertEquals(1, fixture.agentService.observations)
        }
    }

    @Test
    fun inspectingBetaDoesNotFollowAlphasWork() = runComposeUiTest {
        val fixture = Fixture()
        val runtime = mutableStateOf(fixture.snapshot(active = fixture.llmTask(fixture.alpha)))
        setContent { fixture.Panel(runtime.value) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        onNodeWithText("Model B").assertExists()
        onNodeWithTag(UiTestTag.RuntimeAgentActivity.value).assertTextEquals(translation.runtime.readyStatus)
        runOnIdle { runtime.value = fixture.snapshot(active = fixture.toolTask(fixture.alpha)) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        onNodeWithText("Model B").assertExists()
        onNodeWithTag(UiTestTag.RuntimeAgentActivity.value).assertTextEquals(translation.runtime.readyStatus)
        runOnIdle { runtime.value = fixture.snapshot(active = fixture.llmTask(fixture.beta)) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        onNodeWithTag(UiTestTag.RuntimeAgentActivity.value).assertTextEquals(translation.runtime.modelRequestStatus)
    }

    @Test
    fun zeroOneAndManyParticipantsAndSelectedRemovalAreHandled() = runComposeUiTest {
        val fixture = Fixture()
        val participants = mutableStateOf(fixture.connected())
        setContent { fixture.Panel(null, participants = participants.value) }
        onNodeWithText(translation.text("session.runtime.noConnectedAgents")).assertExists()
        onNodeWithTag(UiTestTag.RuntimeAgentTabs.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assertExists()
        runOnIdle { participants.value = fixture.connected(fixture.alpha) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertDoesNotExist()
        runOnIdle { participants.value = fixture.connected(fixture.alpha, fixture.beta) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        onNodeWithText("Model B").assertExists()
        runOnIdle { participants.value = fixture.connected(fixture.alpha) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
        onNodeWithText("Model A").assertExists()
        runOnIdle { participants.value = fixture.connected(fixture.alpha, fixture.beta) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
    }

    @Test
    fun selectionSurvivesConversationSwitchesVisibilityAndTemporaryLoading() = runComposeUiTest {
        val fixture = Fixture()
        val conversation = mutableStateOf(fixture.conversationId)
        val participants = mutableStateOf<Set<Conversation.Participant>?>(fixture.connected(fixture.alpha, fixture.beta))
        val visible = mutableStateOf(true)
        val fullScreen = mutableStateOf(false)
        // Keep an old snapshot while switching: it must not be attributed to the new conversation.
        val oldRuntime = fixture.snapshot(active = fixture.llmTask(fixture.alpha))
        setContent {
            key(conversation.value) {
                fixture.Panel(oldRuntime, conversation.value, participants.value, visible.value, fullScreen.value)
            }
        }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        runOnIdle { conversation.value = Conversation.Id("conversation-b") }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
        onNodeWithTag(UiTestTag.RuntimeAgentActivity.value).assertTextEquals(translation.runtime.readyStatus)
        runOnIdle { conversation.value = fixture.conversationId }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        runOnIdle { participants.value = null }
        onNodeWithTag(UiTestTag.RuntimeAgentTabs.value).assertDoesNotExist()
        runOnIdle { participants.value = fixture.connected(fixture.alpha, fixture.beta) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        runOnIdle { visible.value = false }
        onNodeWithTag(UiTestTag.RuntimeAgentTabs.value).assertDoesNotExist()
        runOnIdle { visible.value = true; fullScreen.value = true }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        onNodeWithText("Model B").assertExists()
    }

    @Test
    fun slowSharedQuotasDoNotBlockOrRestartWhenSwitchingAgents() = runComposeUiTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.quotaGate = gate
        val runtime = mutableStateOf(fixture.snapshot(active = fixture.llmTask(fixture.alpha)))
        setContent { fixture.Panel(runtime.value) }
        onNodeWithText("Model A").assertExists()
        runOnIdle { assertEquals(1, fixture.quotaReads.size) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        onNodeWithText("Model B").assertExists()
        runOnIdle { runtime.value = fixture.snapshot(continuation = fixture.toolTask(fixture.alpha)) }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).assertIsSelected()
        runOnIdle {
            assertEquals(1, fixture.quotaReads.size)
            assertEquals(0, fixture.cancelledQuotaReads)
            gate.complete(Unit)
        }
        onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assertExists()
        onNodeWithText("Model B").assertExists()
    }

    @Test
    fun sharedQuotasCoverAllParticipantsNotJustSelectedAgent() = runComposeUiTest {
        val fixture = Fixture(separateBetaModel = true)
        var readsBeforeSwitch = 0
        setContent { fixture.Panel(null) }
        onNodeWithText("Model A").assertExists()
        runOnIdle {
            assertEquals(setOf("model-a", "model-b"), fixture.quotaReads.map { it.value }.toSet())
            readsBeforeSwitch = fixture.quotaReads.size
        }
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        onNodeWithText("Model B").assertExists()
        runOnIdle { assertEquals(readsBeforeSwitch, fixture.quotaReads.size) }
    }

    @Test
    fun catalogUpdatesRefreshConfigurationWithoutChangingSelection() = runComposeUiTest {
        val fixture = Fixture()
        setContent { fixture.Panel(null) }
        onNodeWithText("Model A").assertExists()
        runOnIdle { fixture.agentService.agents.value = emptyList() }
        onNodeWithText(translation.text("session.participants.unavailableAgent")).assertExists()
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
        runOnIdle {
            fixture.agentService.agents.value = listOf(
                fixture.alpha.copy(name = "Renamed alpha", runtimeSelection = fixture.beta.runtimeSelection),
                fixture.beta,
            )
        }
        onNodeWithText("Model B").assertExists()
        onNodeWithTag(UiTestTag.RuntimeAgentTab("alpha").value).assertIsSelected()
    }

    @Test
    fun agentUsageUsesExplicitAttributionAndSharedTotalsStayOutsideTabs() = runComposeUiTest {
        val fixture = Fixture()
        val stats = fixture.totals(listOf(
            fixture.call("a", fixture.alpha.id, 100, 10),
            fixture.call("b", fixture.beta.id, 200, 20),
            fixture.call("legacy", null, 999, 0),
        ))
        setContent { fixture.Panel(null, tokenStats = stats) }
        val agentUsage = hasAnyAncestor(hasTestTag(UiTestTag.RuntimeAgentUsage.value))
        onNode(hasText(translation.text("session.runtime.context.lastUsage", "count" to "110")) and agentUsage).assertExists()
        onNode(hasText(translation.text("session.runtime.context.lastUsage", "count" to "220")) and agentUsage).assertDoesNotExist()
        onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assert(hasAnyDescendant(hasText("1,329", substring = true)))
        onNodeWithTag(UiTestTag.RuntimeAgentTab("beta").value).performClick()
        onNode(hasText(translation.text("session.runtime.context.lastUsage", "count" to "220")) and agentUsage).assertExists()
        onNode(hasText(translation.text("session.runtime.context.lastUsage", "count" to "110")) and agentUsage).assertDoesNotExist()
        onNodeWithTag(UiTestTag.RuntimeSharedUsage.value).assert(hasAnyDescendant(hasText("1,329", substring = true)))
    }

    @Test
    fun latestAgentCallIgnoresOtherConversationsAndUnattributedRecords() {
        val fixture = Fixture()
        val own = fixture.call("own", fixture.alpha.id, 100, 10)
        val foreign = fixture.call("foreign", fixture.alpha.id, 900, 0).copy(
            conversationId = Conversation.Id("another-conversation"), timestamp = Instant.parse("2026-09-14T00:00:00Z"),
        )
        val unassigned = fixture.call("unassigned", null, 999, 0).copy(timestamp = foreign.timestamp)
        val stats = fixture.totals(listOf(own, foreign, unassigned))
        assertEquals(own, runtimeLastAgentCall(fixture.conversationId, fixture.alpha.id, stats))
        assertEquals(null, runtimeLastAgentCall(fixture.conversationId, fixture.beta.id, stats))
    }

    private class Fixture(separateBetaModel: Boolean = false) {
        val conversationId = Conversation.Id("conversation-a")
        private val now = Instant.parse("2026-09-13T00:00:00Z")
        val tabSelection = RuntimeAgentTabSelection()
        private val connection = AiConnection.OpenAiSubscription(
            id = AiConnection.Id("subscription"), displayName = "Test subscription", enabled = true,
        )
        private val configurationA = AiModelConfiguration(
            id = AiModelConfiguration.Id("model-a"), connectionId = connection.id,
            providerModelId = "test-model", displayName = "Model A",
        )
        private val configurationB = configurationA.copy(
            id = AiModelConfiguration.Id("model-b"), displayName = "Model B",
            providerModelId = if (separateBetaModel) "beta-model" else "test-model",
        )
        val alpha = agent("alpha", "Alpha", configurationA.id)
        val beta = agent("beta", "Beta", configurationB.id)
        val agentService = TestAgentService(listOf(alpha, beta))
        private val catalog = AiCatalog(
            connections = listOf(connection),
            modelSpecs = listOf(configurationA, configurationB).distinctBy { it.providerModelId }.map {
                AiModelSpec(
                    id = it.providerModelId, provider = connection.kind.provider,
                    capabilities = setOf(
                        AiModelCapability.TEXT_GENERATION, AiModelCapability.EMBEDDINGS,
                        AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.TEXT_TO_SPEECH,
                    ),
                    limits = AiModelSpec.Limits(
                        textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 100_000),
                        embeddings = AiModelSpec.Limits.Embeddings(dimensions = 16),
                    ),
                )
            },
            modelConfigurations = listOf(configurationA, configurationB),
            runtimeAssignments = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map {
                AiRuntimeAssignment(it, AiRuntimeSelection(configurationA.id))
            },
            defaultAgentId = alpha.id,
        )
        private val provider = object : AiConfigurationProvider {
            override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(AiCatalogSnapshot(this@Fixture.catalog, 0))
            override val snapshot: AiCatalogSnapshot get() = requireNotNull(snapshotFlow.value)
            override fun resolveAiRuntime(selection: AiRuntimeSelection) = catalog.resolveRuntime(selection)
        }
        var quotaGate: CompletableDeferred<Unit>? = null
        val quotaReads = mutableListOf<AiModelConfiguration.Id>()
        var cancelledQuotaReads = 0
        private val quotaService = object : AiSubscriptionQuotaService {
            override suspend fun read(modelConfigurationId: AiModelConfiguration.Id, forceRefresh: Boolean): AiSubscriptionQuotaObservation {
                quotaReads += modelConfigurationId
                try {
                    quotaGate?.await()
                } catch (cancelled: CancellationException) {
                    cancelledQuotaReads++
                    throw cancelled
                }
                return AiSubscriptionQuotaObservation(
                    modelConfigurationId = modelConfigurationId, connectionId = connection.id,
                    connectionDisplayName = connection.displayName, connectionKind = connection.kind,
                    providerModelId = catalog.modelConfigurations.single { it.id == modelConfigurationId }.providerModelId,
                    status = AiSubscriptionQuotaObservation.Status.UNAVAILABLE,
                )
            }
        }

        @Composable
        fun Panel(
            runtime: ConversationRuntimeSnapshot?,
            conversationId: Conversation.Id = this.conversationId,
            participants: Set<Conversation.Participant>? = connected(alpha, beta),
            isVisible: Boolean = true,
            fullScreen: Boolean = false,
            tokenStats: TokenUsageStatistics.ThreadTotals? = null,
        ) {
            MaterialTheme {
                ConversationRuntimePanel(
                    isVisible = isVisible,
                    conversationId = conversationId,
                    participants = participants,
                    tabSelection = tabSelection,
                    agentService = agentService,
                    aiConfigurationProvider = provider,
                    aiSubscriptionQuotaService = quotaService,
                    tokenStats = tokenStats,
                    isWaitingForResponse = runtime?.activeTask != null || runtime?.continuationTask != null,
                    executionPauseRequested = false,
                    pttState = PttState.IDLE,
                    pttStatusMessage = null,
                    pendingMessages = emptyList(),
                    runtimeSnapshot = runtime,
                    activeGeneration = null,
                    remoteConnectionState = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED),
                    onPause = {}, onResume = {}, onStop = {},
                    onCancelCommandTask = {}, onCancelCommandMonitor = {},
                    onSendInCurrentTurn = {}, onEditPendingMessage = {}, onCancelPendingMessage = {}, onClose = {},
                    fullScreen = fullScreen,
                )
            }
        }

        fun connected(vararg agents: AgentDefinition): Set<Conversation.Participant> = buildSet {
            add(Conversation.Participant.User(User.Id("viewer")))
            agents.forEach { add(Conversation.Participant.Agent(it.id)) }
        }

        fun snapshot(
            active: ConversationRuntimeTask? = null,
            continuation: ConversationRuntimeTask? = null,
        ) = ConversationRuntimeSnapshot(
            revision = 1, conversationId = conversationId, state = null,
            activeTask = active, continuationTask = continuation, pendingTasks = emptyList(),
        )

        fun llmTask(agent: AgentDefinition) = task(
            "llm-${agent.id.value}",
            ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), agent.id, 1),
        )

        fun toolTask(agent: AgentDefinition): ConversationRuntimeTask {
            val call = Conversation.Message.ContentItem.ToolCall(
                id = Conversation.Message.ContentItem.ToolCall.Id("tool-call"),
                call = Conversation.Message.ContentItem.ToolCall.Data("test_tool", JsonObject(emptyMap())),
            )
            return task(
                "tools-${agent.id.value}",
                ConversationRuntimeTask.Payload.ToolExecution(
                    rootUserMessageId = Conversation.Message.Id("root"), agentDefinitionId = agent.id,
                    iteration = 1, toolCalls = listOf(call), returnDirect = false,
                    executionTargetsByCallId = mapOf(call.id.value to ConversationRuntimeTaskTarget.Server),
                ),
            )
        }

        fun call(id: String, agentId: AgentDefinition.Id?, prompt: Int, completion: Int) = TokenUsageStatistics(
            id = TokenUsageStatistics.Id(id), timestamp = now,
            promptTokens = prompt, completionTokens = completion,
            provider = connection.kind.provider.name, modelId = "test-model",
            agentDefinitionId = agentId, conversationId = conversationId,
        )

        fun totals(calls: List<TokenUsageStatistics>) = TokenUsageStatistics.ThreadTotals(
            totalPromptTokens = calls.sumOf { it.promptTokens },
            totalCompletionTokens = calls.sumOf { it.completionTokens },
            totalCacheReadTokens = 0, totalCacheCreationTokens = 0, totalThinkingTokens = 0,
            lastCallTokens = calls.lastOrNull()?.totalTokens, recentCalls = calls,
        )

        private fun task(id: String, payload: ConversationRuntimeTask.Payload) = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(id), conversationId = conversationId,
            parentTaskId = ConversationRuntimeTask.Id("parent"), payload = payload,
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id,
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = ConversationRuntimeCapability.entries.toSet(), target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = now,
        )

        private fun agent(id: String, name: String, modelId: AiModelConfiguration.Id) = AgentDefinition(
            id = AgentDefinition.Id(id), name = name, prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(modelId), type = AgentDefinition.Type.Global,
            createdAt = now, updatedAt = now,
        )
    }

    private class TestAgentService(initial: List<AgentDefinition>) : AgentDomainService {
        val agents = MutableStateFlow(initial)
        var singleAgentLookups = 0
        var observations = 0
        override fun observeAll(): MutableStateFlow<List<AgentDefinition>> {
            observations++
            return agents
        }
        override suspend fun findAll() = agents.value
        override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? {
            singleAgentLookups++
            return agents.value.firstOrNull { it.id == id }
        }
        override suspend fun findByProject(projectId: Project.Id) = agents.value.filter { it.projectId == projectId }
        override suspend fun count() = agents.value.size
        override suspend fun toolCatalog(projectId: Project.Id?) = emptyList<com.gromozeka.domain.tool.AgentToolCatalogEntry>()
        override suspend fun createAgent(
            projectId: Project.Id?, name: String, prompts: List<Prompt.Id>, runtimeSelection: AiRuntimeSelection,
            runtimeOverrides: AiRuntimeOverrides, tools: AgentPreloadedTools, description: String?,
            skills: List<AgentSkill.Id>, toolAccess: ToolAccessPolicy,
        ): AgentDefinition = error("UI inspection must not create an agent")
        override suspend fun duplicateAgent(
            projectId: Project.Id?, sourceAgentId: AgentDefinition.Id, name: String,
        ): AgentDefinition = error("UI inspection must not duplicate an agent")
        override suspend fun update(
            id: AgentDefinition.Id, name: String, prompts: List<Prompt.Id>, description: String?,
            skills: List<AgentSkill.Id>, runtimeSelection: AiRuntimeSelection, runtimeOverrides: AiRuntimeOverrides,
            tools: AgentPreloadedTools, toolAccess: ToolAccessPolicy,
        ): AgentDefinition = error("UI inspection must not update an agent")
        override suspend fun delete(id: AgentDefinition.Id): Unit = error("UI inspection must not delete an agent")
    }
}
