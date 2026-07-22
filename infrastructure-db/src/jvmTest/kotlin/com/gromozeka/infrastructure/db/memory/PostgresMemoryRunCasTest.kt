package com.gromozeka.infrastructure.db.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresMemoryRunCasTest {
    @Test
    fun `only one worker can replace the same queued run snapshot`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "memory_run_cas_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val storeDataSource = dataSource(schema)
            createMemoryRunsTable(storeDataSource)
            val store = PostgresMemoryStore(
                dataSource = storeDataSource,
                json = Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                },
            )
            val queued = MemoryRun(
                id = MemoryRun.Id("run-1"),
                namespace = MemoryNamespace.Global,
                runType = MemoryRun.Type.REMEMBER,
                summary = "Queued",
                status = MemoryRun.Status.QUEUED,
                createdAt = Instant.fromEpochMilliseconds(1_000),
            )
            store.apply(MemoryUpdateBatch(runs = listOf(queued)))

            val firstClaim = queued.copy(
                status = MemoryRun.Status.RUNNING,
                executionLease = MemoryRun.ExecutionLease(
                    ownerId = "worker-1",
                    ownerSessionId = "session-1",
                    expiresAt = Instant.fromEpochMilliseconds(10_000),
                ),
                startedAt = Instant.fromEpochMilliseconds(2_000),
            )
            val competingClaim = firstClaim.copy(
                executionLease = MemoryRun.ExecutionLease(
                    ownerId = "worker-2",
                    ownerSessionId = "session-2",
                    expiresAt = Instant.fromEpochMilliseconds(10_000),
                ),
            )

            assertTrue(store.replaceRunIfUnchanged(queued, firstClaim))
            assertFalse(store.replaceRunIfUnchanged(queued, competingClaim))
            assertEquals(firstClaim, store.findRunById(queued.id))
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    @Test
    fun `metadata key query returns only runs awaiting delivery`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "memory_run_metadata_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val storeDataSource = dataSource(schema)
            createMemoryRunsTable(storeDataSource)
            val store = PostgresMemoryStore(
                dataSource = storeDataSource,
                json = Json { ignoreUnknownKeys = true },
            )
            val createdAt = Instant.fromEpochMilliseconds(1_000)
            val awaitingDelivery = MemoryRun(
                id = MemoryRun.Id("awaiting-delivery"),
                namespace = MemoryNamespace.Global,
                runType = MemoryRun.Type.ANSWER_QUESTION,
                summary = "Completed",
                metadata = buildJsonObject { put("resultDelivery", "conversation_runtime") },
                status = MemoryRun.Status.SUCCESS,
                createdAt = createdAt,
                completedAt = createdAt,
            )
            val alreadyScheduled = awaitingDelivery.copy(
                id = MemoryRun.Id("already-scheduled"),
                metadata = buildJsonObject {
                    put("resultDelivery", "conversation_runtime")
                    put("resultDeliveryState", "scheduled")
                },
            )
            val externalRun = awaitingDelivery.copy(
                id = MemoryRun.Id("external-run"),
                metadata = buildJsonObject {},
            )
            store.apply(MemoryUpdateBatch(runs = listOf(awaitingDelivery, alreadyScheduled, externalRun)))

            val runs = store.findRunsByMetadataKeys(
                statuses = setOf(MemoryRun.Status.SUCCESS),
                runTypes = setOf(MemoryRun.Type.ANSWER_QUESTION),
                requiredKey = "resultDelivery",
                absentKey = "resultDeliveryState",
            )

            assertEquals(listOf(awaitingDelivery.id), runs.map { it.id })
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

    private fun createMemoryRunsTable(dataSource: PGSimpleDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE memory_runs (
                        id TEXT PRIMARY KEY,
                        namespace TEXT NOT NULL,
                        payload JSONB NOT NULL,
                        parent_run_id TEXT NULL,
                        status TEXT NOT NULL,
                        run_type TEXT NOT NULL,
                        search_text TEXT NULL,
                        created_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
