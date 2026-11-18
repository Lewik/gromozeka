package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.infrastructure.db.runtime.PostgresConversationRuntimeCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PostgresAsyncToolDeliveryTest {
    @Test fun `async outcome acknowledgement and continuation commit together across restart`() = runBlocking {
        database { f ->
            val outcome = f.continuation()
            assertTrue(f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, outcome))
            assertNotNull(f.operations().find(f.operation.id)?.projectedAt)
            assertEquals(outcome.nextTask.id, f.coordinator().schedulingSnapshot(f.conversation).continuationTask?.id)
        }
    }

    @Test fun `missing or differently owned outcome cannot partially consume an async packet`() = runBlocking {
        database { f ->
            val invalid = f.continuation().copy(backgroundDeliveryReceipts = listOf(BackgroundActivityDeliveryReceipt(
                asyncTools = setOf(f.operation.id, AsyncToolOperation.Id("missing")))))
            assertFailsWith<IllegalStateException> { f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, invalid) }
            f.assertUnconsumed()
            f.sql("UPDATE async_tool_operations SET actor_user_id='another-owner'")
            assertFailsWith<IllegalStateException> { f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, f.continuation()) }
            f.sql("UPDATE async_tool_operations SET actor_user_id='actor'")
            f.assertUnconsumed()
        }
    }

    @Test fun `failed continuation write rolls back async acknowledgement on the same connection`() = runBlocking {
        database { f ->
            f.sql("""CREATE FUNCTION reject_async_continuation() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN IF NEW.scheduling -> 'continuationTask' ->> 'id' = 'next' THEN RAISE EXCEPTION 'Synthetic write failure'; END IF; RETURN NEW; END
                ${'$'}${'$'}""")
            f.sql("CREATE TRIGGER reject_async_continuation BEFORE UPDATE ON conversation_runtime_records FOR EACH ROW EXECUTE FUNCTION reject_async_continuation()")
            assertFailsWith<Exception> { f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, f.continuation()) }
            f.assertUnconsumed()
            f.sql("DROP TRIGGER reject_async_continuation ON conversation_runtime_records")
            assertTrue(f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, f.continuation()))
            assertNotNull(f.operations().find(f.operation.id)?.projectedAt)
        }
    }

    @Test fun `Stop defers delivery without cancelling or acknowledging a completed async invocation`() = runBlocking {
        database { f ->
            assertTrue(f.coordinator().requestStop(f.conversation))
            assertTrue(f.coordinator().completeActiveTask(f.conversation, f.delivery.id, f.executor, f.continuation()))
            assertNull(f.operations().find(f.operation.id)?.projectedAt)
            assertEquals(AsyncToolOperation.Phase.SUCCEEDED, f.operations().find(f.operation.id)?.phase)
            val snapshot = f.coordinator().schedulingSnapshot(f.conversation)
            assertNull(snapshot.continuationTask)
            assertEquals(ConversationExecutionState.ControlState.PAUSED, snapshot.state?.controlState)
            assertEquals(f.delivery.id, f.coordinator().listPending(f.conversation).single().id)
        }
    }

    private class Fixture(val source: PGSimpleDataSource, home: String) {
        val now = Clock.System.now()
        val actor = User.Id("actor")
        val conversation = Conversation.Id("async-delivery")
        val agent = AgentDefinition.Id("agent")
        val owner = ConversationRuntimeServerSessionId("owner")
        val executor = ConversationRuntimeExecutorIdentity.Server(owner)
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private val settings = Proxy.newProxyInstance(SettingsProvider::class.java.classLoader, arrayOf(SettingsProvider::class.java)) { _, method, _ ->
            check(method.name == "getHomeDirectory"); home
        } as SettingsProvider
        fun operations() = PostgresAsyncToolOperationRepository(source, SecretCipher(settings))
        fun coordinator() = PostgresConversationRuntimeCoordinator(source, json)
        lateinit var operation: AsyncToolOperation
        val delivery = ConversationRuntimeTask(ConversationRuntimeTask.Id("delivery"), conversation, actorUserId = actor,
            payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("async-tool:outcome", agent),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "delivery",
            requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = now)
        fun continuation(): ConversationRuntimeTaskOutcome.Continue = ConversationRuntimeTaskOutcome.Continue(
            delivery.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = delivery.id, idempotencyKey = "next",
                payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), agent, 1)),
            backgroundDeliveryReceipts = listOf(BackgroundActivityDeliveryReceipt(asyncTools = setOf(operation.id))))
        suspend fun initialize() {
            val origin = AsyncToolOrigin(actor, Project.Id("project"), conversation, Conversation.Thread.Id("thread"), agent,
                ConversationRuntimeTask.Id("source"), ContentItem.ToolCall.Id("call"), Conversation.Message.Id("source:result"), false, null)
            val invocation = AsyncToolInvocation(origin, "callback", "a".repeat(64), ContentItem.ToolCall(origin.sourceToolCallId,
                ContentItem.ToolCall.Data("callback", JsonObject(emptyMap()))), ConversationRuntimeTaskTarget.Server, mapOf(
                TOOL_CONTEXT_USER_ID to actor.value, TOOL_CONTEXT_PROJECT_ID to "project", TOOL_CONTEXT_CONVERSATION_ID to conversation.value,
                TOOL_CONTEXT_THREAD_ID to "thread", TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.value, "toolCallId" to "call"))
            val repository = operations()
            operation = repository.admit(AsyncToolOperation.Id("outcome"), invocation, now)
            assertTrue(repository.acknowledge(operation.id, now))
            val claimed = assertNotNull(repository.claim(operation.id, owner, now, now + 30.seconds))
            assertTrue(repository.markStarted(claimed, now))
            assertTrue(repository.finish(assertNotNull(repository.find(operation.id)), AsyncToolOperation.Phase.SUCCEEDED,
                AsyncToolOutcome(listOf(ContentItem.ToolResult.Data.Text("durable body")), isError = false), now))
            operation = assertNotNull(repository.find(operation.id))
            val runtime = coordinator()
            assertTrue(runtime.submit(delivery))
            assertEquals(delivery, runtime.claimDeliveredTask(conversation, delivery.id, executor, delivery.requirements.capabilities, emptySet()))
            assertTrue(runtime.markActiveTaskStarted(conversation, delivery.id, executor, now))
        }
        suspend fun assertUnconsumed() {
            assertNull(operations().find(operation.id)?.projectedAt)
            val snapshot = coordinator().schedulingSnapshot(conversation)
            assertEquals(delivery.id, snapshot.activeTask?.id)
            assertNull(snapshot.continuationTask)
        }
        fun sql(text: String) = source.connection.use { c -> c.createStatement().use { it.execute(text) } }
    }

    private suspend fun database(block: suspend (Fixture) -> Unit) {
        org.junit.Assume.assumeTrue("Requires isolated PostgreSQL", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        fun source() = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "async_delivery_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = source()
        val home = Files.createTempDirectory("async-delivery-cipher-")
        try {
            val scoped = source().apply { currentSchema = "$schema,public" }
            Flyway.configure().dataSource(scoped).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            val f = Fixture(scoped, home.toString())
            f.initialize(); block(f)
        } finally {
            admin.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
