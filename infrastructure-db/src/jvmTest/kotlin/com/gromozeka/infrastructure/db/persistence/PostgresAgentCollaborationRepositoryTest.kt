package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.AgentCollaborationRepository.DeliveryCursor
import com.gromozeka.domain.service.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresAgentCollaborationRepositoryTest {
    private val json = Json { encodeDefaults = true }
    private val now = Instant.fromEpochMilliseconds(1)
    private val a = AgentEndpoint(Conversation.Id("a"), Conversation.Thread.Id("a-thread"), AgentDefinition.Id("agent"))
    private val b = AgentEndpoint(Conversation.Id("b"), Conversation.Thread.Id("b-thread"), AgentDefinition.Id("agent"))
    private val r = AgentRequest("r", a, b, User.Id("user"), "synthetic task", now)
    private val delivery = AgentDelivery("r:input", "r", a, b, r.actorUserId, AgentDelivery.Kind.REQUEST, r.text, now)
    private val done = r.copy(revision = 1, state = AgentRequest.State.COMPLETED, result = "synthetic result")
    private val resultDelivery = delivery.copy(id = "r:result", source = b, target = a, kind = AgentDelivery.Kind.RESULT, text = done.result!!)
    private val decision = AgentResponseDecision(null, AgentResponseDecision.Next.COMPLETE, emptyList(), listOf(AgentResponseDecision.RequestDecision("r", AgentRequest.State.COMPLETED, done.result, emptyList())))
    private val draft = AgentResponseDraft("draft", b, r.actorUserId, Conversation.Message.Id("r:input"), "turn", 1, emptyList(), listOf(r), emptyList(), emptyList(), "", emptyList(), now)
    private val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("review-task"), b.conversationId,
        turnId = ConversationRuntimeTurnId("turn"), parentTaskId = ConversationRuntimeTask.Id("llm"),
        payload = ConversationRuntimeTask.Payload.ResponseReview("draft", b.agentId), placement = QueuedMessagePlacement.END_OF_TURN,
        idempotencyKey = "review-task", requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server), createdAt = now)
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val scheduling = ConversationRuntimeSchedulingState(b.conversationId,
        executionState = ConversationExecutionState(b.conversationId, ConversationExecutionState.ControlState.RUNNING, task.id, executor, now, now), activeTask = task)

    @Test fun `outbox and requests survive repository recreation without duplicates`() = database { source, repo ->
        repo.create(r, delivery); repo.create(r, delivery)
        val resumed = PostgresAgentCollaborationRepository(source, json)
        assertEquals(listOf(r), resumed.requests(a.conversationId))
        assertEquals(listOf(delivery), resumed.pendingDeliveries())
        resumed.settleDelivery(delivery.copy(state = AgentDelivery.State.QUEUED))
        assertTrue(resumed.pendingDeliveries().isEmpty())
        assertFailsWith<IllegalArgumentException> { resumed.create(r.copy(text = "different request"), delivery) }
    }
    @Test fun `pending outbox cursor handles timestamp ties and rows removed between pages`() = database { _, repo ->
        val timestamp = Instant.parse("2026-09-29T19:00:00.123456789Z")
        val later = Instant.parse("2026-09-29T19:00:01Z")
        val messages = (0 until 130).map { index -> delivery.copy(
            id = if (index < 128) "message-${index.toString().padStart(3, '0')}" else "earlier-id-$index",
            requestId = null, kind = AgentDelivery.Kind.MESSAGE,
            createdAt = if (index < 128) timestamp else later,
        ) }
        messages.reversed().forEach { repo.create(null, it) }
        val first = repo.pendingDeliveries()
        assertEquals(messages.take(64), first)
        first.forEach { repo.settleDelivery(it.copy(state = AgentDelivery.State.QUEUED)) }
        val second = repo.pendingDeliveries(DeliveryCursor(first.last().createdAt, first.last().id))
        assertEquals(messages.subList(64, 128), second)
        val third = repo.pendingDeliveries(DeliveryCursor(second.last().createdAt, second.last().id))
        assertEquals(messages.drop(128), third)
        assertTrue(repo.pendingDeliveries(DeliveryCursor(third.last().createdAt, third.last().id)).isEmpty())
        assertEquals(second, repo.pendingDeliveries()) // A new sweep starts from the oldest remaining pending row.
    }
    @Test fun `request changes and result outbox commit together and reject stale completion`() = database { _, repo ->
        repo.create(r, delivery)
        val results = coroutineScope { (1..2).map { async { repo.change(listOf(r), listOf(done), listOf(resultDelivery)) } }.awaitAll() }
        assertEquals(1, results.count { it })
        assertEquals(done, repo.findRequest("r"))
        assertEquals(2, repo.pendingDeliveries().size)
    }
    @Test fun `decision is durable idempotent and preserves unpublished source draft`() = database { source, repo ->
        repo.create(r, delivery); repo.saveDraft(draft); writeScheduling(source, scheduling)
        assertTrue(repo.applyDecision(draft, task.id.value, decision, listOf(done), listOf(resultDelivery)))
        assertTrue(repo.applyDecision(draft, task.id.value, decision, listOf(done), listOf(resultDelivery)))
        assertEquals(decision, repo.findDraft("draft")?.decision)
        assertEquals(2, repo.pendingDeliveries().size)
        assertEquals(1, repo.findRequest("r")?.revision)
    }
    @Test fun `steer arriving while checker runs prevents stale publication and sends nothing`() = database { source, repo ->
        repo.create(r, delivery); repo.saveDraft(draft)
        val input = Conversation.Message(Conversation.Message.Id("steer"), b.conversationId, role = Conversation.Message.Role.USER, content = emptyList(), createdAt = now)
        val steer = ConversationRuntimeTask(ConversationRuntimeTask.Id("steer"), b.conversationId,
            payload = ConversationRuntimeTask.Payload.AgentInvocation(input, b.agentId), placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
            idempotencyKey = "steer", requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.CONVERSATION_TURN, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server), createdAt = now)
        writeScheduling(source, scheduling.copy(pendingTasks = listOf(steer)))
        assertFalse(repo.applyDecision(draft, task.id.value, decision, listOf(done), listOf(resultDelivery)))
        assertNull(repo.findDraft("draft")?.decision)
        assertEquals(r, repo.findRequest("r"))
        assertEquals(listOf(delivery), repo.pendingDeliveries())
    }
    @Test fun `changed branch and cancellation invalidate review`() = database { source, repo ->
        repo.create(r, delivery); repo.saveDraft(draft); writeScheduling(source, scheduling)
        source.connection.use { c -> c.createStatement().use { it.execute("UPDATE conversations SET current_thread_id='new-thread' WHERE id='b'") } }
        assertFalse(repo.applyDecision(draft, task.id.value, decision, listOf(done), listOf(resultDelivery)))
        source.connection.use { c -> c.createStatement().use { it.execute("UPDATE conversations SET current_thread_id='b-thread' WHERE id='b'") } }
        assertTrue(repo.change(listOf(r), listOf(r.copy(state = AgentRequest.State.CANCELLED, revision = 1)), emptyList()))
        assertFalse(repo.applyDecision(draft, task.id.value, decision, listOf(done), listOf(resultDelivery)))
    }
    @Test fun `failed delivery insert rolls back request creation`() = database { _, repo ->
        assertFails { repo.create(r, delivery.copy(target = b.copy(conversationId = Conversation.Id("missing")))) }
        assertNull(repo.findRequest("r"))
        assertTrue(repo.pendingDeliveries().isEmpty())
    }
    private fun database(block: suspend (PGSimpleDataSource, PostgresAgentCollaborationRepository) -> Unit) = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "agent_collab_" + UUID.randomUUID().toString().replace("-", "")
        val admin = source()
        admin.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val source = source(schema)
            source.connection.use { c -> c.createStatement().use {
                it.execute("CREATE TABLE conversations(id TEXT PRIMARY KEY, current_thread_id TEXT NOT NULL)")
                it.execute("INSERT INTO conversations VALUES ('a','a-thread'),('b','b-thread')")
                it.execute("CREATE TABLE conversation_runtime_records(conversation_id TEXT PRIMARY KEY, scheduling JSONB NOT NULL)")
                it.execute(checkNotNull(javaClass.classLoader.getResource("db/migration/postgres/V65__cross_thread_collaboration.sql")).readText())
            } }
            block(source, PostgresAgentCollaborationRepository(source, json))
        } finally { admin.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } } }
    }
    private fun writeScheduling(source: PGSimpleDataSource, state: ConversationRuntimeSchedulingState) {
        source.connection.use { c -> c.prepareStatement("INSERT INTO conversation_runtime_records VALUES ('b',?::jsonb) ON CONFLICT(conversation_id) DO UPDATE SET scheduling=excluded.scheduling").use {
            it.setString(1, json.encodeToString(state)); it.executeUpdate()
        } }
    }
    private fun source(schema: String? = null) = PGSimpleDataSource().apply {
        setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
        user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
        password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        currentSchema = schema
    }
}
