package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresBackgroundActivityOriginRepositoryTest {
    @Test
    fun `origins survive repository restart remain immutable and are conversation scoped`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "activity_origin_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val dataSource = source().apply { currentSchema = schema }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE conversations (id VARCHAR(255) PRIMARY KEY)")
                    statement.execute("INSERT INTO conversations VALUES ('conversation')")
                    val migration = requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V67__background_activity_origins.sql")).readText()
                    statement.execute(migration)
                }
            }
            val origin = BackgroundActivityOrigin(BackgroundActivityOrigin.Kind.COMMAND, "command",
                Conversation.Id("conversation"), AgentDefinition.Id("agent"), User.Id("alice"),
                ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), "request", "call")
            val repository = PostgresBackgroundActivityOriginRepository(dataSource)
            repository.bind(origin)
            repository.bind(origin)
            val restarted = PostgresBackgroundActivityOriginRepository(dataSource)
            assertEquals(origin, restarted.find(origin.conversationId, setOf(origin.key))[origin.key])
            assertTrue(restarted.find(Conversation.Id("another"), setOf(origin.key)).isEmpty())
            assertFailsWith<IllegalStateException> { restarted.bind(origin.copy(actorUserId = User.Id("bob"))) }
            assertFailsWith<IllegalStateException> { restarted.bind(origin.copy(activityId = "second")) }
            assertEquals(origin, restarted.find(origin.conversationId, setOf(origin.key))[origin.key])
            dataSource.connection.use { it.createStatement().use { it.execute("DELETE FROM conversations WHERE id = 'conversation'") } }
            assertTrue(restarted.find(origin.conversationId, setOf(origin.key)).isEmpty())
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
