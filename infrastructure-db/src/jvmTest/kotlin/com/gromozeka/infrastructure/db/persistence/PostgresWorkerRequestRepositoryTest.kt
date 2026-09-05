package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.StoredWorkerRequest
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PostgresWorkerRequestRepositoryTest {
    @Test
    fun `queue survives repository restart encrypts payload and fences completion`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "worker_requests_test_${UUID.randomUUID().toString().replace("-", "")}"
        val directory = Files.createTempDirectory("worker-request-cipher-test-")
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
                    statement.execute("CREATE TABLE workers (id VARCHAR(255) PRIMARY KEY, status VARCHAR(32) NOT NULL)")
                    statement.execute("CREATE TABLE users (id VARCHAR(255) PRIMARY KEY)")
                    statement.execute("CREATE TABLE projects (id VARCHAR(255) PRIMARY KEY)")
                    statement.execute("INSERT INTO workers VALUES ('worker', 'ACTIVE'), ('other', 'ACTIVE')")
                    requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V50__durable_worker_requests.sql"))
                        .readText().split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
            }
            val settings = Proxy.newProxyInstance(SettingsProvider::class.java.classLoader, arrayOf(SettingsProvider::class.java)) { _, method, _ ->
                check(method.name == "getHomeDirectory")
                directory.toString()
            } as SettingsProvider
            val repository = PostgresWorkerRequestRepository(dataSource, SecretCipher(settings))
            val now = Clock.System.now()
            val workerId = ConversationRuntimeWorkerId("worker")
            val request = StoredWorkerRequest("request", workerId, "private-request-secret".encodeToByteArray(), now, now + 30.seconds)
            repository.create(request)
            assertEquals(listOf("request"), repository.pending(workerId, 10).map { it.id })
            val restarted = PostgresWorkerRequestRepository(dataSource, SecretCipher(settings))
            assertContentEquals(request.request, restarted.find(request.id)?.request)
            assertTrue(restarted.markDispatched(request.id, now))
            val response = "private-response-secret".encodeToByteArray()
            assertFalse(restarted.complete(workerId, request.id, response, now, onlyIfUndispatched = true))
            assertFalse(restarted.complete(ConversationRuntimeWorkerId("other"), request.id, response, now))
            restarted.cancel(request.id, now)
            assertTrue(restarted.pending(workerId, 10).single().cancelRequested)
            assertTrue(restarted.complete(workerId, request.id, response, now))
            assertFalse(restarted.complete(workerId, request.id, byteArrayOf(9), now))
            assertTrue(restarted.pending(workerId, 10).isEmpty())
            assertContentEquals(response, repository.find(request.id)?.response)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT request_ciphertext, response_ciphertext FROM worker_requests").use { rows ->
                        rows.next()
                        assertFalse(rows.getString(1).contains("private-request-secret"))
                        assertFalse(rows.getString(2).contains("private-response-secret"))
                    }
                }
            }
            dataSource.connection.use { it.createStatement().use { it.execute("INSERT INTO projects VALUES ('deleted-project')") } }
            repository.create(request.copy(id = "deleted-project-request", projectId = com.gromozeka.domain.model.Project.Id("deleted-project")))
            dataSource.connection.use { it.createStatement().use { it.execute("DELETE FROM projects WHERE id = 'deleted-project'") } }
            assertEquals("deleted-project", repository.find("deleted-project-request")?.projectId?.value)
            assertTrue(repository.complete(workerId, "deleted-project-request", response, now, onlyIfUndispatched = true))
            repeat(256) { index -> repository.create(request.copy(id = "expired-$index", createdAt = now - 60.seconds, startDeadline = now - 30.seconds)) }
            repeat(256) { index -> repository.create(request.copy(id = "live-$index", startDeadline = now + 300.seconds)) }
            assertFailsWith<IllegalArgumentException> { repository.create(request.copy(id = "queue-full")) }
            repository.cancel("live-0", now)
            repository.create(request.copy(id = "cancelled-slot-reused"))
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
