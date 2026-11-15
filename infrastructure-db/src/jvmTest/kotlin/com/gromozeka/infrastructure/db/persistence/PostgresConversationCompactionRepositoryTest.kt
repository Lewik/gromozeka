package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.db.runtime.PostgresConversationRuntimeCoordinator
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresConversationCompactionRepositoryTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val now = Instant.fromEpochMilliseconds(123)

    @Test fun `COW copies commit-time appends retains the original archive and continues turn numbering`() = runBlocking {
        withDatabase { f ->
            val late = f.message("late")
            f.append(late)
            assertTrue(f.commit())
            assertEquals(f.derived.id, f.conversations.findById(f.conversation.id)?.currentThread)
            assertEquals(listOf("first", "second", "late"), f.ids(f.original.id))
            assertEquals(listOf("first", "summary", "second", "late"), f.ids(f.derived.id))
            val persisted = assertNotNull(f.threads.findById(f.derived.id))
            assertEquals(f.original.id, persisted.originalThread)
            assertEquals(17, persisted.lastTurnNumber)
            assertEquals(late, f.messages.findById(late.id))
            assertEquals(f.summary, f.messages.findById(f.summary.id))
        }
    }

    @Test fun `missing insertion anchor rolls back the branch switch and every new row`() = runBlocking {
        withDatabase { f ->
            assertFailsWith<IllegalStateException> { f.commit(Conversation.Message.Id("missing")) }
            assertEquals(f.original.id, f.conversations.findById(f.conversation.id)?.currentThread)
            assertNull(f.threads.findById(f.derived.id))
            assertNull(f.messages.findById(f.summary.id))
            assertEquals(listOf("first", "second"), f.ids(f.original.id))
        }
    }

    @Test fun `a stale source version cannot create another derived thread`() = runBlocking {
        withDatabase { f ->
            assertTrue(f.commit())
            val another = f.derived.copy(id = Conversation.Thread.Id("another"))
            assertFalse(f.repository.commitIfCurrent(f.original.id, f.summary.copy(id = Conversation.Message.Id("another-summary")), another, f.first.id))
            assertNull(f.threads.findById(another.id))
            assertNull(f.messages.findById(Conversation.Message.Id("another-summary")))
            assertEquals(f.derived.id, f.conversations.findById(f.conversation.id)?.currentThread)
        }
    }

    @Test fun `prepared commit and receipt are atomic and cancelled readiness cannot publish`() = runBlocking {
        for (cancelFirst in listOf(false, true)) withDatabase { f ->
            f.installPreparation()
            val coordinator = PostgresConversationRuntimeCoordinator(f.source, json)
            if (cancelFirst) assertTrue(coordinator.requestStop(f.conversation.id))
            assertEquals(!cancelFirst, f.commit(preparedId = f.summary.id.value))
            val preparation = assertNotNull(coordinator.proactiveCompaction(f.conversation.id))
            assertEquals(if (cancelFirst) ProactiveCompaction.Status.CANCELLED else ProactiveCompaction.Status.APPLIED, preparation.status)
            assertNull(preparation.checkpoint)
            if (cancelFirst) {
                assertEquals(f.original.id, f.conversations.findById(f.conversation.id)?.currentThread)
                assertNull(f.messages.findById(f.summary.id))
            } else {
                assertEquals(f.derived.id, f.conversations.findById(f.conversation.id)?.currentThread)
                assertFalse(coordinator.requestStop(f.conversation.id), "A finished commit is not undone by a later idle Stop")
            }
        }
    }

    @Test fun `COW failure rolls back the preparation receipt too`() = runBlocking {
        withDatabase { f ->
            f.installPreparation()
            assertFailsWith<IllegalStateException> { f.commit(Conversation.Message.Id("missing"), f.summary.id.value) }
            val coordinator = PostgresConversationRuntimeCoordinator(f.source, json)
            assertEquals(ProactiveCompaction.Status.READY, coordinator.proactiveCompaction(f.conversation.id)?.status)
            assertEquals(f.original.id, f.conversations.findById(f.conversation.id)?.currentThread)
            assertNull(f.threads.findById(f.derived.id))
            assertNull(f.messages.findById(f.summary.id))
        }
    }

    @Test fun `prepared commit waits behind the Stop transaction and observes its cancellation`() = runBlocking {
        withDatabase { f ->
            val preparation = f.installPreparation()
            f.source.connection.use { blocker ->
                blocker.autoCommit = false
                val cancelled = ConversationRuntimeSchedulingState(f.conversation.id, proactiveCompaction = preparation.cancel(now))
                blocker.prepareStatement("UPDATE conversation_runtime_records SET scheduling = ?::jsonb WHERE conversation_id = ?").use {
                    it.setString(1, json.encodeToString(cancelled)); it.setString(2, f.conversation.id.value); it.executeUpdate()
                }
                val committing = async(Dispatchers.IO) { f.commit(preparedId = f.summary.id.value) }
                try {
                    withTimeout(10_000) {
                        while (true) {
                            val blocked = f.source.connection.use { c -> c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'").use { statement ->
                                statement.setString(1, f.source.applicationName)
                                statement.executeQuery().use { r -> r.next(); r.getInt(1) > 0 }
                            } }
                            if (blocked) break
                            delay(10)
                        }
                    }
                    assertFalse(committing.isCompleted)
                    blocker.commit()
                    assertFalse(committing.await())
                    assertEquals(f.original.id, f.conversations.findById(f.conversation.id)?.currentThread)
                    assertNull(f.messages.findById(f.summary.id))
                } finally { blocker.rollback(); committing.cancel() }
            }
        }
    }

    private inner class Fixture(val source: PGSimpleDataSource) {
        val conversations = ExposedConversationRepository()
        val threads = ExposedThreadRepository()
        val messages = ExposedMessageRepository(json)
        val links = ExposedThreadMessageRepository(json)
        val repository = ExposedConversationCompactionRepository(json)
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(User.Id("actor"))), currentThread = Conversation.Thread.Id("original"), createdAt = now, updatedAt = now)
        val original = Conversation.Thread(conversation.currentThread, conversation.id, lastTurnNumber = 17, createdAt = now, updatedAt = now)
        val derived = Conversation.Thread(Conversation.Thread.Id("derived"), conversation.id, originalThread = original.id, createdAt = now, updatedAt = now)
        val first = message("first")
        val second = message("second")
        val summary = Conversation.Message(Conversation.Message.Id("summary"), conversation.id,
            role = Conversation.Message.Role.ASSISTANT, createdAt = now,
            content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("A complete handoff"),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                strategy = Conversation.Message.ContentItem.ContextCompactionResult.Strategy.SUMMARIZE,
                sourceMessageIds = listOf(first.id), coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
            )))
        fun message(id: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage(id)), createdAt = now)
        suspend fun initialize() {
            conversations.create(conversation)
            threads.save(original)
            append(first); append(second)
        }
        suspend fun append(message: Conversation.Message) {
            messages.save(message)
            links.add(original.id, message.id, (links.getMaxPosition(original.id) ?: -1) + 1)
        }
        suspend fun ids(thread: Conversation.Thread.Id) = links.getByThread(thread).map { it.messageId.value }
        suspend fun commit(anchor: Conversation.Message.Id = first.id, preparedId: String? = null) = repository.commitIfCurrent(original.id, summary, derived, anchor, preparedId)
        fun installPreparation(): ProactiveCompaction {
            val prepared = ProactiveCompaction(summary.id.value, conversation.id, ConversationRuntimeTask.Id("source"), ConversationRuntimeTurnId("turn"),
                ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server")), AgentDefinition.Id("agent"), User.Id("actor"),
                original.id, 2, "a".repeat(64), "b".repeat(64), 100_000, 1, 1, now, status = ProactiveCompaction.Status.READY, checkpoint = summary)
            source.connection.use { c -> c.prepareStatement("INSERT INTO conversation_runtime_records(conversation_id, scheduling) VALUES (?, ?::jsonb)").use { statement ->
                statement.setString(1, conversation.id.value)
                statement.setString(2, json.encodeToString(ConversationRuntimeSchedulingState(conversation.id, proactiveCompaction = prepared)))
                statement.executeUpdate()
            } }
            return prepared
        }
    }

    private suspend fun withDatabase(block: suspend (Fixture) -> Unit) {
        assumeTrue("Requires the isolated PostgreSQL test fixture", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "compaction_test_${UUID.randomUUID().toString().replace("-", "") }"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "$schema,public"
            applicationName = "compaction-$schema"
        }
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            source.connection.use { c -> c.createStatement().use {
                it.execute("INSERT INTO users(id,display_name,status,role,created_at,updated_at) VALUES ('actor','Actor','ACTIVE','MEMBER',now(),now())")
                it.execute("INSERT INTO projects(id,name,created_at,last_used_at) VALUES ('project','Project',now(),now())")
            } }
            Database.connect(source)
            block(Fixture(source).also { it.initialize() })
        } finally {
            source.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
