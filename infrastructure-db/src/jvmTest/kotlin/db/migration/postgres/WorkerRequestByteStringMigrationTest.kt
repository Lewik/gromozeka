package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkerRequestByteStringMigrationTest {
    @Test
    fun `protocol cutover drops old RPC envelopes once and preserves conversation history`() {
        org.junit.Assume.assumeTrue("Requires isolated PostgreSQL test configuration",
            System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "byte_string_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "$schema,public"
        }
        fun flyway(target: String) = Flyway.configure().dataSource(source)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/postgres")
            .target(target).load()
        val message = Conversation.Message(
            id = Conversation.Message.Id("kept-message"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Keep this conversation history")),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        try {
            flyway("79").migrate()
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO users(id,display_name,status,created_at,updated_at) VALUES ('owner','Owner','ACTIVE',now(),now())")
                    statement.execute("INSERT INTO workers(id,display_name,owner_user_id,status,created_at,updated_at) VALUES ('worker','Worker','owner','ACTIVE',now(),now())")
                    statement.execute("INSERT INTO projects(id,name,created_at,last_used_at) VALUES ('project','Test',now(),now())")
                    statement.execute("INSERT INTO conversations(id,project_id,display_name,current_thread_id,created_at,updated_at) VALUES ('conversation','project','Test','thread',now(),now())")
                    statement.execute("INSERT INTO threads(id,conversation_id,created_at,updated_at) VALUES ('thread','conversation',now(),now())")
                }
                connection.prepareStatement("INSERT INTO messages(id,conversation_id,role,created_at,message_json) VALUES ('kept-message','conversation','USER',now(),?)").use {
                    it.setString(1, Json.encodeToString(message))
                    it.executeUpdate()
                }
                connection.createStatement().use {
                    it.execute("INSERT INTO thread_messages(thread_id,message_id,position) VALUES ('thread','kept-message',0)")
                }
            }
            listOf("pending", "dispatched", "completed").forEach { insertRequest(source, it) }
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("UPDATE worker_requests SET dispatched_at=now() WHERE id IN ('dispatched','completed')")
                    it.execute("UPDATE worker_requests SET completed_at=now(),response_ciphertext='discarded-response',response_nonce='discarded-nonce',response_version=1 WHERE id='completed'")
                }
            }
            assertEquals(3, count(source, "SELECT count(*) FROM worker_requests"))
            val upgrade = flyway("80")
            assertEquals(1, upgrade.migrate().migrationsExecuted)
            upgrade.validate()
            assertEquals(0, count(source, "SELECT count(*) FROM worker_requests"))
            assertEquals(1, count(source, "SELECT count(*) FROM workers WHERE id='worker'"))
            assertEquals(1, count(source, "SELECT count(*) FROM users WHERE id='owner'"))
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT message_json FROM messages WHERE id='kept-message'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(message, Json.decodeFromString<Conversation.Message>(rows.getString(1)))
                    }
                    statement.executeQuery("SELECT message_id FROM thread_messages WHERE thread_id='thread' ORDER BY position").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("kept-message", rows.getString(1))
                    }
                }
            }
            insertRequest(source, "after-upgrade")
            assertEquals(0, flyway("80").migrate().migrationsExecuted)
            assertEquals(1, count(source, "SELECT count(*) FROM worker_requests WHERE id='after-upgrade'"))
        } finally {
            source.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }

    private fun insertRequest(source: PGSimpleDataSource, id: String) {
        source.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO worker_requests(id,worker_id,request_ciphertext,request_nonce,request_version,created_at,start_deadline)
                VALUES (?,'worker','discarded-request','discarded-nonce',1,now(),now()+interval '1 minute')
            """.trimIndent()).use {
                it.setString(1, id)
                it.executeUpdate()
            }
        }
    }

    private fun count(source: PGSimpleDataSource, sql: String): Int = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }
}
