package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

class PostgresAiAdmissionRepositoryTest {
    @Test
    fun `migration assigns default and real durable leases preserve remote ownership and background handoff`() = runBlocking {
        val url = System.getenv("GROMOZEKA_AI_HTTP_TEST_URL") ?: run {
            println("SKIP: isolated PostgreSQL fixture not configured for AI admission")
            return@runBlocking
        }
        val source = PGSimpleDataSource().apply {
            setURL(url)
            user = System.getenv("GROMOZEKA_AI_HTTP_TEST_USER") ?: "grz_e_test"
            password = System.getenv("GROMOZEKA_AI_HTTP_TEST_PASSWORD")
        }
        val schema = "ai_admission_${UUID.randomUUID().toString().replace("-", "")}"
        source.connection.use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                source.currentSchema = schema
                source.connection.use { connection -> connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE ai_connections(id TEXT PRIMARY KEY, payload_json TEXT NOT NULL)")
                    sql.execute("INSERT INTO ai_connections VALUES ('one', '{\"httpSettings\":{\"maxConcurrentCalls\":1,\"maxAttempts\":3}}'), ('many', '{\"httpSettings\":{\"maxConcurrentCalls\":64}}')")
                    sql.execute("CREATE TABLE runtime_catalog_configuration(id TEXT PRIMARY KEY, revision BIGINT NOT NULL)")
                    sql.execute("INSERT INTO runtime_catalog_configuration VALUES ('primary', 12)")
                    sql.execute("CREATE TABLE worker_requests(id TEXT PRIMARY KEY, worker_id TEXT NOT NULL, dispatched_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, cancel_requested_at TIMESTAMPTZ)")
                    sql.execute("INSERT INTO worker_requests(id, worker_id, dispatched_at) VALUES ('root', 'worker', now()), ('capture-root', 'worker', now())")
                    sql.execute(requireNotNull(PostgresAiAdmissionRepositoryTest::class.java.getResource("/db/migration/postgres/V83__ai_concurrency_groups.sql")).readText())
                    sql.executeQuery("SELECT payload_json FROM ai_connections").use { rows ->
                        var count = 0
                        while (rows.next()) {
                            val payload = Json.parseToJsonElement(rows.getString(1)).jsonObject
                            assertEquals("default", payload.getValue("concurrencyGroupId").jsonPrimitive.content)
                            assertFalse(payload.getValue("httpSettings").jsonObject.containsKey("maxConcurrentCalls"))
                            count++
                        }
                        assertEquals(2, count)
                    }
                    sql.executeQuery("SELECT revision FROM runtime_catalog_configuration").use { rows -> rows.next(); assertEquals(13, rows.getLong(1)) }
                    sql.executeQuery("SELECT max_concurrent_calls FROM ai_concurrency_groups WHERE id = 'default'").use { rows -> rows.next(); assertEquals(4, rows.getInt(1)) }
                    sql.execute("CREATE TABLE ai_model_configurations(id TEXT PRIMARY KEY, connection_id TEXT NOT NULL, payload_json TEXT NOT NULL)")
                    sql.execute("INSERT INTO ai_concurrency_groups VALUES ('shared', 'Shared', 2)")
                    sql.execute("UPDATE ai_connections SET payload_json = jsonb_set(payload_json::jsonb, '{concurrencyGroupId}', '\"shared\"'::jsonb)::text WHERE id = 'one'")
                    sql.execute("INSERT INTO ai_model_configurations VALUES ('model-one', 'one', '{}')")
                    sql.execute(requireNotNull(PostgresAiAdmissionRepositoryTest::class.java.getResource("/db/migration/postgres/V84__model_configuration_tpm.sql")).readText())
                    sql.executeQuery("SELECT payload_json FROM ai_model_configurations WHERE id = 'model-one'").use { rows ->
                        rows.next(); assertEquals("shared", Json.parseToJsonElement(rows.getString(1)).jsonObject.getValue("concurrencyGroupId").jsonPrimitive.content)
                    }
                    sql.executeQuery("SELECT payload_json FROM ai_connections WHERE id = 'one'").use { rows ->
                        rows.next(); assertFalse(Json.parseToJsonElement(rows.getString(1)).jsonObject.containsKey("concurrencyGroupId"))
                    }
                    sql.executeQuery("SELECT group_key FROM ai_concurrency_groups WHERE id = 'shared'").use { rows -> rows.next(); assertEquals("shared", rows.getString(1)) }

                } }
                val repository = PostgresAiAdmissionRepository(source, Json { encodeDefaults = true })
                fun lease(id: String, worker: String? = null, root: String? = null, background: String? = null) = AiAdmissionLease(
                    AiAdmissionOperation(id, AiAdmissionTarget.Connection(AiConnection.Id("one")), "TEST", backgroundSessionId = background),
                    AiConcurrencyGroup.DEFAULT_ID, Clock.System.now(), worker, root,
                )
                repository.create(lease("local"))
                repository.create(lease("remote", "worker", "root"))
                assertFailsWith<IllegalArgumentException> { repository.create(lease("wrong", "another-worker", "root")) }
                val restored = repository.restoreRemoteLeases()
                assertEquals(listOf("remote"), restored.map { it.operation.id })
                assertFailsWith<IllegalArgumentException> { repository.finish("remote", "another-worker") }
                assertEquals(1, repository.restoreRemoteLeases().size)

