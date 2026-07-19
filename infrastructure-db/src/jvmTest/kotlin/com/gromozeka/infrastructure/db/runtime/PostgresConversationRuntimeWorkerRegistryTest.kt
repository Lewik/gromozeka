package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresConversationRuntimeWorkerRegistryTest {
    @Test
    fun `postgres registry rejects split brain and fences a stale session`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "runtime_worker_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val registryDataSource = dataSource(schema)
            createWorkerRegistrySchema(registryDataSource)
            val registry = PostgresConversationRuntimeWorkerRegistry(registryDataSource)
            val first = worker("shared-worker", "session-1")
            val second = worker("shared-worker", "session-2")

            assertTrue(
                registry.register(
                    registration(first, Instant.fromEpochMilliseconds(10_000)),
                    staleBefore = Instant.fromEpochMilliseconds(0),
                )
            )
            assertFalse(
                registry.register(
                    registration(second, Instant.fromEpochMilliseconds(20_000)),
                    staleBefore = Instant.fromEpochMilliseconds(5_000),
                )
            )
            assertTrue(
                registry.register(
                    registration(second, Instant.fromEpochMilliseconds(40_000)),
                    staleBefore = Instant.fromEpochMilliseconds(20_000),
                )
            )

            assertFalse(registry.heartbeat(first, Instant.fromEpochMilliseconds(41_000)))
            assertFalse(registry.unregister(first, Instant.fromEpochMilliseconds(41_000)))
            assertTrue(registry.heartbeat(second, Instant.fromEpochMilliseconds(42_000)))
            assertEquals(second, registry.find(second.workerId)?.identity)
            assertEquals(Instant.fromEpochMilliseconds(42_000), registry.find(second.workerId)?.lastHeartbeatAt)
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun dataSource(schema: String? = null): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = schema
        }

    private fun createWorkerRegistrySchema(dataSource: DataSource) {
        val migration = checkNotNull(
            javaClass.classLoader.getResource("db/migration/postgres/V6__conversation_runtime_workers.sql")
        ).readText()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                migration
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(statement::execute)
            }
        }
    }

    private fun worker(
        workerId: String,
        sessionId: String,
    ): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(workerId),
            sessionId = ConversationRuntimeWorkerSessionId(sessionId),
        )

    private fun registration(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): ConversationRuntimeWorkerRegistration =
        ConversationRuntimeWorkerRegistration(
            identity = identity,
            capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            tools = emptyList(),
            version = "test",
            startedAt = at,
            lastHeartbeatAt = at,
        )
}
