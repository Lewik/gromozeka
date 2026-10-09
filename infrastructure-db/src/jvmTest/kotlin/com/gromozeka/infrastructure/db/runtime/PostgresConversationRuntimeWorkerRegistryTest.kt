package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
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
            assertEquals(
                workerEnvironmentProfile(Instant.fromEpochMilliseconds(40_000)),
                registry.find(second.workerId)?.environmentProfile,
            )
            val command = AiToolDescriptor(
                AiToolDefinition("grz_execute_command", "Execute a command", "{}"),
                PreloadedWorkspaceToolMetadata,
            )
            assertTrue(registry.updateTools(second, listOf(command), Instant.fromEpochMilliseconds(43_000)))
            val beforeMigration = registry.find(second.workerId)
            registryDataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE conversation_runtime_workers SET registration_json = jsonb_set(registration_json, " +
                        "'{tools,0,metadata,supportsSlotContext}', 'true') WHERE worker_id = ?"
                ).use { statement ->
                    statement.setString(1, second.workerId.value)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            applyMigration(registryDataSource, "V36__ai_tool_contracts.sql")
            applyMigration(registryDataSource, "V69__remove_slot_support_metadata.sql")
            assertEquals(beforeMigration, registry.find(second.workerId))
            assertTrue(registry.heartbeat(second, Instant.fromEpochMilliseconds(44_000)))
            applyMigration(registryDataSource, "V69__remove_slot_support_metadata.sql")
            assertEquals(listOf(command), registry.find(second.workerId)?.tools)
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

    private fun createWorkerRegistrySchema(dataSource: DataSource) =
        applyMigration(dataSource, "V6__conversation_runtime_workers.sql")

    private fun applyMigration(dataSource: DataSource, name: String) {
        val migration = checkNotNull(
            javaClass.classLoader.getResource("db/migration/postgres/$name")
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
            capabilities = setOf(
                ConversationRuntimeCapability.TOOL_EXECUTION,
                ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
            ),
            tools = emptyList(),
            environmentProfile = workerEnvironmentProfile(at),
            version = "test",
            startedAt = at,
            lastHeartbeatAt = at,
        )

    private fun workerEnvironmentProfile(observedAt: Instant): WorkerEnvironmentProfile =
        WorkerEnvironmentProfile(
            observedAt = observedAt,
            operatingSystem = WorkerOperatingSystem(
                family = WorkerOperatingSystem.Family.LINUX,
                name = "Test Linux",
                version = "1",
            ),
            architecture = "x86_64",
            nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/bin/sh"),
            timezoneId = "UTC",
            localeTag = "en-US",
            logicalProcessorCount = 4,
            totalMemoryBytes = 8_589_934_592,
            availableExecutables = listOf("sh"),
        )
}