                val tpmGroup = AiTpmGroup(AiTpmGroup.Id("quota"), "account/model", "Quota", 1000)
                source.connection.use { connection -> connection.prepareStatement("INSERT INTO ai_tpm_groups(id,group_key,payload_json) VALUES (?,?,?)").use {
                    it.setString(1, tpmGroup.id.value); it.setString(2, tpmGroup.key)
                    it.setString(3, Json.encodeToString(AiTpmGroup.serializer(), tpmGroup)); it.executeUpdate()
                } }
                val quotaLease = lease("quota-call", "worker", "root").let { it.copy(operation = it.operation.copy(tpm =
                    AiTpmReservation(tpmGroup.id, 200, AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("remaining"))))) }
                val debit = AiTpmState(tpmGroup.id, 800.0, -30.0, 200)
                repository.create(quotaLease, listOf(debit))
                assertEquals(listOf(debit), repository.loadTpmStates())
                repository.finish("quota-call", "worker", listOf(debit), "invalid feedback")
                assertEquals("invalid feedback", repository.findCompletion("quota-call", "worker")?.feedbackError)
                repository.finish("quota-call", "worker", listOf(debit))
                assertEquals("invalid feedback", repository.findCompletion("quota-call", "worker")?.feedbackError)
                assertFailsWith<IllegalArgumentException> { repository.findCompletion("quota-call", "other") }

                repository.create(lease("capture", "worker", "capture-root", "capture-session"))
                repository.create(lease("lost-grant", "worker", "capture-root", "lost-session"))
                repository.accept("capture", "worker")
                source.connection.use { connection -> connection.createStatement().use {
                    it.execute("UPDATE worker_requests SET completed_at = now()")
                } }
                assertFailsWith<IllegalArgumentException> { repository.accept("lost-grant", "worker") }
                repository.finishWorkerRequest("worker", "root")
                repository.finishWorkerRequest("worker", "capture-root")
                val background = repository.restoreRemoteLeases().single()
                assertEquals("capture", background.operation.id)
                assertTrue(background.accepted)
                assertFailsWith<java.sql.SQLException> {
                    source.connection.use { connection -> connection.createStatement().use { it.execute("DELETE FROM ai_concurrency_groups WHERE id = 'default'") } }
                }
                repository.finish("capture", "worker")
                repository.finish("capture", "worker")
                assertTrue(repository.restoreRemoteLeases().isEmpty())
                assertFailsWith<IllegalArgumentException> { repository.create(lease("late", "worker", "root")) }
                println("AI_ADMISSION_PG: default migration, ownership, restoration, background accept, group dependency and idempotent completion verified")
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
