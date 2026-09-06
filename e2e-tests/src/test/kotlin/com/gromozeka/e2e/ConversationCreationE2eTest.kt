package com.gromozeka.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.ui.UiTestTag
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationCreationE2eTest {
    @Test
    fun persistsAutomaticRespondersAndInvokesEachAgentOnce() {
        val requests = AtomicInteger()
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val number = requests.incrementAndGet()
            val response = """{"id":"fixture-$number","object":"chat.completion","created":0,"model":"auto-fixture","choices":[{"index":0,"message":{"role":"assistant","content":"Automatic reply $number"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        provider.start()
        try {
            runGromozekaUiTest("automatic-responders", isolatedServer = true) { client ->
                val components = client.components
                val (conversation, agents) = runBlocking {
                    val catalog = components.aiConfigurationService.catalog
                    val defaultAgent = checkNotNull(components.agentService.findById(catalog.defaultAgentId))
                    val configurationId = defaultAgent.runtimeSelection.modelConfigurationId
                    val inactiveConfiguration = catalog.modelConfigurations.single { it.id == configurationId }
                        .copy(id = AiModelConfiguration.Id("auto-fixture-unavailable"))
                    check(catalog.connectionFor(inactiveConfiguration)?.enabled == false)
                    val connection = AiConnection.OpenAiCompatible(
                        id = AiConnection.Id("auto-fixture"),
                        displayName = "Automatic response fixture",
                        baseUrl = "http://127.0.0.1:${provider.address.port}/v1",
                    )
                    components.aiConfigurationService.replaceCatalog(catalog.copy(
                        connections = catalog.connections + connection,
                        modelSpecs = catalog.modelSpecs + AiModelSpec(
                            id = "auto-fixture",
                            provider = connection.kind.provider,
                            capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                            limits = AiModelSpec.Limits(
                                textGeneration = AiModelSpec.Limits.TextGeneration(128_000, 1_000),
                            ),
                        ),
                        modelConfigurations = catalog.modelConfigurations.filterNot { it.id == configurationId } + inactiveConfiguration + AiModelConfiguration(
                            id = configurationId,
                            connectionId = connection.id,
                            providerModelId = "auto-fixture",
                            displayName = "Automatic response fixture",
                            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                        ),
                        runtimeAssignments = catalog.runtimeAssignments.map { assignment ->
                            if (assignment.purpose == AiRuntimeAssignment.Purpose.DEFAULT_CHAT ||
                                assignment.purpose == AiRuntimeAssignment.Purpose.TOOL_CATALOG_SUMMARY) {
                                assignment.copy(selection = AiRuntimeSelection(inactiveConfiguration.id))
                            } else assignment
                        },
                    ), secretMutations = listOf(AiCatalogSecretMutation.Set(
                        AiCatalogSecretSlot.ConnectionApiKey(connection.id),
                        SecretRef.Inline("synthetic-e2e-key"),
                    )))
                    val project = components.projectService.create("Automatic responders", "E2E verification")
                    val agents = listOf("Alpha", "Beta").map { name ->
                        components.agentService.createAgent(
                            projectId = project.id,
                            name = name,
                            prompts = defaultAgent.prompts,
                            runtimeSelection = AiRuntimeSelection(configurationId),
                        )
                    }
                    val conversation = components.conversationService.create(
                        projectId = project.id,
                        participants = setOf(
                            Conversation.Participant.User(components.authenticatedUser.id),
                            Conversation.Participant.Agent(agents.first().id),
                        ),
                        displayName = "Automatic replies",
                    )
                    assertEquals(setOf(agents.first().id), conversation.autoRespondAgentIds)
                    components.appViewModel.createTab(
                        projectId = project.id,
                        agent = null,
                        conversationId = conversation.id,
                        initialMessage = null,
                        setAsCurrent = true,
                        initiator = ConversationInitiator.User,
                    )
                    conversation to agents
                }
                waitForTag(UiTestTag.MessageInput)
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodesWithText("Replies: Alpha").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag(UiTestTag.AgentResponseHint.value).assertTextContains("Replies: Alpha")
                onNodeWithTag(UiTestTag.ParticipantsButton.value).performClick()
                waitForTag(UiTestTag.AgentAutoRespond(agents.first().id.value))
                saveScreenshot("automatic-responder-checkbox")
                onNodeWithTag(UiTestTag.AgentAutoRespond(agents.first().id.value).value).performClick()
                waitUntil(timeoutMillis = 30_000) {
                    runBlocking { components.conversationService.findById(conversation.id) }
                        ?.autoRespondAgentIds?.isEmpty() == true
                }
                runBlocking {
                    val unchanged = components.conversationService.updateParticipants(conversation.id, conversation.participants)
                    assertTrue(checkNotNull(unchanged).autoRespondAgentIds.isEmpty())
                    val expanded = components.conversationService.updateParticipants(
                        conversation.id, conversation.participants + Conversation.Participant.Agent(agents.last().id),
                    )
                    assertTrue(checkNotNull(expanded).autoRespondAgentIds.isEmpty())
                    components.conversationService.updateAutoRespondAgentIds(conversation.id, agents.map { it.id }.toSet())
                }
                val tab = components.appViewModel.tabs.value.single { it.conversationId == conversation.id }
                fun awaitMessages(users: Int, assistants: Int) {
                    waitUntil(timeoutMillis = 30_000) {
                        val messages = runBlocking { components.conversationService.loadCurrentMessages(conversation.id) }
                        messages.count { it.role == Conversation.Message.Role.USER } == users &&
                            messages.count { it.role == Conversation.Message.Role.ASSISTANT } == assistants &&
                            tab.runtimeSnapshot.value?.let { it.state == null && it.pendingTasks.isEmpty() } == true
                    }
                }
                runBlocking { tab.sendMessageToSession("Both agents, please reply") }
                awaitMessages(1, 2)
                assertEquals(2, requests.get())
                val messages = runBlocking { components.conversationService.loadCurrentMessages(conversation.id) }
                assertEquals(agents.map { it.id }.toSet(), messages.mapNotNull {
                    (it.author as? Conversation.Message.Author.Agent)?.agentDefinitionId
                }.toSet())
                runBlocking { tab.sendMessageToSession("@Beta only you this time") }
                awaitMessages(2, 3)
                assertEquals(3, requests.get())
                assertEquals(agents.last().id, (runBlocking {
                    components.conversationService.loadCurrentMessages(conversation.id)
                }.last { it.role == Conversation.Message.Role.ASSISTANT }.author as Conversation.Message.Author.Agent).agentDefinitionId)
                runBlocking {
                    components.conversationService.updateAutoRespondAgentIds(conversation.id, emptySet())
                    tab.sendMessageToSession("No agent should reply")
                }
                awaitMessages(3, 3)
                assertEquals(3, requests.get())
                client.openAnotherClient().use { reconnected ->
                    assertTrue(runBlocking {
                        reconnected.components.conversationService.findById(conversation.id)
                    }!!.autoRespondAgentIds.isEmpty())
                }
                onNodeWithTag(UiTestTag.ParticipantsButton.value).performClick()
                saveScreenshot("automatic-responder-conversation")
                runBlocking {
                    val narrowed = components.conversationService.updateParticipants(conversation.id, conversation.participants)
                    assertEquals(setOf(agents.first().id), checkNotNull(narrowed).autoRespondAgentIds)
                    tab.sendMessageToSession("One user and one agent again")
                }
                awaitMessages(4, 4)
                assertEquals(4, requests.get())
            }
        } finally {
            provider.stop(0)
        }
    }

    @Test
    fun opensConversationAndAcceptsMessageInput() = runGromozekaUiTest("conversation-creation") { client ->
        val project = runBlocking {
            client.components.projectService.create(
                name = "Conversation project ${UUID.randomUUID().toString().take(8)}",
                description = "Project for conversation UI verification",
            )
        }

        waitForTag(UiTestTag.NewSessionButton(project.id.value))
        onNodeWithTag(UiTestTag.NewSessionButton(project.id.value).value).performClick()
        waitForTag(UiTestTag.SessionScreen)
        waitForTag(UiTestTag.MessageInput)

        val message = "Message prepared by Compose E2E"
        onNodeWithTag(UiTestTag.MessageInput.value).performTextInput(message)
        onNodeWithTag(UiTestTag.MessageInput.value).assertTextContains(message)
        waitUntil(timeoutMillis = 30_000) {
            runBlocking { client.components.conversationService.findByProject(project.id) }.isNotEmpty()
        }
    }
}
