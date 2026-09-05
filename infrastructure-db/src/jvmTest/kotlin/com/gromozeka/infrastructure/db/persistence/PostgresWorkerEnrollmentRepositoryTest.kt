package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PostgresWorkerEnrollmentRepositoryTest {
    @Test
    fun `enrollment atomically binds a worker id to its owner`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val schema = "worker_enrollment_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val repositoryDataSource = dataSource(schema)
            createSchema(repositoryDataSource)
            val repository = PostgresWorkerEnrollmentRepository(repositoryDataSource)
            val owner = User.Id("owner")
            val otherOwner = User.Id("other-owner")
            val createdAt = Instant.parse("2026-07-29T20:00:00Z")
            val expiresAt = Instant.parse("2026-07-29T20:15:00Z")
            val consumedAt = Instant.parse("2026-07-29T20:01:00Z")
            val firstTokenHash = "a".repeat(64)
            val secondTokenHash = "b".repeat(64)
            val rotationTokenHash = "f".repeat(64)
            val firstCredentialHash = "c".repeat(64)
            val secondCredentialHash = "d".repeat(64)
            val thirdCredentialHash = "e".repeat(64)
            val rotatedCredentialHash = "1".repeat(64)

            repository.issue(firstTokenHash, owner, createdAt, expiresAt)
            val worker = repository.consume(
                firstTokenHash,
                firstCredentialHash,
                ConversationRuntimeWorkerId("worker-1"),
                "Worker 1",
                consumedAt,
                platform = "macos",
                bindToUser = true,
            )

            assertEquals(owner, worker?.ownerUserId)
            assertEquals(owner, worker?.subjectUserId)
            assertEquals("macos", worker?.platform)
            assertEquals(
                ConversationRuntimeWorkerId("worker-1"),
                repository.authenticateGatewayCredential(firstCredentialHash)?.id,
            )
            assertNull(
                repository.consume(
                    firstTokenHash,
                    secondCredentialHash,
                    ConversationRuntimeWorkerId("worker-2"),
                    "Worker 2",
                    consumedAt,
                )
            )

            repository.issue(secondTokenHash, otherOwner, createdAt, expiresAt)
            assertFailsWith<IllegalArgumentException> {
                repository.consume(
                    secondTokenHash,
                    secondCredentialHash,
                    ConversationRuntimeWorkerId("worker-1"),
                    "Worker 1",
                    consumedAt,
                )
            }
            assertEquals(
                otherOwner,
                repository.consume(
                    secondTokenHash,
                    thirdCredentialHash,
                    ConversationRuntimeWorkerId("worker-2"),
                    "Worker 2",
                    consumedAt,
                )?.ownerUserId,
            )

            repository.issue(rotationTokenHash, owner, createdAt, expiresAt)
            repository.consume(
                rotationTokenHash,
                rotatedCredentialHash,
                ConversationRuntimeWorkerId("worker-1"),
                "Worker 1",
                consumedAt,
                platform = "macos",
                bindToUser = true,
            )
            assertNull(repository.authenticateGatewayCredential(firstCredentialHash))
            assertEquals(
                ConversationRuntimeWorkerId("worker-1"),
                repository.authenticateGatewayCredential(rotatedCredentialHash)?.id,
            )

            val connections = PostgresDeviceConnectionRepository(repositoryDataSource)
            val deviceConnection = DeviceConnection(
                id = DeviceConnection.Id("phone-connection"),
                secretHash = "2".repeat(64),
                userCode = "PHONE-CODE",
                deviceLabel = "Phone",
                platform = "android",
                components = setOf(DeviceConnection.Component.WORKER),
                clientLabel = null,
                worker = DeviceConnection.WorkerRequest(ConversationRuntimeWorkerId("phone"), bindToUser = true),
                status = DeviceConnection.Status.PENDING,
                authorizedUserId = null,
                decidedByUserId = null,
                createdAt = createdAt,
                expiresAt = expiresAt,
                decidedAt = null,
                consumedAt = null,
            )
            kotlin.test.assertTrue(connections.create(deviceConnection))
            assertEquals(deviceConnection, connections.findPendingByUserCode(deviceConnection.userCode, consumedAt))
            connections.approve(deviceConnection.userCode, owner, consumedAt)
            val consumption = requireNotNull(connections.consume(deviceConnection.secretHash, consumedAt, null, "3".repeat(64)))
            assertEquals(owner, consumption.worker?.subjectUserId)
            assertEquals("android", consumption.worker?.platform)
            val replay = requireNotNull(connections.consume(deviceConnection.secretHash, consumedAt, null, "3".repeat(64)))
            kotlin.test.assertFalse(replay.newlyConsumed)
            assertEquals(consumption.worker, replay.worker)

            repository.issue("4".repeat(64), owner, createdAt, expiresAt)
            assertFailsWith<IllegalArgumentException> {
                repository.consume("4".repeat(64), "5".repeat(64), worker!!.id, "Worker 1", consumedAt, bindToUser = false)
            }
            assertEquals(owner, repository.authenticateGatewayCredential(rotatedCredentialHash)?.subjectUserId)
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

    private fun createSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE users (id VARCHAR(255) PRIMARY KEY, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE')")
                statement.execute("CREATE TABLE projects (id VARCHAR(255) PRIMARY KEY)")
                statement.execute("INSERT INTO users(id) VALUES ('owner'), ('other-owner')")
                listOf(
                    "db/migration/postgres/V27__worker_access.sql",
                    "db/migration/postgres/V28__worker_gateway_credentials.sql",
                    "db/migration/postgres/V29__rename_worker_runtime_wide_access.sql",
                    "db/migration/postgres/V37__context_state_and_mobile_workers.sql",
                    "db/migration/postgres/V38__device_connections.sql",
                    "db/migration/postgres/V49__unify_workers.sql",
                ).map { resource ->
                    checkNotNull(javaClass.classLoader.getResource(resource)).readText()
                }.forEach { script ->
                    script
                        .split(';')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(statement::execute)
                }
            }
        }
    }
}
