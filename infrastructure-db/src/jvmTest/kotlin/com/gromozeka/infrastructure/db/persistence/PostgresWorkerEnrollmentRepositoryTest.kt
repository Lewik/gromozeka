package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
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

            repository.issue(firstTokenHash, owner, createdAt, expiresAt)
            val worker = repository.consume(
                firstTokenHash,
                ConversationRuntimeWorkerId("worker-1"),
                "Worker 1",
                consumedAt,
            )

            assertEquals(owner, worker?.ownerUserId)
            assertNull(
                repository.consume(
                    firstTokenHash,
                    ConversationRuntimeWorkerId("worker-2"),
                    "Worker 2",
                    consumedAt,
                )
            )

            repository.issue(secondTokenHash, otherOwner, createdAt, expiresAt)
            assertFailsWith<IllegalArgumentException> {
                repository.consume(
                    secondTokenHash,
                    ConversationRuntimeWorkerId("worker-1"),
                    "Worker 1",
                    consumedAt,
                )
            }
            assertEquals(
                otherOwner,
                repository.consume(
                    secondTokenHash,
                    ConversationRuntimeWorkerId("worker-2"),
                    "Worker 2",
                    consumedAt,
                )?.ownerUserId,
            )
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
                statement.execute("CREATE TABLE users (id VARCHAR(255) PRIMARY KEY)")
                statement.execute("CREATE TABLE projects (id VARCHAR(255) PRIMARY KEY)")
                statement.execute("INSERT INTO users(id) VALUES ('owner'), ('other-owner')")
                val migration = checkNotNull(
                    javaClass.classLoader.getResource("db/migration/postgres/V27__worker_access.sql")
                ).readText()
                migration
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(statement::execute)
            }
        }
    }
}
