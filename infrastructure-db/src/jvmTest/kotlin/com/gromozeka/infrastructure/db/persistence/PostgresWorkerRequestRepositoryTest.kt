package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.StoredWorkerRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import com.gromozeka.domain.model.ApplicationInstallation
import com.gromozeka.domain.model.UpdateComponent
import com.gromozeka.domain.model.UpdatePlatform
import com.gromozeka.domain.model.User
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
    fun `queue survives repository restart encrypts payload and fences completion`(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Requires isolated PostgreSQL test configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
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
                    statement.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V68__development_slots.sql")).readText())
                    statement.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V77__worker_request_retention.sql")).readText())
                    statement.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V86__application_updates.sql")).readText())
                }
            }
            val settings = Proxy.newProxyInstance(SettingsProvider::class.java.classLoader, arrayOf(SettingsProvider::class.java)) { _, method, _ ->
                check(method.name == "getHomeDirectory")
                directory.toString()
            } as SettingsProvider
            val repository = PostgresWorkerRequestRepository(dataSource, SecretCipher(settings))
            val now = Clock.System.now()
            val installations = PostgresApplicationUpdateRepository(dataSource)
            val installation = ApplicationInstallation("worker-update-test", User.Id("owner"), "worker", UpdateComponent.WORKER,
                UpdatePlatform.WINDOWS_X64, "Test Worker", "0", now.toString())
            installations.modify(installation.id, installation) { it }
            (1..16).map {
                async(Dispatchers.IO) {
                    PostgresApplicationUpdateRepository(dataSource).modify(installation.id) { current ->
                        current.copy(version = (current.version.toInt() + 1).toString())
                    }
                }
            }.awaitAll()
            assertEquals("16", PostgresApplicationUpdateRepository(dataSource).find(installation.id)?.version)
            assertFailsWith<IllegalStateException> { installations.modify(installation.id) { error("Synthetic transaction failure") } }
            assertEquals("16", installations.list().single().version)
            val workerId = ConversationRuntimeWorkerId("worker")
            val request = StoredWorkerRequest("request", workerId, "private-request-secret".encodeToByteArray(), now, now + 30.seconds, slotOrigin = com.gromozeka.domain.slot.SlotCommandOrigin("slot", 7, "lease"), executionDeadline = now + 60.seconds)
            repository.create(request)
            assertEquals(listOf("request"), repository.pending(workerId, 10).map { it.id })
            val restarted = PostgresWorkerRequestRepository(dataSource, SecretCipher(settings))
            assertContentEquals(request.request, restarted.find(request.id)?.request)
            assertEquals(request.slotOrigin, restarted.find(request.id)?.slotOrigin)
            assertEquals(request.slotOrigin, restarted.pendingForSlot(7).single().origin)
            assertTrue(restarted.markDispatched(request.id, now))
            val response = "private-response-secret".encodeToByteArray()
            assertFalse(restarted.complete(workerId, request.id, response, now, onlyIfUndispatched = true))
            assertFalse(restarted.complete(ConversationRuntimeWorkerId("other"), request.id, response, now))
            restarted.cancel(request.id, now)
            assertTrue(restarted.pending(workerId, 10).single().cancelRequested)
            assertTrue(restarted.complete(workerId, request.id, response, now))
            assertFalse(restarted.complete(workerId, request.id, byteArrayOf(9), now))
            assertTrue(restarted.pending(workerId, 10).isEmpty())
            assertTrue(restarted.pendingForSlot(7).isEmpty())
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
            assertTrue("request" in restarted.unprojectedCompletions(10))
            assertEquals(0, restarted.deleteCompletedBefore(now + 3_600.seconds, 10))
            restarted.markCompletionProjected("request", now)
            assertEquals(1, restarted.deleteCompletedBefore(now + 3_600.seconds, 10))
            kotlin.test.assertNull(restarted.find("request"))
            kotlin.test.assertNotNull(restarted.find("live-1"))

        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
