package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.application.service.InMemoryConversationRuntimeCoordinator
import com.gromozeka.application.service.RecordedRunningCommandsRequestEnricher
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTurnId
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

/** Actual application enrichment followed by the subscription's semantic and wire projections. */
class OpenAiSubscriptionCommandContextTest {
    private val helper = OpenAiSubscriptionWebSearchTest
    private val search = OpenAiSubscriptionWebSearch(helper.client("http://127.0.0.1:1"))

    @Test fun `inventory cannot turn a tool result into new input and hide a pending mixed web call`() = runBlocking<Unit> {
        for (hasRunningCommand in listOf(false, true)) {
            val human = helper.userMessage("Find the relevant documentation")
            val ordinary = JsonObject(helper.webCall + mapOf("namespace" to JsonPrimitive("functions"),
                "name" to JsonPrimitive("read_file"), "call_id" to JsonPrimitive("ordinary")))
            val response = helper.mapResponse(listOf(helper.webCall, ordinary))
            assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
            val generated = helper.persisted(response).map { it.copy(providerMetadata = JsonObject(it.providerMetadata +
                (AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive("thread")))) }
            val result = toolResult("ordinary")
            val original = request(listOf(human) + generated + result)
            val originalProjection = original.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            assertNotNull(search.pendingCall(originalProjection, helper.connectionId, helper.model))
            val enriched = enrich(original, fixture(hasRunningCommand).second)
            val projected = enriched.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            val pending = assertNotNull(search.pendingCall(projected, helper.connectionId, helper.model))
            assertEquals(JsonPrimitive("web-call"), pending["call_id"])
            assertEquals(search.recentInput(originalProjection), search.recentInput(projected))
            assertEquals(result.content, projected.last().content)
            assertTrue(projected.last().content.none { it is ContentItem.UserMessage })
            val wire = OpenAiSubscriptionRequestMapper().toRequest(enriched.copy(messages = projected), helper.profile,
                "conversation", true, helper.connectionId)
            assertContains(wire.input.toString(), "recorded_running_commands")
        }
    }

    @Test fun `two ordinary tool steps cannot displace human text or leak inventory into search context`() = runBlocking<Unit> {
        for (hasRunningCommand in listOf(false, true)) {
            val human = helper.userMessage("HUMAN_QUERY_SENTINEL")
            val history = listOf(human, toolCall("one"), toolResult("one"), toolCall("two"), toolResult("two"))
            val original = request(history)
            val expected = search.recentInput(history)
            assertEquals(1, expected.size)
            val enricher = fixture(hasRunningCommand).second
            enrich(request(history.take(1)), enricher)
            enrich(request(history.take(3)), enricher)
            val enriched = enrich(original, enricher)
            val projected = enriched.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            assertEquals(expected, search.recentInput(projected))
            assertFalse(search.recentInput(projected).toString().contains("recorded_running_commands"))
            assertContains(search.recentInput(projected).toString(), "HUMAN_QUERY_SENTINEL")
            assertEquals(history.map { it.content }, projected.map { it.content })
            val wire = OpenAiSubscriptionRequestMapper().toRequest(enriched.copy(messages = projected), helper.profile,
                "conversation", true, helper.connectionId)
            assertContains(wire.input.toString(), "recorded_running_commands")
        }
    }

    @Test fun `same-anchor refresh preserves the actual wire input and supplies current state separately`() = runBlocking<Unit> {
        val (coordinator, enricher) = fixture(true)
        val original = request(listOf(helper.userMessage("UNCHANGED_HUMAN_INPUT")))
        val first = enrich(original, enricher)
        val initialWire = OpenAiSubscriptionRequestMapper().toRequest(first, helper.profile, "conversation", true, helper.connectionId)
        val task = assertNotNull(coordinator.findCommandTask(CONVERSATION, CommandTask.Id("background")))
        coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED,
            updatedAt = Instant.fromEpochSeconds(2), completedAt = Instant.fromEpochSeconds(2)))
        val changed = enrich(original, enricher)
        val changedWire = OpenAiSubscriptionRequestMapper().toRequest(changed, helper.profile, "conversation", true, helper.connectionId)
        assertEquals(initialWire.input, changedWire.input)
        assertContains(changedWire.instructions.orEmpty(), "\"recorded_running_commands\":[]")
        assertEquals(search.recentInput(original.messages), search.recentInput(changed.messages))
    }

    private suspend fun fixture(hasRunningCommand: Boolean): Pair<InMemoryConversationRuntimeCoordinator, RecordedRunningCommandsRequestEnricher> {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        if (hasRunningCommand) coordinator.upsertCommandTask(CommandTask(CommandTask.Id("background"), CONVERSATION,
            ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), agentDefinitionId = AgentDefinition.Id("agent"),
            command = "sleep 60", workingDirectory = "/tmp", status = CommandTask.Status.WORKING, processId = 1,
            processStartedAt = NOW, outputFile = "/tmp/unused", outputBytes = 0, createdAt = NOW, updatedAt = NOW,
            description = "Background analysis"))
        return coordinator to RecordedRunningCommandsRequestEnricher(coordinator)
    }

    private suspend fun enrich(request: AiRuntimeRequest, enricher: RecordedRunningCommandsRequestEnricher) =
        enricher.enrich(CONVERSATION, request.messages.first().id, ConversationRuntimeTurnId("turn"), MODEL, request)

    private fun request(messages: List<Conversation.Message>) = AiRuntimeRequest(listOf("system"), messages,
        options = AiRuntimeOptions(toolContext = mapOf(TOOL_CONTEXT_CONVERSATION_ID to CONVERSATION.value,
            TOOL_CONTEXT_THREAD_ID to "thread", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent", TOOL_CONTEXT_USER_ID to "user")))
    private fun toolCall(id: String) = helper.message(Conversation.Message.Role.ASSISTANT,
        listOf(ContentItem.ToolCall(ContentItem.ToolCall.Id(id), ContentItem.ToolCall.Data("read_file", buildJsonObject {}))))
    private fun toolResult(id: String) = helper.message(Conversation.Message.Role.USER,
        listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id(id), "read_file", listOf(ContentItem.ToolResult.Data.Text("tool output")))))

    companion object {
        private val NOW = Instant.fromEpochSeconds(1)
        private val CONVERSATION = Conversation.Id("conversation")
        private val MODEL = AiModelSpec("model", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(1_000_000)))
    }
}
