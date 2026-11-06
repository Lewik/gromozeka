package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Timestamp
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PostgresConversationSessionMetadataTest {
    @Test
    fun `metadata follows thread position and reads no message bodies`(): Unit = runBlocking {
        assumeTrue("Requires an explicitly configured isolated PostgreSQL test instance", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "session_metadata_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD").orEmpty()
            currentSchema = schema
        }
        try {
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA $schema")
                    statement.execute("CREATE TABLE threads (id VARCHAR(255) PRIMARY KEY, updated_at TIMESTAMPTZ)")
                    statement.execute("CREATE TABLE messages (id VARCHAR(255) PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL, role TEXT NOT NULL, message_json TEXT NOT NULL)")
                    statement.execute("CREATE TABLE thread_messages (thread_id VARCHAR(255) REFERENCES threads(id), message_id VARCHAR(255) REFERENCES messages(id), position INT NOT NULL, PRIMARY KEY(thread_id,message_id))")
                    statement.execute("CREATE INDEX ON thread_messages(thread_id,position)")
                    statement.execute("INSERT INTO threads(id,updated_at) VALUES ('a','2099-01-01T00:00:00Z'),('fork','2099-01-01T00:00:00Z'),('empty','2099-01-01T00:00:00Z'),('unrelated','2099-01-01T00:00:00Z')")
                }
                connection.prepareStatement("INSERT INTO messages(id,created_at,role,message_json) VALUES (?,?,?,?)").use { statement ->
                    for ((id, at, role) in listOf(
                        Triple("old", "2026-11-01T00:00:00Z", "USER"),
                        Triple("newer", "2026-11-04T00:00:00Z", "ASSISTANT"),
                        Triple("last-in-order", "2026-11-02T00:00:00Z", "SYSTEM"),
                        Triple("foreign", "2099-01-01T00:00:00Z", "USER"),
                    )) {
                        statement.setString(1, id)
                        statement.setTimestamp(2, Timestamp.from(java.time.Instant.parse(at)))
                        statement.setString(3, role)
                        // This deliberately cannot be deserialized; the query must use scalar metadata only.
                        statement.setString(4, "PRIVATE_INVALID_JSON_BODY")
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.createStatement().use { it.execute("INSERT INTO thread_messages VALUES ('a','old',0),('a','newer',1),('a','last-in-order',2),('fork','old',0),('fork','newer',1),('unrelated','foreign',0)") }
            }
            val repository = PostgresConversationHistoryRepository(source, Json)
            val a = Conversation.Thread.Id("a")
            val fork = Conversation.Thread.Id("fork")
            val empty = Conversation.Thread.Id("empty")
            assertEquals(mapOf(a to Instant.parse("2026-11-02T00:00:00Z"), fork to Instant.parse("2026-11-04T00:00:00Z"), empty to null),
                repository.latestMessageTimes(setOf(a, fork, empty)))
            assertTrue(repository.latestMessageTimes(emptySet()).isEmpty())
            assertTrue(repository.latestMessageTimes(setOf(Conversation.Thread.Id("missing"))).isEmpty())
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
