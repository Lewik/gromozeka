package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.isDurableRuntimeObservationBoundary
import com.gromozeka.domain.model.withDurableRuntimeObservationBoundary
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
        assertEquals(Conversation.Message.Role.RUNTIME, first.role)
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
        assertEquals(listOf(
            ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
            "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
        ),
            closed.observations().map { it.key })
        val inventory = frame(closed.observations().first().text, "visual-inventory-context")
        assertEquals(listOf("large"), inventory.getValue("visuals").jsonArray.map {
            it.jsonObject.getValue("visual-id").jsonPrimitive.content
        })
        val tombstone = frame(closed.observations().last().text, "visual-state-context")
        assertTrue(closed.observations().last().removed)
        assertEquals(VISUAL.id, tombstone.getValue("visual-id").jsonPrimitive.content)
        assertEquals("closed", tombstone.getValue("state-status").jsonPrimitive.content)
        assertFalse(closed.toString().contains("saved"))
        assertTrue(f.append("still-closed", listOf(baseline, changed, closed)).isEmpty())
    }

    @Test
    fun `repeated large Visual changes reset to one bounded durable baseline`() = runBlocking {
        val f = Fixture()
        val history = mutableListOf<Conversation.Message>()
        repeat(10) { index ->
            f.visuals = listOf(VISUAL.copy(state = json(
                """{"form":{"query":"saved"},"data":{"count":$index,"payload":"${"x".repeat(300_000)}"}}""",
            )))
            history += f.append("large-$index", history).single()
        }

        val boundaries = history.filter { it.isDurableRuntimeObservationBoundary }
        val projected = ConversationContext(history).messagesForProvider(null)
        val retained = projected.filter { it.author is Conversation.Message.Author.Runtime }
        val retainedBytes = retained.sumOf { message -> message.observations().sumOf { it.text.encodeToByteArray().size.toLong() } }

        assertTrue(boundaries.size > 1)
        assertEquals(boundaries.last().id, retained.first().id)
        assertTrue(retained.size < history.size)
        assertTrue(retainedBytes <= ConversationRuntimeObservationService.MAX_RETAINED_OBSERVATION_BYTES)
        val latestState = retained.flatMap { it.observations() }.last {
            it.key == "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}"
        }
        assertEquals(
            "9",
            frame(latestState.text, "visual-state-context")
                .getValue("state").jsonObject.getValue("data").jsonObject
                .getValue("count").jsonPrimitive.content,
        )
    }

    @Test
    fun `one irreducibly large unchanged baseline is not rewritten on every request`() = runBlocking {
        val f = Fixture()
        f.visuals = listOf(VISUAL.copy(state = json(
            """{"form":{},"data":{"payload":"${"x".repeat(1_100_000)}"}}""",
        )))
        val baseline = f.append("large-baseline", emptyList()).single()

        assertTrue(baseline.observations().sumOf { it.text.encodeToByteArray().size.toLong() } >
            ConversationRuntimeObservationService.MAX_RETAINED_OBSERVATION_BYTES)
        assertTrue(f.append("unchanged", listOf(baseline)).isEmpty())
    }

    @Test
    fun `closing a Visual after a selective checkpoint still replaces retained state`() = runBlocking {
        val f = Fixture()
        val baseline = f.append("baseline", emptyList()).single()
        val selected = user("selected")
        val checkpoint = checkpoint("checkpoint", listOf(selected))

        f.visuals = emptyList()
        val closed = f.append("closed", listOf(baseline, selected, checkpoint)).single()

        assertEquals(
            listOf(
                ConversationRuntimeObservationService.COMMANDS_KEY,
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            closed.observations().map { it.key },
        )
        assertEquals(
            "closed",
            frame(closed.observations().last().text, "visual-state-context")
                .getValue("state-status").jsonPrimitive.content,
        )
        assertTrue(closed.observations().last().removed)

        val afterCheckpoint = checkpoint("after-close", listOf(baseline, closed))
        val restored = f.append(
            "after-checkpoint",
            listOf(baseline, selected, checkpoint, closed, afterCheckpoint),
        ).single()
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.COMMANDS_KEY,
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
            ),
            restored.observations().map { it.key },
        )
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
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            baseline.observations().map { it.key },
        )
        assertFalse(baseline.toString().contains("saved"))
        assertEquals(
            "disabled",
            frame(baseline.observations().last().text, "visual-state-context")
                .getValue("state-status").jsonPrimitive.content,
        )

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
    fun `full checkpoint forces one complete post checkpoint baseline`() = runBlocking {
        val f = Fixture()
        val baseline = f.append("baseline", emptyList()).single()
        val checkpoint = checkpoint("full", listOf(baseline), full = true)
            .withDurableRuntimeObservationBoundary()

        val restored = f.append("restored", listOf(baseline, checkpoint)).single()
        assertEquals(3, restored.observations().size)
        assertTrue(f.append("unchanged", listOf(baseline, checkpoint, restored)).isEmpty())
    }

    @Test
    fun `selective checkpoint cannot expose an older matching observation revision`() = runBlocking {
        val f = Fixture()
        val baselineA = f.append("baseline-a", emptyList()).single()
        f.visuals = listOf(VISUAL.copy(state = json("""{"form":{"query":"B"},"data":{"count":1}}""")))
        val changedB = f.append("changed-b", listOf(baselineA)).single()
        val checkpointB = checkpoint("checkpoint-b", listOf(changedB))

        f.visuals = listOf(VISUAL)
        val restoredA = f.append("restored-a", listOf(baselineA, changedB, checkpointB)).single()

        assertTrue(restoredA.isDurableRuntimeObservationBoundary)
        assertEquals(
            listOf(
                ConversationRuntimeObservationService.COMMANDS_KEY,
                ConversationRuntimeObservationService.VISUAL_INVENTORY_KEY,
                "${ConversationRuntimeObservationService.VISUAL_STATE_KEY_PREFIX}${VISUAL.id}",
            ),
            restoredA.observations().map { it.key },
        )
        assertTrue(restoredA.toString().contains("saved"))
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

        private fun checkpoint(id: String, sources: List<Conversation.Message>, full: Boolean = false) = user(id).copy(
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(id),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                sourceMessageIds = sources.map(Conversation.Message::id),
                coverage = if (full) {
                    Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS
                } else {
                    Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES
                },
            )),
        )

        private fun frame(text: String, tag: String): JsonObject = Json.parseToJsonElement(
            text.substringAfter("<$tag>").substringBefore("</$tag>"),
        ).jsonObject

        private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
