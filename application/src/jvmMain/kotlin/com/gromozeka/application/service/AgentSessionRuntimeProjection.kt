package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** A bounded projection of existing facts. It never classifies intention, liveness or a deadlock. */
internal object AgentSessionRuntimeProjection {
    const val REQUEST_SCAN_LIMIT = 128
    const val DETAIL_LIMIT = 16

    fun project(
        endpoint: AgentEndpoint,
        actor: User.Id,
        scheduling: ConversationRuntimeSchedulingSnapshot,
        requests: List<AgentRequest>,
        readAt: Instant,
        details: Boolean,
    ): JsonObject {
        require(scheduling.conversationId == endpoint.conversationId) { "Runtime snapshot belongs to another conversation" }
        val control = scheduling.state?.controlState
        val foreground = when {
            control != null && control != ConversationExecutionState.ControlState.RUNNING -> control.name
            scheduling.activeTask != null -> "ACTIVE"
            scheduling.continuationTask != null || scheduling.pendingTasks.isNotEmpty() || scheduling.activeInsertions.isNotEmpty() -> "QUEUED"
            else -> "IDLE"
        }
        val open = requests.take(REQUEST_SCAN_LIMIT).filter {
            it.actorUserId == actor && it.isOpen && (it.source == endpoint || it.target == endpoint)
        }
        val incoming = open.filter { it.target == endpoint }
        val outgoing = open.filter { it.source == endpoint }
        return buildJsonObject {
            put("source", "stored_runtime")
            put("consistency", "best_effort")
            put("read_at", readAt.toString())
            put("foreground_scope", "conversation")
            put("foreground_state", foreground)
            put("control_state", control?.name)
            put("state_updated_at", scheduling.state?.updatedAt?.toString())
            // Only the kind, never an input message, command, arguments or another agent's identity.
            put("active_task_kind", scheduling.activeTask?.payload?.let { it::class.simpleName })
            put("continuation_pending", scheduling.continuationTask != null)
            put("queued_task_count", scheduling.pendingTasks.size)
            put("active_insertion_count", scheduling.activeInsertions.size)
            put("last_event_sequence", scheduling.lastEventSequence)
            putJsonObject("agent_requests") {
                put("scope", "selected_agent_current_thread")
                put("open_incoming", incoming.size)
                put("open_outgoing", outgoing.size)
                put("incoming_waiting_user", incoming.count { it.state == AgentRequest.State.WAITING_USER })
                put("incoming_waiting_result", incoming.count { it.state == AgentRequest.State.WAITING_RESULT })
                // The repository already has a bounded read. Counts describe observed rows, not a global total.
                put("possibly_truncated", requests.size >= REQUEST_SCAN_LIMIT)
                if (details) {
                    put("details_truncated", open.size > DETAIL_LIMIT)
                    putJsonArray("requests") {
                        open.take(DETAIL_LIMIT).forEach { request ->
                            add(buildJsonObject {
                                put("request_id", request.id)
                                put("direction", if (request.target == endpoint) "incoming" else "outgoing")
                                put("state", request.state.name)
                                put("updated_at", request.updatedAt.toString())
                                putJsonArray("wait_for") { request.waitFor.take(DETAIL_LIMIT).forEach { add(JsonPrimitive(it)) } }
                                put("wait_for_truncated", request.waitFor.size > DETAIL_LIMIT)
                            })
                        }
                    }
                }
            }
        }
    }
}
