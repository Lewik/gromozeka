package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

class PostgresBackgroundActivityOriginRepositoryTest {
    @Test
    fun `admission is serialized with completion and outlives request retention`() = runBlocking {
        assumeTrue("Requires isolated PostgreSQL test configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "activity_origin_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            applicationName = schema
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val dataSource = source().apply { currentSchema = schema }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE conversations (id VARCHAR(255) PRIMARY KEY)")
                    statement.execute("INSERT INTO conversations VALUES ('conversation')")
                    statement.execute("""CREATE TABLE worker_requests (id TEXT PRIMARY KEY, worker_id TEXT, actor_user_id TEXT,
                        dispatched_at TIMESTAMPTZ, cancel_requested_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, execution_deadline TIMESTAMPTZ)""")
                    statement.execute("""INSERT INTO worker_requests(id,worker_id,actor_user_id,dispatched_at,execution_deadline)
                        VALUES ('request','worker','alice',now(),'2099-01-01'), ('racing','worker','alice',now(),'2099-01-01')""")
                    for (name in listOf("V67__background_activity_origins.sql", "V78__background_launch_admission.sql")) {
                        statement.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/$name")).readText())
                    }
                }
            }
            val at = Clock.System.now()
            val origin = BackgroundActivityOrigin(BackgroundActivityOrigin.Kind.COMMAND, "command",
                Conversation.Id("conversation"), AgentDefinition.Id("agent"), User.Id("alice"),
                ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), "request", "call")
            val repository = PostgresBackgroundActivityOriginRepository(dataSource)
            repository.bindWhileRequestPending(origin, at)
            repository.bindWhileRequestPending(origin, at)
            val restarted = PostgresBackgroundActivityOriginRepository(dataSource)
            assertEquals(origin, restarted.find(origin.conversationId, setOf(origin.key))[origin.key])
            assertTrue(restarted.find(Conversation.Id("another"), setOf(origin.key)).isEmpty())
            assertFailsWith<IllegalArgumentException> { restarted.bindWhileRequestPending(origin.copy(actorUserId = User.Id("bob")), at) }
            assertFailsWith<IllegalStateException> { restarted.bindWhileRequestPending(origin.copy(activityId = "second"), at) }
            dataSource.connection.use { it.createStatement().use { it.execute("DELETE FROM worker_requests WHERE id = 'request'") } }
            restarted.bindWhileRequestPending(origin, at)

            dataSource.connection.use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("UPDATE worker_requests SET completed_at=now() WHERE id='racing'") }
                val raced = origin.copy(activityId = "raced", workerRequestId = "racing")
                val admission = async(Dispatchers.IO) { runCatching { repository.bindWhileRequestPending(raced, at) }.exceptionOrNull() }
                try {
                    withTimeout(5_000) {
                        while (true) {
                            val blocked = admin.connection.use { connection ->
                                connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')").use {
                                    it.setString(1, schema)
                                    it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                                }
                            }
                            if (blocked) break
                            delay(10)
                        }
                    }
                    assertFalse(admission.isCompleted)
                    lock.commit()
                    assertIs<IllegalArgumentException>(admission.await())
                    assertTrue(repository.find(raced.conversationId, setOf(raced.key)).isEmpty())
                } finally {
                    lock.rollback()
                    admission.cancelAndJoin()
                }
            }
            dataSource.connection.use { it.createStatement().use { it.execute("DELETE FROM conversations WHERE id = 'conversation'") } }
            assertTrue(restarted.find(origin.conversationId, setOf(origin.key)).isEmpty())
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
