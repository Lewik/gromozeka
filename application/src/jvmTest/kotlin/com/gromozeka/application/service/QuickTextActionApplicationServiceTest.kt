package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.RuntimeEnvironmentExecutor
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.service.AgentPromptAssemblyService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class QuickTextActionApplicationServiceTest {
    @Test
    fun `selected global Agent controls runtime prompts and overrides`() = runBlocking {
        val selection = AiRuntimeSelection(AiModelConfiguration.Id("agent-model"))
        val reasoning = AiReasoningConfig(effort = AiReasoningEffort.HIGH)
        val expectedAgent = AgentDefinition(
            id = AgentDefinition.Id("translator"),
            name = "Translator",
            prompts = emptyList(),
            runtimeSelection = selection,
            runtimeOverrides = AiRuntimeOverrides(maxOutputTokens = 2_048, reasoning = reasoning),
            type = AgentDefinition.Type.Global,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        val action = QuickTextAction.defaults().first().copy(agentId = expectedAgent.id)
        val runtimeProvider = QuickActionRecordingRuntimeProvider(selection)
        var assembledContext: RuntimeEnvironmentContext? = null
        val service = QuickTextActionApplicationService(
            aiRuntimeProvider = runtimeProvider,
            aiConfigurationProvider = QuickActionConfigurationProvider(selection),
            settingsProvider = QuickActionSettingsProvider(action),
            agentRepository = QuickActionAgentRepository(expectedAgent),
            agentPromptAssemblyService = object : AgentPromptAssemblyService {
                override suspend fun assembleSystemPrompt(
                    agent: AgentDefinition,
                    runtimeContext: RuntimeEnvironmentContext,
                ): List<String> {
                    assertSame(expectedAgent, agent)
                    assembledContext = runtimeContext
                    return listOf("Use concise professional English.")
                }
            },
        )

        val result = service.runAction(action.id, "helo")

        assertEquals("hello", result.text)
        assertEquals(selection, runtimeProvider.selection)
        val request = runtimeProvider.request ?: error("Runtime was not called")
        assertContains(request.systemPrompts, "Use concise professional English.")
        assertEquals(2_048, request.options.maxOutputTokens)
        assertEquals(reasoning, request.options.reasoning)
        assertEquals(AiToolChoice.None, request.options.toolChoice)
        assertEquals(expectedAgent.id.value, request.options.toolContext["agentDefinitionId"])
        assertEquals(
            RuntimeEnvironmentExecutor.Server,
            assertIs<RuntimeEnvironmentContext.Standalone>(assembledContext).executor,
        )
    }
}

private class QuickActionRecordingRuntimeProvider(
    private val expectedSelection: AiRuntimeSelection,
) : AiRuntimeProvider {
    var selection: AiRuntimeSelection? = null
    var request: AiRuntimeRequest? = null

    override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime {
        assertEquals(expectedSelection, selection)
        assertEquals(null, workspaceRootPath)
        this.selection = selection
        return object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                this@QuickActionRecordingRuntimeProvider.request = request
                return AiRuntimeResponse(
                    messages = listOf(
                        AiAssistantMessage(
                            content = listOf(
                                Conversation.Message.ContentItem.AssistantMessage(
                                    Conversation.Message.StructuredText(fullText = "hello"),
                                ),
                            ),
                        ),
                    ),
                )
            }

            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
        }
    }
}

private class QuickActionConfigurationProvider(
    private val selection: AiRuntimeSelection,
) : AiConfigurationProvider {
    private val connection = AiConnection.OpenAiSubscription(
        id = AiConnection.Id("subscription"),
        displayName = "Subscription",
        enabled = true,
        executionTarget = AiExecutionTarget.Server,
    )
    private val configuration = AiModelConfiguration(
        id = selection.modelConfigurationId,
        connectionId = connection.id,
        providerModelId = "gpt-test",
        displayName = "Test model",
    )
    private val resolved = ResolvedAiRuntime(
        connection = connection,
        modelConfiguration = configuration,
        modelSpec = AiModelSpec(
            id = configuration.providerModelId,
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.TEXT_GENERATION),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 10_000),
            ),
        ),
    )

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = MutableStateFlow(null)
    override val snapshot: AiCatalogSnapshot
        get() = error("Not used")

    override fun resolveAiRuntimeIfAvailable(selection: AiRuntimeSelection): ResolvedAiRuntime? {
        assertEquals(this.selection, selection)
        return resolved
    }

    override fun requireAvailableRuntimeSelectionFor(
        purpose: AiRuntimeAssignment.Purpose,
    ): AiRuntimeSelection = error("Agent runtime must be used")

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime = resolved
}

private class QuickActionSettingsProvider(action: QuickTextAction) : SettingsProvider {
    override val userProfile = UserProfile(quickTextActions = listOf(action))
    override val userDeviceSettings = UserDeviceSettings.Desktop()
    override val mode = AppMode.TEST
    override val homeDirectory = "/tmp/gromozeka-test"
}

private class QuickActionAgentRepository(
    agent: AgentDefinition,
) : AgentRepository {
    private val agents = mutableMapOf(agent.id to agent)

    override suspend fun save(agent: AgentDefinition): AgentDefinition = agent.also { agents[it.id] = it }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? = agents[id]

    override suspend fun findAll(): List<AgentDefinition> = agents.values.toList()

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        agents.values.filter { it.projectId == null || it.projectId == projectId }

    override suspend fun delete(id: AgentDefinition.Id) {
        agents.remove(id)
    }

    override suspend fun count(): Int = agents.size
}
