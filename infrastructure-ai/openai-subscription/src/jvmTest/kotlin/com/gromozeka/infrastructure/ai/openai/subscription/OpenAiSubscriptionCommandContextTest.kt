package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.application.service.InMemoryConversationRuntimeCoordinator
import com.gromozeka.application.service.ConversationRuntimeMessageAppender
import com.gromozeka.application.service.ConversationRuntimeObservationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.visual.Visual
import com.gromozeka.domain.visual.VisualActionReceipt
import com.gromozeka.domain.visual.VisualActionResult
import com.gromozeka.domain.visual.VisualRepository
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
            val fixture = fixture(hasRunningCommand)
            val human = helper.userMessage("Find the relevant documentation")
            val ordinary = JsonObject(helper.webCall + mapOf("namespace" to JsonPrimitive("functions"),
                "name" to JsonPrimitive("read_file"), "call_id" to JsonPrimitive("ordinary")))
            val response = helper.mapResponse(listOf(helper.webCall, ordinary))
            assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
            val generated = helper.persisted(response).map { it.copy(providerMetadata = JsonObject(it.providerMetadata +
                (AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive("thread")))) }
            val result = toolResult("ordinary")
            val baseline = fixture.enrich(request(listOf(human)))
            val original = request(baseline.messages + generated + result)
            val originalProjection = original.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            assertNotNull(search.pendingCall(originalProjection, helper.connectionId, helper.model))
            fixture.toggleCommand()
            val enriched = fixture.enrich(original)
            val projected = enriched.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            val pending = assertNotNull(search.pendingCall(projected, helper.connectionId, helper.model))
            assertEquals(JsonPrimitive("web-call"), pending["call_id"])
            assertEquals(search.recentInput(originalProjection), search.recentInput(projected))
            assertEquals(result.content, projected.single { it.id == result.id }.content)
            assertTrue(projected.last().content.isEmpty())
            assertIs<Conversation.Message.Author.Runtime>(projected.last().author)
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
            val enriched = fixture(hasRunningCommand).enrich(original)
            val projected = enriched.projectedMessages(AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
            assertEquals(expected, search.recentInput(projected))
            assertFalse(search.recentInput(projected).toString().contains("recorded_running_commands"))
            assertContains(search.recentInput(projected).toString(), "HUMAN_QUERY_SENTINEL")
            assertEquals(history.map { it.content }, projected.filter { message -> history.any { it.id == message.id } }.map { it.content })
            val wire = OpenAiSubscriptionRequestMapper().toRequest(enriched.copy(messages = projected), helper.profile,
                "conversation", true, helper.connectionId)
            assertContains(wire.input.toString(), "recorded_running_commands")
        }
    }

    @Test fun `changed inventory appends one durable observation without rewriting prior input`() = runBlocking<Unit> {
        val fixture = fixture(true)
        val original = request(listOf(helper.userMessage("UNCHANGED_HUMAN_INPUT")))
        val first = fixture.enrich(original)
        val task = assertNotNull(fixture.coordinator.findCommandTask(CONVERSATION_ID, CommandTask.Id("background")))
        fixture.coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED,
            updatedAt = Instant.fromEpochSeconds(2), completedAt = Instant.fromEpochSeconds(2)))
        val changed = fixture.enrich(first)
        val changedWire = OpenAiSubscriptionRequestMapper().toRequest(changed, helper.profile, "conversation", true, helper.connectionId)
        assertEquals(first.messages, changed.messages.dropLast(1))
        assertEquals(1, changed.messages.last().instructions.size)
        assertContains(changed.messages.last().instructions.single().toXmlLine(), "\"recorded_running_commands\":[]")
        assertContains(changedWire.input.toString(), "recorded_running_commands")
        assertEquals(search.recentInput(original.messages), search.recentInput(changed.messages))
    }

    private suspend fun fixture(hasRunningCommand: Boolean): Fixture {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        if (hasRunningCommand) coordinator.upsertCommandTask(commandTask())
        return Fixture(coordinator)
    }

    private class Fixture(val coordinator: InMemoryConversationRuntimeCoordinator) {
        private var call = 0
        private val service = ConversationRuntimeObservationService(EmptyVisualRepository, coordinator, PassthroughAppender)

        suspend fun enrich(request: AiRuntimeRequest): AiRuntimeRequest {
            val appended = service.appendChanged(
                CONVERSATION,
                ConversationRuntimeTask.Id("observation-${call++}"),
                request.messages,
            ) {}
            return request.copy(messages = request.messages + appended)
        }

        suspend fun toggleCommand() {
            val task = coordinator.findCommandTask(CONVERSATION_ID, CommandTask.Id("background"))
            if (task == null) {
                coordinator.upsertCommandTask(commandTask())
            } else {
                coordinator.upsertCommandTask(task.copy(
                    status = CommandTask.Status.COMPLETED,
                    completedAt = Instant.fromEpochSeconds(2),
                    updatedAt = Instant.fromEpochSeconds(2),
                ))
            }
        }
    }

    private object PassthroughAppender : ConversationRuntimeMessageAppender {
        override suspend fun appendRuntimeMessages(
            conversationId: Conversation.Id,
            messages: List<Conversation.Message>,
        ) = messages

        override suspend fun appendRuntimeMessage(
            conversationId: Conversation.Id,
            message: Conversation.Message,
        ): Conversation? = error("Observation writes use the atomic batch path")
    }

    private object EmptyVisualRepository : VisualRepository {
        override suspend fun list(conversationId: Conversation.Id) = emptyList<Visual>()
        override suspend fun find(conversationId: Conversation.Id, visualId: String): Visual? = null
        override suspend fun save(visual: Visual) = error("Not used")
        override suspend fun delete(conversationId: Conversation.Id, visualId: String) = error("Not used")
        override suspend fun withHandlers() = emptyList<Visual>()
        override suspend fun findAction(eventId: String): VisualActionReceipt? = null
        override suspend fun acceptAction(visual: Visual, receipt: VisualActionReceipt) = error("Not used")
        override suspend fun finishAction(eventId: String, result: VisualActionResult) = error("Not used")
    }

    private fun request(messages: List<Conversation.Message>) = AiRuntimeRequest(listOf("system"), messages,
        options = AiRuntimeOptions(toolContext = mapOf(TOOL_CONTEXT_CONVERSATION_ID to CONVERSATION_ID.value,
            TOOL_CONTEXT_THREAD_ID to "thread", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent", TOOL_CONTEXT_USER_ID to "user")))
    private fun toolCall(id: String) = helper.message(Conversation.Message.Role.ASSISTANT,
        listOf(ContentItem.ToolCall(ContentItem.ToolCall.Id(id), ContentItem.ToolCall.Data("read_file", buildJsonObject {}))))
    private fun toolResult(id: String) = helper.message(Conversation.Message.Role.USER,
        listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id(id), "read_file", listOf(ContentItem.ToolResult.Data.Text("tool output")))))

    companion object {
        private val NOW = Instant.fromEpochSeconds(1)
        private val CONVERSATION_ID = Conversation.Id("conversation")
        private val CONVERSATION = Conversation(
            id = CONVERSATION_ID,
            projectId = Project.Id("project"),
            participants = setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = NOW,
            updatedAt = NOW,
        )

        private fun commandTask() = CommandTask(
            CommandTask.Id("background"),
            CONVERSATION_ID,
            ConversationRuntimeWorkerId("worker"),
            WorkspaceMount.Id("mount"),
            agentDefinitionId = AgentDefinition.Id("agent"),
            command = "sleep 60",
            workingDirectory = "/tmp",
            status = CommandTask.Status.WORKING,
            processId = 1,
            processStartedAt = NOW,
            outputFile = "/tmp/unused",
            outputBytes = 0,
            createdAt = NOW,
            updatedAt = NOW,
            description = "Background analysis",
        )
    }
}
