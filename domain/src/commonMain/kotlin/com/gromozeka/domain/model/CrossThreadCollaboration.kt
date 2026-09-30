package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** An agent definition is not an address: the conversation and exact history branch are required. */
@Serializable
data class AgentEndpoint(
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val agentId: AgentDefinition.Id,
)

@Serializable
data class AgentRequest(
    val id: String,
    val source: AgentEndpoint,
    val target: AgentEndpoint,
    val actorUserId: User.Id,
    val text: String,
    val createdAt: Instant,
    val readOnly: Boolean = false,
    val updatedAt: Instant = createdAt,
    val revision: Long = 0,
    val state: State = State.WORKING,
    val result: String? = null,
    val waitFor: List<String> = emptyList(),
) {
    @Serializable
    enum class State { WORKING, WAITING_USER, WAITING_RESULT, COMPLETED, CANCELLED, BLOCKED }
    val isOpen: Boolean get() = state in setOf(State.WORKING, State.WAITING_USER, State.WAITING_RESULT)
}

@Serializable
data class AgentDelivery(
    val id: String,
    val requestId: String?,
    val source: AgentEndpoint,
    val target: AgentEndpoint,
    val actorUserId: User.Id,
    val kind: Kind,
    val text: String,
    val createdAt: Instant,
    val readOnly: Boolean = false,
    val state: State = State.PENDING,
    val error: String? = null,
) {
    @Serializable enum class Kind { REQUEST, RESULT, MESSAGE }
    @Serializable enum class State { PENDING, QUEUED, BLOCKED }
}

/** The checker can route text and settle obligations, but cannot execute arbitrary tools. */
@Serializable
data class AgentResponseDecision(
    val userText: String?,
    val next: Next,
    val waitFor: List<String>,
    val requests: List<RequestDecision>,
) {
    @Serializable enum class Next { CONTINUE, ASK_USER, WAIT, COMPLETE }
    @Serializable data class RequestDecision(
        val requestId: String,
        val state: AgentRequest.State,
        val result: String?,
        val waitFor: List<String>,
    )
}

@Serializable
data class AgentResponseDraft(
    val id: String,
    val endpoint: AgentEndpoint,
    val actorUserId: User.Id,
    val rootMessageId: Conversation.Message.Id,
    val turnId: String,
    val iteration: Int,
    val messages: List<Conversation.Message>,
    val incoming: List<AgentRequest>,
    val outgoing: List<AgentRequest>,
    val waitableIds: List<String>,
    val contextText: String,
    val inputMessageIds: List<String>,
    val createdAt: Instant,
    val decision: AgentResponseDecision? = null,
    val failure: String? = null,
)

/** Built-in collaboration tools are unavailable in externally bound conversations. */
val AGENT_COLLABORATION_TOOL_NAMES: Set<String> = setOf(
    "grz_agent_sessions", "grz_agent_request", "grz_agent_message",
    "grz_agent_reply", "grz_agent_requests", "grz_agent_request_cancel",
)

const val COLLABORATION_ORIGINAL_CONTENT = "gromozekaCollaborationOriginalContent"
const val COLLABORATION_MESSAGE_KIND = "gromozekaCollaborationKind"
const val COLLABORATION_DELIVERY_ID = "gromozekaCollaborationDeliveryId"
