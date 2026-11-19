package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresAsyncToolOperationRepositoryTest {
    @Test fun `concurrent lost acknowledgement admission reuses one encrypted durable operation`() = runBlocking<Unit> {
        database { f ->
            val candidates = (1..16).map { index -> async(Dispatchers.IO) {
                f.repository().admit(AsyncToolOperation.Id("candidate-$index"), f.invocation, f.at)
            } }.awaitAll()
            assertEquals(1, candidates.map { it.id }.distinct().size)
            val accepted = candidates.first()
            assertEquals(f.invocation, f.repository().find(accepted.id)?.invocation)
            assertNull(f.repository().claim(accepted.id, f.owner, f.at, f.after(30)))
            assertTrue(f.repository().acknowledge(accepted.id, f.at))
            assertFalse(f.repository().acknowledge(accepted.id, f.after(1)))
            val claims = (1..8).map { index -> async(Dispatchers.IO) {
                f.repository().claim(accepted.id, ConversationRuntimeServerSessionId("owner-$index"), f.at, f.after(30))
            } }.awaitAll()
            assertEquals(1, claims.count { it != null })
            val claimed = assertNotNull(f.repository().find(accepted.id))
            val winner = assertNotNull(claimed.executorSessionId)
            assertFalse(f.repository().markStarted(claimed.copy(executorSessionId = ConversationRuntimeServerSessionId("wrong-owner")), f.at))
            assertTrue(f.repository().markStarted(claimed, f.at))
            val running = assertNotNull(f.repository().find(accepted.id))
            val output = AsyncToolOutcome(listOf(ContentItem.ToolResult.Data.Text("private-result-secret")), isError = false)
            assertTrue(f.repository().finish(running, Phase.SUCCEEDED, output, f.after(1)))
            assertEquals(output, f.repository().find(accepted.id)?.outcome)
            assertEquals(listOf(accepted.id), f.repository().unprojected(16).map { it.id })
            assertTrue(f.repository().markProjected(accepted.id, f.after(2)))
            assertTrue(f.repository().unprojected(16).isEmpty())
            f.dataSource.connection.use { c -> c.createStatement().use { s ->
                s.executeQuery("SELECT invocation_ciphertext,outcome_ciphertext FROM async_tool_operations").use { rows ->
                    assertTrue(rows.next())
                    assertFalse(rows.getString(1).contains("private-input-secret"))
                    assertFalse(rows.getString(2).contains("private-result-secret"))
                }
            } }
        }
    }

    @Test fun `expired claim can be fenced but a lost running callback is never reclaimed`() = runBlocking<Unit> {
        database { f ->
            val id = f.repository().admit(AsyncToolOperation.Id("operation"), f.invocation, f.at).id
            assertTrue(f.repository().acknowledge(id, f.at))
            val initial = assertNotNull(f.repository().claim(id, f.owner, f.at, f.after(1)))
            val replacement = ConversationRuntimeServerSessionId("replacement-server")
            val nextClaim = assertNotNull(f.repository().claim(id, replacement, f.after(2), f.after(30)))
            assertFalse(f.repository().markStarted(initial, f.after(3)))
            assertTrue(f.repository().markStarted(nextClaim, f.after(3)))
            assertNull(f.repository().claim(id, f.owner, f.after(31), f.after(60)))
            val lost = f.repository().expiredLeases(f.after(31), 16).single()
            val unknown = AsyncToolOutcome(isError = true, failureCode = "OUTCOME_UNKNOWN")
            assertTrue(f.repository().finish(lost, Phase.OUTCOME_UNKNOWN, unknown, f.after(31)))
            assertFalse(f.repository().finish(lost, Phase.SUCCEEDED, AsyncToolOutcome(isError = false), f.after(32)))
            assertEquals(unknown, f.repository().find(id)?.outcome)
            assertNull(f.repository().claim(id, f.owner, f.after(33), f.after(60)))
        }
    }

    @Test fun `queued cancellation is not reported as rollback after execution starts`() = runBlocking<Unit> {
        database { f ->
            val id = f.repository().admit(AsyncToolOperation.Id("queued"), f.invocation, f.at).id
            assertTrue(f.repository().acknowledge(id, f.at))
            assertTrue(f.repository().requestCancellation(id, f.at))
            assertNull(f.repository().claim(id, f.owner, f.at, f.after(30)))
            val queued = assertNotNull(f.repository().find(id))
            assertTrue(f.repository().finish(queued, Phase.CANCELLED,
                AsyncToolOutcome(isError = true, failureCode = "CANCELLED_BEFORE_START"), f.after(1)))
            val nextOrigin = f.invocation.origin.copy(sourceTaskId = ConversationRuntimeTask.Id("next-task"),
                acknowledgementMessageId = Conversation.Message.Id("next-task:result"))
            val next = f.invocation.copy(origin = nextOrigin)
            val runningId = f.repository().admit(AsyncToolOperation.Id("running"), next, f.at).id
            assertTrue(f.repository().acknowledge(runningId, f.at))
            val claimedRunning = assertNotNull(f.repository().claim(runningId, f.owner, f.at, f.after(30)))
            assertTrue(f.repository().markStarted(claimedRunning, f.at))
            assertTrue(f.repository().requestCancellation(runningId, f.after(1)))
            val running = assertNotNull(f.repository().find(runningId))
            assertFailsWith<IllegalArgumentException> { f.repository().finish(running, Phase.CANCELLED,
                AsyncToolOutcome(isError = true, failureCode = "CANCELLED_BEFORE_START"), f.after(2)) }
            assertTrue(f.repository().finish(running, Phase.OUTCOME_UNKNOWN,
                AsyncToolOutcome(isError = true, failureCode = "OUTCOME_UNKNOWN"), f.after(2)))
            assertEquals(f.after(1), f.repository().find(runningId)?.cancelRequestedAt)
        }
    }

    @Test fun `same source cannot be rebound to a branch actor or new arguments`() = runBlocking<Unit> {
        database { f ->
            f.repository().admit(AsyncToolOperation.Id("original"), f.invocation, f.at)
            val otherThread = f.invocation.copy(origin = f.invocation.origin.copy(threadId = Conversation.Thread.Id("another-thread")),
                context = f.invocation.context + (TOOL_CONTEXT_THREAD_ID to "another-thread"))
            val otherActor = f.invocation.copy(origin = f.invocation.origin.copy(actorUserId = User.Id("another-actor")),
                context = f.invocation.context + (TOOL_CONTEXT_USER_ID to "another-actor"))
            for (changed in listOf(otherThread, otherActor, f.invocation.copy(modelToolName = "another-tool"),
                f.invocation.copy(toolCall = f.invocation.toolCall.copy(call = f.invocation.toolCall.call.copy(input = buildJsonObject { put("changed", true) }))))) {
                assertFailsWith<IllegalArgumentException> { f.repository().admit(AsyncToolOperation.Id("replacement"), changed, f.at) }
            }
            f.dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE async_tool_operations SET actor_user_id='tampered-index'") } }
            assertFailsWith<IllegalStateException> { f.repository().find(AsyncToolOperation.Id("original")) }
        }
    }

    @Test fun `Worker child keeps its parent ACK owner lease and stable identity through lost submit ACK`() = runBlocking<Unit> {
        database { f ->
            val now = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
            val slot = com.gromozeka.domain.slot.SlotCommandOrigin("slot", 2, "original-lease")
            val parent = f.repository().admit(AsyncToolOperation.Id("worker-operation"), f.workerInvocation(slot), now)
            val child = StoredWorkerRequest(parent.id.value, ConversationRuntimeWorkerId("worker"), "original-private-wire".encodeToByteArray(),
                now, now + kotlin.time.Duration.parse("30s"), actorUserId = parent.invocation.origin.actorUserId,
                projectId = parent.invocation.origin.projectId, slotOrigin = slot, executionDeadline = now + kotlin.time.Duration.parse("60s"),
                asyncTool = AsyncToolDispatchReference(parent.id, f.owner, 1))
            assertEquals(parent.id.value, f.workerRepository().pendingForSlot(2).single().requestId)
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child) }
            assertNull(f.workerRepository().find(child.id))
            assertTrue(f.repository().acknowledge(parent.id, now))
            assertNotNull(f.repository().claim(parent.id, f.owner, now, now + kotlin.time.Duration.parse("30s")))
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child.copy(
                asyncTool = AsyncToolDispatchReference(parent.id, ConversationRuntimeServerSessionId("wrong-owner"), 1))) }
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child.copy(
                asyncTool = checkNotNull(child.asyncTool).copy(claimGeneration = 2))) }
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child.copy(workerId = ConversationRuntimeWorkerId("other"))) }
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child.copy(slotOrigin = slot.copy(leaseId = "new-lease"))) }
            f.workerRepository().create(child)
            assertEquals(now, f.repository().find(parent.id)?.workerRequestCreatedAt)
            assertEquals(slot, f.workerRepository().pendingForSlot(2).single().origin)
            f.workerRepository().create(child.copy(request = "replacement-wire-must-not-run".encodeToByteArray()))
            assertContentEquals(child.request, f.workerRepository().find(child.id)?.request)
            f.dataSource.connection.use { c -> c.prepareStatement("DELETE FROM worker_requests WHERE id=?").use { it.setString(1, child.id); assertEquals(1, it.executeUpdate()) } }
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child) }
            assertNull(f.workerRepository().find(child.id))
            assertNotNull(f.repository().find(parent.id)?.workerRequestCreatedAt)
        }
    }

    @Test fun `Worker child creation and parent cancellation serialize without allowing a fresh dispatch after cancel`() = runBlocking<Unit> {
        database { f ->
            val now = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
            val parent = f.repository().admit(AsyncToolOperation.Id("worker-cancel"), f.workerInvocation(null), now)
            assertTrue(f.repository().acknowledge(parent.id, now))
            assertNotNull(f.repository().claim(parent.id, f.owner, now, now + kotlin.time.Duration.parse("30s")))
            val child = StoredWorkerRequest(parent.id.value, ConversationRuntimeWorkerId("worker"), byteArrayOf(1), now,
                now + kotlin.time.Duration.parse("30s"), actorUserId = parent.invocation.origin.actorUserId,
                projectId = parent.invocation.origin.projectId, executionDeadline = now + kotlin.time.Duration.parse("60s"),
                asyncTool = AsyncToolDispatchReference(parent.id, f.owner, 1))
            assertTrue(f.repository().requestCancellation(parent.id, now))
            assertFailsWith<IllegalArgumentException> { f.workerRepository().create(child) }
            assertNull(f.workerRepository().find(child.id))
            assertNull(f.repository().find(parent.id)?.workerRequestCreatedAt)
        }
    }

    @Test fun `created Worker child fences a reclaim decided from an older claimed snapshot`() = runBlocking<Unit> {
        database { f ->
            val now = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds())
            val parent = f.repository().admit(AsyncToolOperation.Id("child-before-reclaim"), f.workerInvocation(null), now)
            assertTrue(f.repository().acknowledge(parent.id, now))
            assertNotNull(f.repository().claim(parent.id, f.owner, now, now + kotlin.time.Duration.parse("30s")))
            val stale = assertNotNull(f.repository().find(parent.id))
            assertNull(stale.workerRequestCreatedAt)
            val child = StoredWorkerRequest(parent.id.value, ConversationRuntimeWorkerId("worker"), byteArrayOf(1), now,
                now + kotlin.time.Duration.parse("30s"), actorUserId = parent.invocation.origin.actorUserId,
                projectId = parent.invocation.origin.projectId, executionDeadline = now + kotlin.time.Duration.parse("60s"),
                asyncTool = AsyncToolDispatchReference(parent.id, f.owner, 1))
            f.workerRepository().create(child)
            assertFalse(f.repository().finish(stale, Phase.FAILED, AsyncToolOutcome(isError = true, failureCode = "STALE_PRE_CHILD"), now))
            val reclaimAt = checkNotNull(stale.leaseDeadline) + kotlin.time.Duration.parse("1s")
            assertNull(f.repository().claim(stale.id, ConversationRuntimeServerSessionId("replacement"), reclaimAt,
                reclaimAt + kotlin.time.Duration.parse("30s")), "A Worker child created after the snapshot must fence reclaim")
            assertEquals(f.owner, f.repository().find(stale.id)?.executorSessionId)
            assertNotNull(f.repository().find(stale.id)?.workerRequestCreatedAt)
            assertContentEquals(child.request, f.workerRepository().find(child.id)?.request)
        }
    }

    @Test fun `same-session replacement claim fences stale finish`() = runBlocking<Unit> {
        database { f ->
            val parent = f.repository().admit(AsyncToolOperation.Id("same-owner-reclaim"), f.invocation, f.at)
            assertTrue(f.repository().acknowledge(parent.id, f.at))
            assertNotNull(f.repository().claim(parent.id, f.owner, f.at, f.after(1)))
            val stale = assertNotNull(f.repository().find(parent.id))
            assertNotNull(f.repository().claim(parent.id, f.owner, f.after(2), f.after(30)))
            assertFalse(f.repository().finish(stale, Phase.FAILED, AsyncToolOutcome(isError = true, failureCode = "LATE_PREPARATION"), f.after(3)))
            assertEquals(Phase.CLAIMED, f.repository().find(parent.id)?.phase)
            assertNull(f.repository().find(parent.id)?.outcome)
            assertFalse(f.repository().markStarted(stale, f.after(3)))
            assertFalse(f.repository().renewLease(stale, f.after(60)))
            assertEquals(2L, f.repository().find(parent.id)?.claimGeneration)
        }
    }

    @Test fun `fresh V90 schema starts unclaimed and rejects invalid claim generations`() = runBlocking<Unit> {
        database { f ->
            val admitted = f.repository().admit(AsyncToolOperation.Id("fresh-generation"), f.invocation, f.at)
            assertEquals(0L, admitted.claimGeneration)
            assertNull(admitted.claimReference)
            for (generation in listOf(-1, 1)) {
                assertFailsWith<java.sql.SQLException> {
                    f.dataSource.connection.use { connection ->
                        connection.prepareStatement("UPDATE async_tool_operations SET claim_generation=? WHERE id=?").use { statement ->
                            statement.setLong(1, generation.toLong()); statement.setString(2, admitted.id.value)
                            statement.executeUpdate()
                        }
                    }
                }
                assertEquals(0L, f.repository().find(admitted.id)?.claimGeneration)
            }
        }
    }

    @Test fun `fresh V90 creates both claim fencing constraints without a data upgrade`() = runBlocking<Unit> {
        database { f ->
            f.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("""SELECT conname FROM pg_constraint c
                        JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                        WHERE n.nspname=current_schema() AND t.relname='async_tool_operations'
                        AND conname IN ('async_tool_claim_generation_nonnegative','async_tool_claim_generation_owner')""").use { rows ->
                        val names = mutableSetOf<String>()
                        while (rows.next()) names += rows.getString(1)
                        assertEquals(setOf("async_tool_claim_generation_nonnegative", "async_tool_claim_generation_owner"), names)
                    }
                }
            }
        }
    }

    private class Fixture(val dataSource: PGSimpleDataSource, home: String) {
        val at = Instant.fromEpochSeconds(10)
        val owner = ConversationRuntimeServerSessionId("initial-server")
        private val settings = Proxy.newProxyInstance(SettingsProvider::class.java.classLoader, arrayOf(SettingsProvider::class.java)) { _, method, _ ->
            check(method.name == "getHomeDirectory"); home
        } as SettingsProvider
        fun repository() = PostgresAsyncToolOperationRepository(dataSource, SecretCipher(settings))
        fun workerRepository() = PostgresWorkerRequestRepository(dataSource, SecretCipher(settings))
        fun workerInvocation(slot: com.gromozeka.domain.slot.SlotCommandOrigin?): AsyncToolInvocation = invocation.copy(
            origin = origin.copy(slotOrigin = slot),
            target = ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount")),
            context = invocation.context + mapOf(TOOL_CONTEXT_WORKER_ID to "worker", TOOL_CONTEXT_WORKSPACE_MOUNT_ID to "mount",
                TOOL_CONTEXT_WORKSPACE_ID to "workspace", TOOL_CONTEXT_WORKSPACE_ROOT_PATH to "/original-checkout"))
        fun after(seconds: Long) = Instant.fromEpochSeconds(10 + seconds)
        private val origin = AsyncToolOrigin(User.Id("actor"), Project.Id("project"), Conversation.Id("conversation"),
            Conversation.Thread.Id("source-thread"), AgentDefinition.Id("agent"), ConversationRuntimeTask.Id("source-task"),
            ContentItem.ToolCall.Id("call"), Conversation.Message.Id("source-task:result"), readOnly = true, slotOrigin = null)
        val invocation = AsyncToolInvocation(origin, "tool_v1", "a".repeat(64),
            ContentItem.ToolCall(origin.sourceToolCallId, ContentItem.ToolCall.Data("tool", buildJsonObject { put("input", "private-input-secret") })),
            ConversationRuntimeTaskTarget.Server, mapOf(
                TOOL_CONTEXT_USER_ID to origin.actorUserId.value, TOOL_CONTEXT_PROJECT_ID to origin.projectId.value,
                TOOL_CONTEXT_CONVERSATION_ID to origin.conversationId.value, TOOL_CONTEXT_THREAD_ID to origin.threadId.value,
                TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value, "toolCallId" to origin.sourceToolCallId.value))
    }

    private suspend fun database(block: suspend (Fixture) -> Unit) {
        org.junit.Assume.assumeTrue("Requires isolated PostgreSQL test configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        fun source() = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "async_tool_test_${UUID.randomUUID().toString().replace("-", "")}"
        val home = Files.createTempDirectory("async-tool-cipher-test-")
        val admin = source()
        admin.connection.use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
        try {
            val scoped = source().apply { currentSchema = schema }
            scoped.connection.use { it.createStatement().use { s ->
                s.execute("CREATE TABLE workers (id VARCHAR(255) PRIMARY KEY, status VARCHAR(32) NOT NULL)")
                s.execute("CREATE TABLE users (id VARCHAR(255) PRIMARY KEY)")
                s.execute("CREATE TABLE projects (id VARCHAR(255) PRIMARY KEY)")
                s.execute("INSERT INTO workers VALUES ('worker','ACTIVE'),('other','ACTIVE')")
                s.execute("INSERT INTO users VALUES ('actor')")
                s.execute("INSERT INTO projects VALUES ('project')")
                for (migration in listOf("V50__durable_worker_requests.sql", "V68__development_slots.sql", "V77__worker_request_retention.sql", "V90__async_tool_operations.sql")) {
                    s.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/$migration")).readText())
                }
            } }
            block(Fixture(scoped, home.toString()))
        } finally {
            admin.connection.use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") } }
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
