package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem.PromptMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationTurnPromptServiceTest {
    @Test fun `catalog edits apply next turn while iterations share one immutable prompt message`() = runBlocking<Unit> {
        val f = Fixture()
        f.startTurn("first")
        assertEquals(listOf("original"), f.assemble())
        val first = f.stored.single()
        f.prompt = f.prompt.copy(content = "changed")
        f.continueTurn("tool-follow-up")
        assertEquals(listOf("original"), f.assemble())
        assertEquals(1, f.reads)
        assertEquals(listOf(first), f.stored)
        assertEquals(first.id, f.runtime.schedulingSnapshot(f.conversation.id).lastTurn?.promptMessageId)

        f.finishTurn()
        f.startTurn("second")
        assertEquals(listOf("changed"), f.assemble())
        val replacement = assertNotNull(f.stored.last().promptSnapshotOrNull())
        assertEquals(PromptMessage.Action.REPLACE, replacement.action)
        assertEquals(first.id, replacement.previousMessageId)
        assertEquals("original", (first.promptSnapshotOrNull()!!.snapshot.entries.single() as PromptSnapshot.Entry.Catalog).text)
        assertNotEquals(first.promptSnapshotOrNull()!!.snapshot.fingerprint, replacement.snapshot.fingerprint)
        assertEquals(2, f.reads)
    }

    @Test fun `unchanged prompts reuse the existing message and detachment records its predecessor`() = runBlocking<Unit> {
        val f = Fixture()
        f.startTurn("first"); f.assemble(); val first = f.stored.single()
        f.finishTurn(); f.startTurn("same"); f.assemble()
        assertEquals(listOf(first), f.stored)
        assertEquals(first.id, f.runtime.schedulingSnapshot(f.conversation.id).lastTurn?.promptMessageId)
        f.finishTurn(); f.agent = f.agent.copy(prompts = emptyList()); f.startTurn("detached")
        assertTrue(f.assemble().isEmpty())
        val detached = assertNotNull(f.stored.last().promptSnapshotOrNull())
        assertEquals(PromptMessage.Action.DETACH, detached.action)
        assertEquals(first.id, detached.previousMessageId)
        assertTrue(detached.snapshot.entries.isEmpty())
    }

    @Test fun `lost append acknowledgement recovers the captured content rather than rereading changed catalog`() = runBlocking<Unit> {
        val f = Fixture()
        f.startTurn("ambiguous")
        f.failAppendAcknowledgement = true
        assertFailsWith<IllegalStateException> { f.assemble() }
        assertEquals(1, f.stored.size)
        assertNull(f.runtime.schedulingSnapshot(f.conversation.id).lastTurn?.promptMessageId)
        f.prompt = f.prompt.copy(content = "edited after the durable append")
        f.failAppendAcknowledgement = false
        assertEquals(listOf("original"), f.assemble())
        assertEquals(1, f.stored.size)
        assertEquals(1, f.reads)
        assertEquals(f.stored.single().id, f.runtime.schedulingSnapshot(f.conversation.id).lastTurn?.promptMessageId)
    }

    @Test fun `stale owner cannot capture prompts or change the reference of a bound turn`() = runBlocking<Unit> {
        val f = Fixture()
        f.startTurn("owned")
        val stale = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("stale"))
        assertFailsWith<IllegalStateException> { f.assemble(stale) }
        assertEquals(0, f.reads)
        assertTrue(f.stored.isEmpty())
        f.assemble()
        assertFalse(f.runtime.bindTurnPromptMessage(f.conversation.id, f.task.id, stale, Message.Id("other")))
        assertFailsWith<IllegalStateException> {
            f.runtime.bindTurnPromptMessage(f.conversation.id, f.task.id, f.owner, Message.Id("other"))
        }
        assertEquals(f.stored.single().id, f.runtime.schedulingSnapshot(f.conversation.id).lastTurn?.promptMessageId)
    }

    @Test fun `dynamic environment is rendered live without recapturing the catalog`() = runBlocking<Unit> {
        val f = Fixture()
        f.agent = f.agent.copy(prompts = f.agent.prompts + Prompt.Id("env"))
        f.startTurn("environment")
        assertEquals(listOf("original", "environment-1"), f.assemble())
        f.environment = "environment-2"
        f.prompt = f.prompt.copy(content = "not in this turn")
        assertEquals(listOf("original", "environment-2"), f.assemble())
        assertEquals(1, f.reads)
        assertEquals(PromptSnapshot.Entry.Environment, f.stored.single().promptSnapshotOrNull()!!.snapshot.entries.last())
    }

    @Test fun `missing bound snapshot fails rather than silently adopting current prompts`() = runBlocking<Unit> {
        val f = Fixture()
        f.startTurn("bound"); f.assemble(); f.stored.clear()
        assertFailsWith<IllegalArgumentException> { f.assemble() }
        assertEquals(1, f.reads)
    }

    private class Fixture {
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val owner = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
        val runtime = InMemoryConversationRuntimeCoordinator()
        val user = User.Id("user")
        var prompt = Prompt(Prompt.Id("prompt"), name = "Rules", content = "original", type = Prompt.Type.Global, createdAt = now, updatedAt = now)
        var agent = AgentDefinition(AgentDefinition.Id("agent"), name = "Developer", prompts = listOf(prompt.id),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user), Conversation.Participant.Agent(agent.id)), currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val context = RuntimeEnvironmentContext.Standalone(RuntimeEnvironmentExecutor.Server)
        val stored = mutableListOf<Message>()
        var reads = 0
        var environment = "environment-1"
        var failAppendAcknowledgement = false
        lateinit var task: ConversationRuntimeTask
        val repository = Mockito.mock(PromptRepository::class.java) { invocation ->
            check(invocation.method.name.startsWith("findById")); reads++; prompt
        }
        val threads = Mockito.mock(ThreadMessageRepository::class.java) { invocation ->
            check(invocation.method.name.startsWith("getContextMessagesByThread")); stored.toList()
        }
        val history = Mockito.mock(ConversationHistoryReadService::class.java) { invocation ->
            check(invocation.method.name.startsWith("findMessage"))
            val id = invocation.arguments[1] as String
            stored.singleOrNull { it.id.value == id }
        }
        val appender = Mockito.mock(ConversationRuntimeMessageAppender::class.java) { invocation ->
            check(invocation.method.name.startsWith("appendRuntimeMessage"))
            val message = invocation.arguments[1] as Message
            check(stored.none { it.id == message.id }); stored += message
            if (failAppendAcknowledgement) error("Lost append acknowledgement")
            conversation
        }
        val settings = Mockito.mock(SettingsProvider::class.java) { UserProfile() }
        val environmentBuilder = Mockito.mock(SystemPromptBuilder::class.java) { invocation ->
            check(invocation.method.name.startsWith("buildEnvironmentInfo")); environment
        }
        val prompts = PromptApplicationService(repository, Mockito.mock(AgentRepository::class.java), environmentBuilder, settings)
        val service = ConversationTurnPromptService(prompts, threads, history, appender, runtime)
        suspend fun startTurn(id: String) {
            task = ConversationRuntimeTask(ConversationRuntimeTask.Id(id), conversation.id, actorUserId = user,
                payload = ConversationRuntimeTask.Payload.AgentInvocation(Message(Message.Id("root"), conversation.id,
                    role = Message.Role.USER, author = Message.Author.User(user, "User"),
                    content = listOf(Message.ContentItem.UserMessage("Work")), createdAt = now), agent.id),
                placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id,
                requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = now)
            assertTrue(runtime.submit(task)); claim()
        }
        suspend fun claim() {
            task = assertNotNull(runtime.claimDeliveredTask(conversation.id, task.id, owner, task.requirements.capabilities, emptySet()))
            assertTrue(runtime.markActiveTaskStarted(conversation.id, task.id, owner, now))
        }
        suspend fun continueTurn(id: String) {
            val next = task.copy(id = ConversationRuntimeTask.Id(id), parentTaskId = task.id, idempotencyKey = id,
                payload = ConversationRuntimeTask.Payload.LlmCall(Message.Id("root"), agent.id, 2))
            assertTrue(runtime.completeActiveTask(conversation.id, task.id, owner, ConversationRuntimeTaskOutcome.Continue(next)))
            task = next; claim()
        }
        suspend fun finishTurn() { assertTrue(runtime.completeActiveTask(conversation.id, task.id, owner, ConversationRuntimeTaskOutcome.CompleteTurn)) }
        suspend fun assemble(executor: ConversationRuntimeExecutorIdentity = owner) =
            service.assemble(conversation, agent, context, task, executor, null) {}
    }
}
