package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.isDurableRuntimeObservationBoundary
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTurnId
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.visual.Visual
import com.gromozeka.domain.visual.VisualRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito

class ConversationRuntimeObservationServiceTest {
    @Test
    fun `first live request persists a complete baseline and restart needs no RAM state`() = runBlocking {
        val f = Fixture()
        val first = f.append("first", emptyList()).single()

        assertTrue(first.isDurableRuntimeObservationBoundary)
        assertEquals(Conversation.Message.Role.USER, first.role)
        assertIs<Conversation.Message.Author.Runtime>(first.author)
        assertTrue(first.content.isEmpty())
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.COMMANDS_KEY,
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            first.observations().map { it.key },
        )
        assertEquals(emptyList(), f.append("retry", listOf(first)))

        val restarted = f.restart()
        assertEquals(emptyList(), restarted.append("after-restart", listOf(first)))
        assertEquals(1, f.appender.persisted.size)
    }

    @Test
    fun `a changed panel does not duplicate another large unchanged panel`() = runBlocking {
        val f = Fixture()
        val large = VISUAL.copy(
            id = "large",
            title = "Large",
            state = json("""{"form":{},"data":{"payload":"${"x".repeat(200_000)}"}}"""),
        )
        f.visuals = listOf(VISUAL, large)
        val baseline = f.append("baseline", emptyList()).single()

        f.visuals = listOf(
            VISUAL.copy(state = json("""{"form":{"query":"changed"},"data":{"count":1}}""")),
            large,
        )
        val changed = f.append("changed", listOf(baseline)).single()
        val keys = changed.observations().map { it.key }
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            keys,
        )
        assertFalse(changed.toString().contains("x".repeat(1_000)))

        f.visuals = listOf(large)
        val closed = f.append("closed", listOf(baseline, changed)).single()
        assertEquals(listOf(ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY),
            closed.observations().map { it.key })
        val inventory = frame(closed.observations().single().text, "visual-inventory-context")
        assertEquals(listOf("large"), inventory.getValue("visuals").jsonArray.map {
            it.jsonObject.getValue("visual-id").jsonPrimitive.content
        })
    }

    @Test
    fun `disabled Visual state changes remain absent until context is enabled`() = runBlocking {
        val f = Fixture()
        f.visuals = listOf(VISUAL.copy(includeStateInContext = false))
        val baseline = f.append("baseline", emptyList()).single()
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.COMMANDS_KEY,
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
            ),
            baseline.observations().map { it.key },
        )
        assertFalse(baseline.toString().contains("saved"))

        f.visuals = listOf(VISUAL.copy(
            includeStateInContext = false,
            state = json("""{"form":{"query":"hidden"},"data":{"count":99}}"""),
        ))
        assertTrue(f.append("hidden-change", listOf(baseline)).isEmpty())

        f.visuals = listOf(VISUAL.copy(
            includeStateInContext = true,
            state = json("""{"form":{"query":"visible"},"data":{"count":99}}"""),
        ))
        val enabled = f.append("enabled", listOf(baseline)).single()
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            enabled.observations().map { it.key },
        )
        assertTrue(enabled.toString().contains("visible"))
    }

    @Test
    fun `running command inventory is durable bounded and excludes command text`() = runBlocking {
        val f = Fixture()
        val baseline = f.append("baseline", emptyList()).single()
        val longDescription = "  ${"🙂".repeat(200)}  "
        f.coordinator.upsertCommandTask(command("running", longDescription))
        f.coordinator.upsertCommandTask(command("done", "Done").copy(status = CommandTask.Status.COMPLETED))

        val running = f.append("running", listOf(baseline)).single()
        val observations = running.observations()
        assertEquals(listOf(ConversationRuntimeObservationService.COMMANDS_KEY), observations.map { it.key })
        val commands = frame(observations.single().text, "runtime-command-context")
            .getValue("recorded_running_commands").jsonArray
        assertEquals(1, commands.size)
        assertEquals("running", commands.single().jsonObject.getValue("task_id").jsonPrimitive.content)
        assertEquals("🙂".repeat(160), commands.single().jsonObject.getValue("description").jsonPrimitive.content)
        assertFalse(running.toString().contains("PRIVATE_COMMAND"))

        f.coordinator.upsertCommandTask(command("running", longDescription).copy(status = CommandTask.Status.COMPLETED))
        val completed = f.append("completed", listOf(baseline, running)).single()
        assertTrue(frame(completed.observations().single().text, "runtime-command-context")
            .getValue("recorded_running_commands").jsonArray.isEmpty())
    }

    @Test
    fun `isolated snapshots are fresh and compaction snapshots remain durable and read only`() = runBlocking {
        val f = Fixture()
        val request = AiRuntimeRequest(
            systemPrompts = emptyList(),
            messages = listOf(user("root")),
            options = AiRuntimeOptions(toolContext = mapOf(TOOL_CONTEXT_CONVERSATION_ID to CONVERSATION.id.value)),
        )
        val isolated = f.service.enrichSnapshot(
            CONVERSATION.id, Conversation.Message.Id("root"), ConversationRuntimeTurnId("snapshot"), MODEL, request)
        assertEquals(request.messages.size + 1, isolated.messages.size)
        assertEquals(3, isolated.messages.last().observations().size)
        assertTrue(f.appender.persisted.isEmpty())

        assertEquals(request, f.service.enrichCompactionSnapshot(
            CONVERSATION.id, Conversation.Message.Id("root"), ConversationRuntimeTurnId("compact"), MODEL, request))
        assertEquals(request, f.service.enrich(
            CONVERSATION.id, Conversation.Message.Id("root"), ConversationRuntimeTurnId("live"), MODEL, request))
    }

    private class Fixture {
        var visuals = listOf(VISUAL)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val appender = RecordingAppender()
        private val repository = Mockito.mock(VisualRepository::class.java)
        val service: ConversationRuntimeObservationService

        init {
            runBlocking { Mockito.`when`(repository.list(CONVERSATION.id)).thenAnswer { visuals } }
            service = ConversationRuntimeObservationService(repository, coordinator, appender)
        }

        suspend fun restart() = Fixture().also {
            it.visuals = visuals
            coordinator.findCommandTasks(CONVERSATION.id).forEach { task -> it.coordinator.upsertCommandTask(task) }
            it.appender.persisted += appender.persisted
        }

        suspend fun append(id: String, history: List<Conversation.Message>): List<Conversation.Message> =
            service.appendChanged(CONVERSATION, ConversationRuntimeTask.Id(id), history) {}
    }

    private class RecordingAppender : ConversationRuntimeMessageAppender {
        val persisted = mutableListOf<Conversation.Message>()

        override suspend fun appendRuntimeMessages(
            conversationId: Conversation.Id,
            messages: List<Conversation.Message>,
        ): List<Conversation.Message> {
            persisted += messages
            return messages
        }

        override suspend fun appendRuntimeMessage(
            conversationId: Conversation.Id,
            message: Conversation.Message,
        ): Conversation? = error("Observation writes use the atomic batch path")
    }

    companion object {
        private val NOW = Instant.fromEpochMilliseconds(0)
        private val CONVERSATION = Conversation(
            id = Conversation.Id("conversation"),
            projectId = Project.Id("project"),
            participants = setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = Conversation.Thread.Id("thread"),
            createdAt = NOW,
            updatedAt = NOW,
        )
        private val MODEL = AiModelSpec(
            "model",
            AiProvider.OPENAI,
            setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(1_000_000)),
        )
        private val VISUAL = Visual(
            id = "visual",
            conversationId = CONVERSATION.id,
            createdBy = User.Id("user"),
            document = "document",
            title = "Panel",
            state = json("""{"form":{"query":"saved"},"data":{"count":0}}"""),
            createdAt = NOW,
            updatedAt = NOW,
            includeStateInContext = true,
        )

        private fun command(id: String, description: String) = CommandTask(
            id = CommandTask.Id(id),
            conversationId = CONVERSATION.id,
            workerId = ConversationRuntimeWorkerId("worker"),
            workspaceMountId = WorkspaceMount.Id("mount"),
            command = "PRIVATE_COMMAND",
            workingDirectory = "/tmp",
            status = CommandTask.Status.WORKING,
            processId = 1,
            processStartedAt = NOW,
            outputFile = "/tmp/$id",
            outputBytes = 0,
            createdAt = NOW,
            updatedAt = NOW,
            description = description,
        )

        private fun Conversation.Message.observations() =
            instructions.filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>()

        private fun user(id: String) = Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = CONVERSATION.id,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("input")),
            createdAt = NOW,
        )

        private fun frame(text: String, tag: String): JsonObject = Json.parseToJsonElement(
            text.substringAfter("<$tag>").substringBefore("</$tag>"),
        ).jsonObject

        private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
