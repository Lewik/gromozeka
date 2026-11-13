package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.repository.*
import com.gromozeka.infrastructure.db.runtime.PostgresConversationRuntimeCoordinator
import org.mockito.Mockito
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Real PostgreSQL and the production V87 migration in an isolated minimal parent schema. */
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

    @Test fun `terminal commit survives a lost observer retention restart and stale WORKING replay`() = runBlocking<Unit> {
        for (evictDuringCommit in listOf(false, true)) database { f ->
            val origin = f.origin("retained")
            f.seed(origin)
            val task = f.task(origin)
            val token = CommandScriptTokens.create()
            val coordinator = f.coordinator()
            coordinator.upsertCommandTask(task)
            f.repository.register(origin, f.project, CommandScriptTokens.hash(token), f.now)
            suspend fun fillTerminalRetention() {
                repeat(100) { i -> coordinator.upsertCommandTask(task.copy(
                    id = CommandTask.Id("filler-$i"), launchReference = null,
                    status = CommandTask.Status.COMPLETED, completedAt = f.now,
                    createdAt = f.now + (i + 1).seconds,
                )) }
            }
            if (evictDuringCommit) fillTerminalRetention()
            assertFailsWith<IllegalStateException> {
                coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED, completedAt = f.now))
                error("Synthetic crash after canonical commit; no post-commit observer runs")
            }
            assertNotNull(f.repository.findByHash(CommandScriptTokens.hash(token))?.revokedAt)
            assertNotNull(f.closedAt(origin))
            if (!evictDuringCommit) fillTerminalRetention()
            val restarted = f.coordinator()
            assertNull(restarted.findCommandTask(task.conversationId, task.id))
            val service = f.closedGrantService(restarted)
            service.maintain(f.now + 30.minutes)
            assertNotNull(f.repository.findByHash(CommandScriptTokens.hash(token))?.revokedAt)
            assertEquals(CommandTask.Status.WORKING, restarted.upsertCommandTask(task).task.status)
            assertNull(service.authenticate(token, f.now + 30.minutes))
            service.maintain(f.now + 2.hours)
            assertNull(f.repository.findByHash(CommandScriptTokens.hash(token)))
            assertNull(service.authenticate(token, f.now + 2.hours))
            assertFailsWith<IllegalArgumentException> {
                f.repository.register(origin, f.project, CommandScriptTokens.hash(token), f.now)
            }
        }
    }

    @Test fun `grant or inventory write failure rolls back closure and canonical terminal state together`() = runBlocking<Unit> {
        database { f ->
            val origin = f.origin("rollback")
            f.seed(origin)
            val task = f.task(origin)
            val coordinator = f.coordinator()
            coordinator.upsertCommandTask(task)
            val hash = CommandScriptTokens.hash(CommandScriptTokens.create())
            f.repository.register(origin, f.project, hash, f.now)
            f.sql.execute("""CREATE FUNCTION reject_closure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic closure write failure'; END $$""")
            for (table in listOf("command_script_grants", "conversation_runtime_records")) {
                f.sql.execute("CREATE TRIGGER reject_closure BEFORE UPDATE ON $table FOR EACH ROW EXECUTE FUNCTION reject_closure()")
                try {
                    assertFailsWith<java.sql.SQLException> {
                        coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED, completedAt = f.now))
                    }
                    assertEquals(CommandTask.Status.WORKING, f.coordinator().findCommandTask(task.conversationId, task.id)?.status)
                    assertNull(f.repository.findByHash(hash)?.revokedAt)
                    assertNull(f.closedAt(origin))
                } finally { f.sql.execute("DROP TRIGGER reject_closure ON $table") }
            }
            coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED, completedAt = f.now))
            assertNotNull(f.repository.findByHash(hash)?.revokedAt)
            assertNotNull(f.closedAt(origin))
        }
    }

    @Test fun `terminal cancellation and deadline close issuance even before a grant exists`() = runBlocking<Unit> {
        database { f ->
            for (kind in listOf("terminal", "cancel", "cancel-all", "deadline")) {
                for (issued in if (kind == "deadline") listOf(false) else listOf(false, true)) {
                    val origin = f.origin("close-$kind-$issued")
                    f.seed(origin)
                    val task = f.task(origin).let { if (kind == "deadline") it.copy(timeoutAt = f.now - 1.seconds) else it }
                    val coordinator = f.coordinator()
                    coordinator.upsertCommandTask(task)
                    val hash = CommandScriptTokens.hash(CommandScriptTokens.create())
                    if (issued) f.repository.register(origin, f.project, hash, f.now)
                    when (kind) {
                        "terminal" -> coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.FAILED, completedAt = f.now))
                        "cancel" -> assertTrue(coordinator.requestCommandTaskCancellation(task.conversationId, task.id, f.now))
                        "cancel-all" -> assertTrue(coordinator.requestCommandTaskCancellations(task.conversationId, f.now) > 0)
                    }
                    assertNotNull(f.closedAt(origin))
                    if (issued) assertNotNull(f.repository.findByHash(hash)?.revokedAt)
                    assertFailsWith<IllegalArgumentException> { f.repository.register(origin, f.project, hash, f.now) }
                }
            }
        }
    }

    @Test fun `registration racing closure cannot leave an active credential`() = runBlocking<Unit> {
        database { f ->
            repeat(4) { i ->
                val origin = f.origin("race-$i")
                f.seed(origin)
                val task = f.task(origin)
                val coordinator = f.coordinator()
                coordinator.upsertCommandTask(task)
                val hash = CommandScriptTokens.hash(CommandScriptTokens.create())
                val registration = coroutineScope {
                    val issue = async(Dispatchers.IO) { runCatching { f.repository.register(origin, f.project, hash, f.now) } }
                    val close = async(Dispatchers.IO) { coordinator.upsertCommandTask(task.copy(status = CommandTask.Status.COMPLETED, completedAt = f.now)) }
                    close.await()
                    issue.await()
                }
                assertTrue(registration.isSuccess || registration.exceptionOrNull() is IllegalArgumentException)
                assertNotNull(f.closedAt(origin))
                f.repository.findByHash(hash)?.let { assertNotNull(it.revokedAt) }
            }
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
            sql.update("INSERT INTO background_activity_origins(kind,activity_id,conversation_id,record_json) VALUES ('COMMAND',?,?,?::jsonb)",
                origin.activityId, origin.conversationId.value, json.encodeToString(origin))
        }
        fun seedRequest(origin: BackgroundActivityOrigin, at: Instant) {
            sql.update("INSERT INTO worker_requests(id,worker_id,actor_user_id,project_id,dispatched_at,execution_deadline) VALUES (?,?,?,?,?,?)",
                origin.workerRequestId, origin.workerId.value, origin.actorUserId.value, project.value, at.sql(), (at + 1.hours).sql())
        }
        fun task(origin: BackgroundActivityOrigin) = CommandTask(CommandTask.Id(origin.activityId), origin.conversationId,
            origin.workerId, origin.workspaceMountId, agentDefinitionId = origin.agentDefinitionId,
            command = "synthetic", workingDirectory = "/not-opened", status = CommandTask.Status.WORKING,
            processId = 123, processStartedAt = now, processTreeId = 123, outputFile = "/not-opened/output",
            outputBytes = 0, createdAt = now, updatedAt = now,
            launchReference = BackgroundActivityLaunchReference(origin.workerRequestId, origin.toolCallId))
        fun coordinator() = PostgresConversationRuntimeCoordinator(source, json)
        suspend fun closedGrantService(coordinator: ConversationRuntimeCoordinator): CommandScriptGrantService {
            val identities = Mockito.mock(IdentityRepository::class.java)
            Mockito.`when`(identities.findUserById(User.Id("user")))
                .thenThrow(IllegalStateException("A closed grant must be rejected before user authorization"))
            return CommandScriptGrantService(repository, Mockito.mock(BackgroundActivityOriginRepository::class.java),
                coordinator, Mockito.mock(ConversationRepository::class.java), identities,
                Mockito.mock(ProjectAccessService::class.java), Mockito.mock(WorkspaceDomainService::class.java),
                Mockito.mock(WorkerAccessService::class.java), Mockito.mock(AgentDomainService::class.java),
                UserNotificationPublisher { _, _ -> error("A closed grant must not produce an active reminder") })
        }
        fun closedAt(origin: BackgroundActivityOrigin): Timestamp? = sql.queryForObject(
            "SELECT command_closed_at FROM background_activity_origins WHERE activity_id=?", Timestamp::class.java, origin.activityId)
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
            sql.execute("CREATE TABLE background_activity_origins(kind TEXT,activity_id VARCHAR(128),conversation_id TEXT NOT NULL,record_json JSONB,PRIMARY KEY(kind,activity_id))")
            for (name in listOf("V4__conversation_runtime_records.sql", "V31__conversation_runtime_ready_work.sql",
                "V62__worker_command_inventory_indexes.sql", "V64__split_conversation_runtime_storage.sql")) {
                sql.execute(checkNotNull(javaClass.getResourceAsStream("/db/migration/postgres/$name"))
                    .bufferedReader().use { it.readText() })
            }
            val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/postgres/V87__command_script_grants.sql"))
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
