package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AsyncToolInvocation
import com.gromozeka.domain.model.AsyncToolOperation
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.AsyncToolOutcome
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Instant

@Service
internal class PostgresAsyncToolOperationRepository(
    private val dataSource: DataSource,
    private val cipher: SecretCipher,
) : AsyncToolOperationRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun admit(id: AsyncToolOperation.Id, invocation: AsyncToolInvocation, at: Instant): AsyncToolOperation = withContext(Dispatchers.IO) {
        val origin = invocation.origin
        val plain = json.encodeToString(AsyncToolInvocation.serializer(), invocation)
        require(plain.encodeToByteArray().size <= 64 * 1024 * 1024) { "Async tool input exceeds 64 MiB" }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                // Serialize the per-actor bound and source dedupe even when no row exists yet.
                listOf("async-tools:actor:${origin.actorUserId.value}",
                    "async-tools:source:${origin.conversationId.value}:${origin.sourceTaskId.value}:${origin.sourceToolCallId.value}").forEach { key ->
                    c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { it.setString(1, key); it.executeQuery().close() }
                }
                val existing = c.prepareStatement("SELECT * FROM async_tool_operations WHERE conversation_id=? AND source_task_id=? AND source_call_id=?").use {
                    it.setString(1, origin.conversationId.value)
                    it.setString(2, origin.sourceTaskId.value); it.setString(3, origin.sourceToolCallId.value)
                    it.executeQuery().use { rows -> if (rows.next()) rows.record() else null }
                }
                if (existing != null) {
                    require(existing.invocation == invocation) { "Async source identity cannot be reused with different input or origin" }
                    c.commit()
                    return@withContext existing
                }
                c.prepareStatement("SELECT count(*) FROM async_tool_operations WHERE actor_user_id=? AND completed_at IS NULL").use {
                    it.setString(1, origin.actorUserId.value)
                    it.executeQuery().use { rows -> check(rows.next()); require(rows.getInt(1) < 256) { "Async tool queue is full" } }
                }
                val encrypted = cipher.encrypt(plain, "async-tool-invocation:${id.value}")
                c.prepareStatement("""
                    INSERT INTO async_tool_operations(id, actor_user_id, project_id, conversation_id, thread_id,
                        agent_definition_id, source_task_id, source_call_id, slot_number, slot_origin_json, worker_id, workspace_mount_id,
                        invocation_ciphertext, invocation_nonce, invocation_version, phase, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 'ACCEPTED', ?)
                """.trimIndent()).use {
                    it.setString(1, id.value); it.setString(2, origin.actorUserId.value); it.setString(3, origin.projectId.value)
                    it.setString(4, origin.conversationId.value); it.setString(5, origin.threadId.value)
                    it.setString(6, origin.agentDefinitionId.value); it.setString(7, origin.sourceTaskId.value)
                    it.setString(8, origin.sourceToolCallId.value); it.setObject(9, origin.slotOrigin?.slotNumber)
                    it.setString(10, origin.slotOrigin?.let { slot -> json.encodeToString(com.gromozeka.domain.slot.SlotCommandOrigin.serializer(), slot) })
                    val worker = invocation.target as? com.gromozeka.domain.service.ConversationRuntimeTaskTarget.Worker
                    it.setString(11, worker?.workerId?.value); it.setString(12, worker?.workspaceMountId?.value)
                    it.setString(13, encrypted.ciphertext); it.setString(14, encrypted.nonce); it.setInt(15, encrypted.version)
                    it.setTimestamp(16, at.sql()); check(it.executeUpdate() == 1)
                }
                c.commit()
                AsyncToolOperation(id, invocation, at)
            } catch (error: Throwable) {
                c.rollback()
                throw error
            }
        }
    }

    override suspend fun find(id: AsyncToolOperation.Id): AsyncToolOperation? = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT * FROM async_tool_operations WHERE id=?").use {
                it.setString(1, id.value)
                it.executeQuery().use { rows -> if (rows.next()) rows.record() else null }
            }
        }
    }

    override suspend fun findBySource(conversationId: com.gromozeka.domain.model.Conversation.Id,
        taskId: com.gromozeka.domain.service.ConversationRuntimeTask.Id,
        callId: com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall.Id): AsyncToolOperation? =
        select("conversation_id=? AND source_task_id=? AND source_call_id=? LIMIT ?", 1) {
            it.setString(1, conversationId.value); it.setString(2, taskId.value); it.setString(3, callId.value); it.setInt(4, 1)
        }.singleOrNull()

    override suspend fun pendingDeliveryConversations(): Set<com.gromozeka.domain.model.Conversation.Id> = withContext(Dispatchers.IO) {
        dataSource.connection.use { c -> c.prepareStatement("SELECT DISTINCT conversation_id FROM async_tool_operations WHERE completed_at IS NOT NULL AND acknowledged_at IS NOT NULL AND projected_at IS NULL").use { s ->
            s.executeQuery().use { rows -> buildSet { while (rows.next()) add(com.gromozeka.domain.model.Conversation.Id(rows.getString(1))) } }
        } }
    }

    override suspend fun pendingDelivery(conversationId: com.gromozeka.domain.model.Conversation.Id,
        threadId: com.gromozeka.domain.model.Conversation.Thread.Id, limit: Int): List<AsyncToolOperation> =
        select("conversation_id=? AND thread_id=? AND completed_at IS NOT NULL AND acknowledged_at IS NOT NULL AND projected_at IS NULL ORDER BY completed_at,id LIMIT ?", limit) {
            it.setString(1, conversationId.value); it.setString(2, threadId.value); it.setInt(3, limit)
        }

    override suspend fun pending(phase: Phase, limit: Int): List<AsyncToolOperation> {
        require(!phase.isTerminal) { "Pending selection requires a nonterminal phase" }
        return select("phase=? ORDER BY created_at,id LIMIT ?", limit) { it.setString(1, phase.name); it.setInt(2, limit) }
    }

    override suspend fun expiredLeases(at: Instant, limit: Int): List<AsyncToolOperation> =
        select("lease_deadline<=? ORDER BY lease_deadline,id LIMIT ?", limit) { it.setTimestamp(1, at.sql()); it.setInt(2, limit) }

    override suspend fun unprojected(limit: Int): List<AsyncToolOperation> =
        select("completed_at IS NOT NULL AND acknowledged_at IS NOT NULL AND projected_at IS NULL ORDER BY completed_at,id LIMIT ?", limit) { it.setInt(1, limit) }

    override suspend fun acknowledge(id: AsyncToolOperation.Id, at: Instant): Boolean = update("""
        UPDATE async_tool_operations SET acknowledged_at=?, phase=CASE WHEN phase='ACCEPTED' THEN 'ACKNOWLEDGED' ELSE phase END
        WHERE id=? AND acknowledged_at IS NULL
    """.trimIndent()) { it.setTimestamp(1, at.sql()); it.setString(2, id.value) }

    override suspend fun claim(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant, leaseDeadline: Instant): Boolean {
        require(leaseDeadline > at) { "Async lease must expire after its claim" }
        return update("""
            UPDATE async_tool_operations SET phase='CLAIMED', claimed_at=?, executor_session_id=?, lease_deadline=?
            WHERE id=? AND acknowledged_at IS NOT NULL AND cancel_requested_at IS NULL AND
                (phase='ACKNOWLEDGED' OR (phase='CLAIMED' AND lease_deadline<=?))
        """.trimIndent()) {
            it.setTimestamp(1, at.sql()); it.setString(2, owner.value); it.setTimestamp(3, leaseDeadline.sql())
            it.setString(4, id.value); it.setTimestamp(5, at.sql())
        }
    }

    override suspend fun markStarted(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant): Boolean = update("""
        UPDATE async_tool_operations SET phase='RUNNING', started_at=?
        WHERE id=? AND phase='CLAIMED' AND executor_session_id=? AND cancel_requested_at IS NULL AND lease_deadline>?
    """.trimIndent()) {
        it.setTimestamp(1, at.sql()); it.setString(2, id.value); it.setString(3, owner.value); it.setTimestamp(4, at.sql())
    }

    override suspend fun recordWorkerDispatch(id: AsyncToolOperation.Id): Boolean = update("""
        UPDATE async_tool_operations operation SET phase='RUNNING', started_at=child.dispatched_at
        FROM worker_requests child
        WHERE operation.id=? AND operation.phase='CLAIMED' AND operation.worker_request_created_at IS NOT NULL
          AND child.id=operation.id AND child.dispatched_at IS NOT NULL
    """.trimIndent()) { it.setString(1, id.value) }

    override suspend fun renewLease(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, leaseDeadline: Instant): Boolean = update("""
        UPDATE async_tool_operations SET lease_deadline=?
        WHERE id=? AND phase IN ('CLAIMED','RUNNING') AND executor_session_id=?
    """.trimIndent()) { it.setTimestamp(1, leaseDeadline.sql()); it.setString(2, id.value); it.setString(3, owner.value) }

    override suspend fun requestConversationCancellation(conversationId: com.gromozeka.domain.model.Conversation.Id, at: Instant): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { c -> c.prepareStatement("UPDATE async_tool_operations SET cancel_requested_at=? WHERE conversation_id=? AND completed_at IS NULL AND cancel_requested_at IS NULL").use { s ->
            s.setTimestamp(1, at.sql()); s.setString(2, conversationId.value); s.executeUpdate()
        } }
    }

    override suspend fun requestCancellation(id: AsyncToolOperation.Id, at: Instant): Boolean = update("""
        UPDATE async_tool_operations SET cancel_requested_at=COALESCE(cancel_requested_at,?) WHERE id=? AND completed_at IS NULL
    """.trimIndent()) { it.setTimestamp(1, at.sql()); it.setString(2, id.value) }

    override suspend fun finish(observed: AsyncToolOperation, phase: Phase, outcome: AsyncToolOutcome, at: Instant): Boolean {
        require(!observed.phase.isTerminal && phase.isTerminal) { "Only an unfinished async operation can acquire a terminal outcome" }
        observed.copy(phase = phase, completedAt = at, outcome = outcome, leaseDeadline = null)
        val encrypted = cipher.encrypt(json.encodeToString(AsyncToolOutcome.serializer(), outcome), "async-tool-outcome:${observed.id.value}")
        return update("""
            UPDATE async_tool_operations SET phase=?, completed_at=?, outcome_ciphertext=?, outcome_nonce=?, outcome_version=?, lease_deadline=NULL
            WHERE id=? AND phase=? AND executor_session_id IS NOT DISTINCT FROM ? AND completed_at IS NULL
        """.trimIndent()) {
            it.setString(1, phase.name); it.setTimestamp(2, at.sql()); it.setString(3, encrypted.ciphertext)
            it.setString(4, encrypted.nonce); it.setInt(5, encrypted.version); it.setString(6, observed.id.value)
            it.setString(7, observed.phase.name); it.setString(8, observed.executorSessionId?.value)
        }
    }

    override suspend fun markProjected(id: AsyncToolOperation.Id, at: Instant): Boolean = update("""
        UPDATE async_tool_operations SET projected_at=?
        WHERE id=? AND completed_at IS NOT NULL AND acknowledged_at IS NOT NULL AND projected_at IS NULL
    """.trimIndent()) { it.setTimestamp(1, at.sql()); it.setString(2, id.value) }

    private suspend fun select(where: String, limit: Int, bind: (PreparedStatement) -> Unit): List<AsyncToolOperation> = withContext(Dispatchers.IO) {
        require(limit in 1..256)
        dataSource.connection.use { c -> c.prepareStatement("SELECT * FROM async_tool_operations WHERE $where").use { s ->
            bind(s)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.record()) } }
        } }
    }

    private suspend fun update(sql: String, bind: (PreparedStatement) -> Unit): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { c -> c.prepareStatement(sql).use { bind(it); it.executeUpdate() == 1 } }
    }

    private fun ResultSet.record(): AsyncToolOperation {
        val id = AsyncToolOperation.Id(getString("id"))
        fun decrypt(prefix: String) = cipher.decrypt(EncryptedSecret(getString("${prefix}_ciphertext"),
            getString("${prefix}_nonce"), getInt("${prefix}_version")), "async-tool-$prefix:${id.value}")
        val invocation = json.decodeFromString<AsyncToolInvocation>(decrypt("invocation"))
        val origin = invocation.origin
        check(getString("actor_user_id") == origin.actorUserId.value && getString("project_id") == origin.projectId.value &&
            getString("conversation_id") == origin.conversationId.value && getString("thread_id") == origin.threadId.value &&
            getString("agent_definition_id") == origin.agentDefinitionId.value && getString("source_task_id") == origin.sourceTaskId.value &&
            getString("source_call_id") == origin.sourceToolCallId.value) { "Async operation index differs from its authenticated payload" }
        val worker = invocation.target as? com.gromozeka.domain.service.ConversationRuntimeTaskTarget.Worker
        check(getString("worker_id") == worker?.workerId?.value && getString("workspace_mount_id") == worker?.workspaceMountId?.value) {
            "Async Worker index differs from its immutable target"
        }
        val slot = getString("slot_origin_json")?.let { json.decodeFromString<com.gromozeka.domain.slot.SlotCommandOrigin>(it) }
        check(slot == origin.slotOrigin && getObject("slot_number")?.let { (it as Number).toLong() } == slot?.slotNumber) {
            "Async slot index differs from its captured lease"
        }
        return AsyncToolOperation(id, invocation, getTimestamp("created_at").kotlin(),
            phase = Phase.valueOf(getString("phase")), acknowledgedAt = getTimestamp("acknowledged_at")?.kotlin(),
            claimedAt = getTimestamp("claimed_at")?.kotlin(), startedAt = getTimestamp("started_at")?.kotlin(),
            executorSessionId = getString("executor_session_id")?.let(::ConversationRuntimeServerSessionId),
            leaseDeadline = getTimestamp("lease_deadline")?.kotlin(), workerRequestCreatedAt = getTimestamp("worker_request_created_at")?.kotlin(), cancelRequestedAt = getTimestamp("cancel_requested_at")?.kotlin(),
            completedAt = getTimestamp("completed_at")?.kotlin(),
            outcome = getString("outcome_ciphertext")?.let { json.decodeFromString<AsyncToolOutcome>(decrypt("outcome")) },
            projectedAt = getTimestamp("projected_at")?.kotlin())
    }

    private fun Instant.sql() = Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))
    private fun Timestamp.kotlin() = toInstant().let { Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong()) }
}
