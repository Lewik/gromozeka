package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.infrastructure.db.persistence.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class PostgresRuntimeMessageBatchTest {
    @Test fun `a failed link rolls back every message body and link in the packet`(): Unit = runBlocking {
        database { f ->
            val first = f.message("first")
            val second = f.message("second")
            f.sql.execute("""CREATE FUNCTION reject_second_link() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN IF NEW.message_id = 'second' THEN RAISE EXCEPTION 'Synthetic link failure'; END IF; RETURN NEW; END
                ${'$'}${'$'}""")
            f.sql.execute("CREATE TRIGGER reject_second BEFORE INSERT ON thread_messages FOR EACH ROW EXECUTE FUNCTION reject_second_link()")
            assertFailsWith<Exception> { f.append(listOf(first, second)) }
            assertNull(f.messages.findById(first.id))
            assertNull(f.messages.findById(second.id))
            assertTrue(f.links.getByThread(f.conversation.currentThread).isEmpty())
        }
    }

    @Test fun `packet replay is idempotent and a changed body cannot be accepted as replay`(): Unit = runBlocking {
        database { f ->
            val packet = listOf(f.message("first"), f.message("second"))
            assertEquals(packet, f.append(packet))
            assertTrue(f.append(packet.map { it.copy(createdAt = Clock.System.now()) }).isEmpty())
            assertEquals(packet.map { it.id }, f.links.getByThread(f.conversation.currentThread).map { it.messageId })
            assertFailsWith<IllegalArgumentException> { f.append(listOf(packet.last().copy(content = emptyList()))) }
            assertEquals(packet, f.links.getMessagesByThread(f.conversation.currentThread))
        }
    }

    @Test fun `concurrent packets remain contiguous and cannot split each other`(): Unit = runBlocking {
        database { f ->
            val a = listOf(f.message("a1"), f.message("a2"))
            val b = listOf(f.message("b1"), f.message("b2"))
            coroutineScope {
                listOf(async(Dispatchers.IO) { f.append(a) }, async(Dispatchers.IO) { f.append(b) }).awaitAll()
            }
            val order = f.links.getByThread(f.conversation.currentThread).map { it.messageId.value }
            assertTrue(order == listOf("a1", "a2", "b1", "b2") || order == listOf("b1", "b2", "a1", "a2"), order.toString())
        }
    }

    @Test fun `a thread change fails before publishing a packet into another history`(): Unit = runBlocking {
        database { f ->
            val next = Conversation.Thread(Conversation.Thread.Id("other"), f.conversation.id, createdAt = Clock.System.now(), updatedAt = Clock.System.now())
            f.threads.save(next)
            f.conversations.updateCurrentThread(f.conversation.id, next.id)
            val message = f.message("wrong-history")
            assertFailsWith<IllegalStateException> { f.append(listOf(message)) }
            assertNull(f.messages.findById(message.id))
            assertTrue(f.links.getByThread(next.id).isEmpty())
        }
    }

    private class Fixture(pool: HikariDataSource) {
        val sql = JdbcTemplate(pool)
        val conversations = ExposedConversationRepository()
        val threads = ExposedThreadRepository()
        val messages = ExposedMessageRepository(Json)
        val links = ExposedThreadMessageRepository(Json)
        val writer = ExposedConversationMessageBatchRepository(Json, messages)
        private val at = Clock.System.now()
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(User.Id("user"))), currentThread = Conversation.Thread.Id("thread"), createdAt = at, updatedAt = at)
        suspend fun initialize() {
            ExposedProjectRepository().save(Project(conversation.projectId, "Test", createdAt = at, lastUsedAt = at))
            sql.update("INSERT INTO users(id,display_name,status,created_at,updated_at) VALUES ('user','User','ACTIVE',now(),now())")
            conversations.create(conversation)
            threads.save(Conversation.Thread(conversation.currentThread, conversation.id, createdAt = at, updatedAt = at))
        }
        fun message(id: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(id)), createdAt = at)
        suspend fun append(batch: List<Conversation.Message>) = writer.append(conversation.id, conversation.currentThread, batch, Clock.System.now())
    }

    private suspend fun database(block: suspend (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        val schema = "message_batch_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = DriverManagerDataSource(url, user, password)
        var pool: HikariDataSource? = null
        try {
            Flyway.configure().dataSource(admin).schemas(schema).defaultSchema(schema).locations("classpath:db/migration/postgres").load().migrate()
            pool = HikariDataSource(HikariConfig().apply {
                jdbcUrl = url; username = user; this.password = password; maximumPoolSize = 2
                connectionInitSql = "SET search_path TO \"$schema\", public"
            })
            Database.connect(pool)
            val f = Fixture(pool)
            f.initialize()
            block(f)
        } finally {
            pool?.close()
            JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
