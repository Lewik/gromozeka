package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.AgentCollaborationRepository
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
@DependsOn("postgresFlyway")
class PostgresAgentCollaborationRepository(private val dataSource: DataSource, private val json: Json) : AgentCollaborationRepository {
    private suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try { block(c).also { c.commit() } } catch (e: Throwable) { c.rollback(); throw e }
        }
    }

    private fun Connection.rows(sql: String, vararg args: Any?): List<String> = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
    private fun Connection.execute(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
    }

    override suspend fun requests(conversationId: Conversation.Id): List<AgentRequest> = transaction { c ->
        c.rows("SELECT record_json::text FROM agent_requests WHERE source_conversation_id = ? OR target_conversation_id = ? ORDER BY (state IN ('WORKING','WAITING_USER','WAITING_RESULT')) DESC, (record_json ->> 'createdAt') DESC, id LIMIT 128", conversationId.value, conversationId.value)
            .map { json.decodeFromString<AgentRequest>(it) }
    }
    override suspend fun findRequest(id: String): AgentRequest? = transaction { c ->
        c.rows("SELECT record_json::text FROM agent_requests WHERE id = ?", id).firstOrNull()?.let { json.decodeFromString(it) }
    }
    private fun Connection.matches(expected: List<AgentRequest>): Boolean = expected.sortedBy { it.id }.all { item ->
        rows("SELECT revision::text FROM agent_requests WHERE id = ? FOR UPDATE", item.id).singleOrNull()?.toLong() == item.revision
    }
    private fun Connection.writeRequests(updated: List<AgentRequest>) {
        updated.forEach { r ->
            execute("UPDATE agent_requests SET revision = ?, state = ?, record_json = ?::jsonb WHERE id = ?", r.revision, r.state.name, json.encodeToString(r), r.id)
        }
    }
    private fun Connection.writeDeliveries(deliveries: List<AgentDelivery>) {
        deliveries.forEach { d ->
            execute("INSERT INTO agent_deliveries(id, request_id, target_conversation_id, state, created_at, record_json) VALUES (?, ?, ?, ?, ?::timestamptz, ?::jsonb) ON CONFLICT (id) DO NOTHING",
                d.id, d.requestId, d.target.conversationId.value, d.state.name, d.createdAt.toString(), json.encodeToString(d))
        }
    }
    override suspend fun change(expected: List<AgentRequest>, updated: List<AgentRequest>, deliveries: List<AgentDelivery>): Boolean = transaction { c ->
        require(expected.map { it.id }.toSet() == updated.map { it.id }.toSet())
        if (!c.matches(expected)) return@transaction false
        c.writeRequests(updated); c.writeDeliveries(deliveries); true
    }
    override suspend fun create(request: AgentRequest?, delivery: AgentDelivery) = transaction { c ->
        if (request != null) {
            c.execute("INSERT INTO agent_requests(id, source_conversation_id, target_conversation_id, revision, state, record_json) VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING",
                request.id, request.source.conversationId.value, request.target.conversationId.value, request.revision, request.state.name, json.encodeToString(request))
            val saved = c.rows("SELECT record_json::text FROM agent_requests WHERE id = ?", request.id).single()
            val old = json.decodeFromString<AgentRequest>(saved)
            require(old.source == request.source && old.target == request.target && old.text == request.text && old.actorUserId == request.actorUserId) { "Idempotency key belongs to a different request" }
        }
        c.writeDeliveries(listOf(delivery))
        val old = json.decodeFromString<AgentDelivery>(c.rows("SELECT record_json::text FROM agent_deliveries WHERE id = ?", delivery.id).single())
        require(old.source == delivery.source && old.target == delivery.target && old.text == delivery.text && old.actorUserId == delivery.actorUserId) { "Idempotency key belongs to a different delivery" }
    }
    override suspend fun pendingDeliveries(after: AgentCollaborationRepository.DeliveryCursor?): List<AgentDelivery> = transaction { c ->
        val select = "SELECT record_json::text FROM agent_deliveries WHERE state = 'PENDING'"
        val order = " ORDER BY created_at, id LIMIT 64"
        val rows = if (after == null) c.rows(select + order) else c.rows(
            "$select AND (created_at, id) > (?::timestamptz, ?)$order", after.createdAt.toString(), after.id,
        )
        rows.map { json.decodeFromString(it) }
    }
    override suspend fun findDelivery(id: String): AgentDelivery? = transaction { c ->
        c.rows("SELECT record_json::text FROM agent_deliveries WHERE id = ?", id).firstOrNull()?.let { json.decodeFromString(it) }
    }
    override suspend fun settleDelivery(delivery: AgentDelivery) = transaction { c ->
        c.execute("UPDATE agent_deliveries SET state = ?, record_json = ?::jsonb WHERE id = ? AND state = 'PENDING'", delivery.state.name, json.encodeToString(delivery), delivery.id); Unit
    }
    override suspend fun saveDraft(draft: AgentResponseDraft) = transaction { c ->
        c.execute("INSERT INTO agent_response_drafts(id, conversation_id, record_json) VALUES (?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING", draft.id, draft.endpoint.conversationId.value, json.encodeToString(draft)); Unit
    }
    override suspend fun findDraft(id: String): AgentResponseDraft? = transaction { c ->
        c.rows("SELECT record_json::text FROM agent_response_drafts WHERE id = ?", id).firstOrNull()?.let { json.decodeFromString(it) }
    }
    override suspend fun failDraft(id: String, reason: String) = transaction { c ->
        val draft = c.rows("SELECT record_json::text FROM agent_response_drafts WHERE id = ? FOR UPDATE", id).firstOrNull()?.let { json.decodeFromString<AgentResponseDraft>(it) }
        if (draft != null && draft.decision == null) c.execute("UPDATE agent_response_drafts SET record_json = ?::jsonb WHERE id = ?", json.encodeToString(draft.copy(failure = reason)), id)
        Unit
    }
    override suspend fun applyDecision(draft: AgentResponseDraft, activeTaskId: String, decision: AgentResponseDecision, updated: List<AgentRequest>, deliveries: List<AgentDelivery>): Boolean = transaction { c ->
        // Serialize with normal runtime submissions, including steer, pause and stop.
        val scheduling = c.rows("SELECT scheduling::text FROM conversation_runtime_records WHERE conversation_id = ? FOR UPDATE", draft.endpoint.conversationId.value)
            .firstOrNull()?.let { json.decodeFromString<ConversationRuntimeSchedulingState>(it) } ?: return@transaction false
        if (scheduling.activeTask?.id?.value != activeTaskId || scheduling.executionState?.controlState in setOf(ConversationExecutionState.ControlState.STOPPING, ConversationExecutionState.ControlState.INTERRUPTING) ||
            scheduling.pendingTasks.any { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }) return@transaction false
        val thread = c.rows("SELECT current_thread_id FROM conversations WHERE id = ?", draft.endpoint.conversationId.value).singleOrNull()
        if (thread != draft.endpoint.threadId.value) return@transaction false
        val old = json.decodeFromString<AgentResponseDraft>(c.rows("SELECT record_json::text FROM agent_response_drafts WHERE id = ? FOR UPDATE", draft.id).single())
        if (old.decision != null) return@transaction old.decision == decision
        if (!c.matches(draft.incoming + draft.outgoing)) return@transaction false
        c.writeRequests(updated); c.writeDeliveries(deliveries)
        c.execute("UPDATE agent_response_drafts SET record_json = ?::jsonb WHERE id = ?", json.encodeToString(draft.copy(decision = decision)), draft.id)
        true
    }
}
