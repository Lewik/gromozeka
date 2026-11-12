package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.service.CommandScriptTokens
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Real PostgreSQL and the production V86 migration in an isolated minimal parent schema. */
@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class PostgresCommandScriptGrantRepositoryTest {
    @Test fun `grant survives creating RPC retention but cannot be replaced or reissued after credential GC`() = runBlocking<Unit> {
        database { f ->
            val origin = f.origin("one")
            f.seed(origin)
            val raw = CommandScriptTokens.create()
            val hash = CommandScriptTokens.hash(raw)
            val first = f.repository.register(origin, f.project, hash, f.now)
            assertEquals(first, f.repository.register(origin, f.project, hash, f.now + 1.minutes))
            assertEquals(1, f.sql.queryForObject("SELECT count(*) FROM command_script_grants", Int::class.java))
            assertFailsWith<IllegalArgumentException> {
                f.repository.register(origin, f.project, CommandScriptTokens.hash(CommandScriptTokens.create()), f.now)
            }
            assertFalse(f.sql.queryForObject("SELECT row_to_json(g)::text FROM command_script_grants g", String::class.java)!!.contains(raw))
            f.sql.update("DELETE FROM worker_requests WHERE id=?", origin.workerRequestId)
            assertEquals(first, PostgresCommandScriptGrantRepository(f.source).findByHash(hash))
            assertEquals(0, f.repository.deleteRevokedBefore(f.now + 30.days, 256), "Age does not expire an active command")
            f.repository.revoke(origin.conversationId, CommandTask.Id(origin.activityId), f.now + 7.days)
            assertNotNull(f.repository.findByHash(hash)?.revokedAt)
            assertEquals(1, f.repository.deleteRevokedBefore(f.now + 8.days, 256))
            assertNull(f.repository.findByHash(hash))
            // Recreate a pending RPC deliberately: the permanent non-secret issuance marker
            // must still prevent credential revival after the token row itself was cleaned.
            f.seedRequest(origin, f.now + 8.days)
            val failure = assertFailsWith<IllegalArgumentException> {
                f.repository.register(origin, f.project, hash, f.now + 8.days)
            }
            assertContains(failure.message.orEmpty(), "retired")
            assertEquals(true, f.sql.queryForObject("SELECT script_grant_issued FROM background_activity_origins WHERE activity_id=?", Boolean::class.java, origin.activityId))
        }
    }

    @Test fun `registration requires a dispatched pending unexpired RPC and identical trusted provenance`() = runBlocking<Unit> {
        database { f ->
            val mutations = listOf(
                "completed_at=clock_timestamp()",
                "cancel_requested_at=clock_timestamp()",
                "dispatched_at=NULL",
                "execution_deadline=clock_timestamp()-interval '1 second'",
                "worker_id='other-worker'",
                "actor_user_id='other-user'",
                "project_id='other-project'",
            )
            for ((index, mutation) in mutations.withIndex()) {
                val origin = f.origin("bad-$index")
                f.seed(origin)
                f.sql.update("UPDATE worker_requests SET $mutation WHERE id=?", origin.workerRequestId)
                assertFailsWith<IllegalArgumentException> {
                    f.repository.register(origin, f.project, CommandScriptTokens.hash(CommandScriptTokens.create()), f.now)
                }
            }
            val origin = f.origin("wrong-provenance")
            f.seed(origin)
            assertFailsWith<IllegalArgumentException> {
                f.repository.register(origin.copy(agentDefinitionId = AgentDefinition.Id("other-agent")), f.project,
                    CommandScriptTokens.hash(CommandScriptTokens.create()), f.now)
            }
            assertFailsWith<IllegalArgumentException> { f.repository.register(origin, f.project, "not-a-hash", f.now) }
            assertEquals(0, f.sql.queryForObject("SELECT count(*) FROM command_script_grants", Int::class.java))
            assertEquals(0, f.sql.queryForObject("SELECT count(*) FROM background_activity_origins WHERE script_grant_issued", Int::class.java))
        }
    }

    @Test fun `concurrent registration is one issuance and maintenance never cleans active rows`() = runBlocking<Unit> {
        database { f ->
            val origin = f.origin("concurrent")
            f.seed(origin)
            val hashes = listOf(CommandScriptTokens.hash(CommandScriptTokens.create()), CommandScriptTokens.hash(CommandScriptTokens.create()))
            val results = coroutineScope { hashes.map { hash -> async(Dispatchers.IO) {
                runCatching { f.repository.register(origin, f.project, hash, f.now) }
            } }.awaitAll() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is IllegalArgumentException })
            val stored = results.single { it.isSuccess }.getOrThrow()
            assertEquals(stored, f.repository.findByHash(stored.tokenHash))
            assertEquals(1, f.repository.maintenanceCandidates(f.now + 1.minutes, 128).size)
            f.repository.markChecked(CommandTask.Id(origin.activityId), f.now + 1.days, 1)
            f.repository.markChecked(CommandTask.Id(origin.activityId), f.now + 2.days, 2)
            f.repository.markChecked(CommandTask.Id(origin.activityId), f.now + 1.days, 1)
            val checked = assertNotNull(f.repository.findByHash(stored.tokenHash))
            assertEquals(2L, checked.lastReminderDay)
            assertEquals(f.now + 2.days, checked.checkedAt)
            assertEquals(0, f.repository.deleteRevokedBefore(f.now + 100.days, 256))
            f.repository.revoke(Conversation.Id("other-conversation"), CommandTask.Id(origin.activityId), f.now)
            assertNull(f.repository.findByHash(stored.tokenHash)?.revokedAt)
        }
    }

    private class Fixture(val source: DataSource) {
        val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val project = Project.Id("project")
        val sql = JdbcTemplate(source)
        val repository = PostgresCommandScriptGrantRepository(source)
        private val json = Json { encodeDefaults = true }
        fun origin(id: String) = BackgroundActivityOrigin(BackgroundActivityOrigin.Kind.COMMAND, id,
            Conversation.Id("conversation"), AgentDefinition.Id("agent"), User.Id("user"),
            ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), "request-$id", "call-$id")
        fun seed(origin: BackgroundActivityOrigin) {
            seedRequest(origin, now)
            sql.update("INSERT INTO background_activity_origins(kind,activity_id,record_json) VALUES ('COMMAND',?,?::jsonb)",
                origin.activityId, json.encodeToString(origin))
        }
        fun seedRequest(origin: BackgroundActivityOrigin, at: Instant) {
            sql.update("INSERT INTO worker_requests(id,worker_id,actor_user_id,project_id,dispatched_at,execution_deadline) VALUES (?,?,?,?,?,?)",
                origin.workerRequestId, origin.workerId.value, origin.actorUserId.value, project.value, at.sql(), (at + 1.hours).sql())
        }
        private fun Instant.sql() = Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))
    }

    private suspend fun database(block: suspend (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        val schema = "command_grants_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = DriverManagerDataSource(url, user, password)
        JdbcTemplate(admin).execute("CREATE SCHEMA $schema")
        try {
            val source = DriverManagerDataSource(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema,public", user, password)
            val sql = JdbcTemplate(source)
            sql.execute("CREATE TABLE users(id VARCHAR(255) PRIMARY KEY)")
            sql.execute("CREATE TABLE conversations(id VARCHAR(255) PRIMARY KEY)")
            sql.execute("""CREATE TABLE worker_requests(id VARCHAR(128) PRIMARY KEY,worker_id TEXT,actor_user_id TEXT,project_id TEXT,
                dispatched_at TIMESTAMPTZ,execution_deadline TIMESTAMPTZ,cancel_requested_at TIMESTAMPTZ,completed_at TIMESTAMPTZ)""")
            sql.execute("CREATE TABLE background_activity_origins(kind TEXT,activity_id VARCHAR(128),record_json JSONB,PRIMARY KEY(kind,activity_id))")
            val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/postgres/V86__command_script_grants.sql"))
                .bufferedReader().use { it.readText() }
            sql.execute(migration)
            sql.update("INSERT INTO users VALUES ('user')")
            sql.update("INSERT INTO conversations VALUES ('conversation')")
            block(Fixture(source))
        } finally {
            JdbcTemplate(admin).execute("DROP SCHEMA $schema CASCADE")
        }
    }
}
