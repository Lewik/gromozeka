package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.slot.SlotCommandOrigin
import com.gromozeka.domain.tool.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Captured at the source tool boundary. Argument selectors cannot assert any of these identities. */
@Serializable
data class AsyncToolOrigin(
    val actorUserId: User.Id,
    val projectId: Project.Id,
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val sourceTaskId: ConversationRuntimeTask.Id,
    val sourceToolCallId: ContentItem.ToolCall.Id,
    val acknowledgementMessageId: Conversation.Message.Id,
    val readOnly: Boolean,
    val slotOrigin: SlotCommandOrigin?,
    val externalChannel: ExternalConversationChannel? = null,
) {
    init {
        require(acknowledgementMessageId.value == "${sourceTaskId.value}:result") {
            "Async tool acknowledgement must belong to its source task"
        }
    }
}

/** Server-private parent fence for an existing durable Worker request. */
@Serializable
data class AsyncToolDispatchReference(
    val operationId: AsyncToolOperation.Id,
    val executorSessionId: ConversationRuntimeServerSessionId,
)

/** Immutable execution input, also the equality boundary for admission deduplication. */
@Serializable
data class AsyncToolInvocation(
    val origin: AsyncToolOrigin,
    val modelToolName: String,
    val contractFingerprint: String,
    val toolCall: ContentItem.ToolCall,
    val target: ConversationRuntimeTaskTarget,
    val context: Map<String, String>,
) {
    init {
        require(modelToolName.isNotBlank()) { "Async model tool name is required" }
        require(contractFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Async tool contract fingerprint must be SHA-256" }
        require(toolCall.id == origin.sourceToolCallId) { "Async tool call identity mismatch" }
        require(context[TOOL_CONTEXT_USER_ID] == origin.actorUserId.value &&
            context[TOOL_CONTEXT_PROJECT_ID] == origin.projectId.value &&
            context[TOOL_CONTEXT_CONVERSATION_ID] == origin.conversationId.value &&
            context[TOOL_CONTEXT_THREAD_ID] == origin.threadId.value &&
            context[TOOL_CONTEXT_AGENT_DEFINITION_ID] == origin.agentDefinitionId.value &&
            context["toolCallId"] == origin.sourceToolCallId.value) {
            "Async invocation context differs from its captured origin"
        }
        when (target) {
            ConversationRuntimeTaskTarget.Server -> require(origin.slotOrigin == null) {
                "A Server callback cannot assert a filesystem slot lease"
            }
            is ConversationRuntimeTaskTarget.Worker -> {
                require(context[TOOL_CONTEXT_WORKER_ID] == target.workerId.value) { "Async Worker context mismatch" }
                require(context[TOOL_CONTEXT_WORKSPACE_MOUNT_ID] == target.workspaceMountId?.value) {
                    "Async workspace mount context mismatch"
                }
                if (target.workspaceMountId != null) {
                    require(!context[TOOL_CONTEXT_WORKSPACE_ROOT_PATH].isNullOrBlank() && !context[TOOL_CONTEXT_WORKSPACE_ID].isNullOrBlank()) {
                        "Async workspace execution requires its captured workspace and root path"
                    }
                } else require(origin.slotOrigin == null) { "A slot lease requires an exact workspace mount" }
            }
        }
    }
}

@Serializable
data class AsyncToolOutcome(
    val content: List<ContentItem.ToolResult.Data> = emptyList(),
    val structuredContent: JsonObject? = null,
    val isError: Boolean,
    val failureCode: String? = null,
) {
    init {
        require(failureCode == null || (failureCode.isNotBlank() && isError)) {
            "An async failure requires an explicit error outcome"
        }
    }
}

/** Protocol state, not a replacement Worker journal or a managed subprocess lifetime. */
data class AsyncToolOperation(
    val id: Id,
    val invocation: AsyncToolInvocation,
    val createdAt: Instant,
    val phase: Phase = Phase.ACCEPTED,
    val acknowledgedAt: Instant? = null,
    val claimedAt: Instant? = null,
    val startedAt: Instant? = null,
    val executorSessionId: ConversationRuntimeServerSessionId? = null,
    val leaseDeadline: Instant? = null,
    val workerRequestCreatedAt: Instant? = null,
    val cancelRequestedAt: Instant? = null,
    val completedAt: Instant? = null,
    val outcome: AsyncToolOutcome? = null,
    val projectedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init { require(value.isNotBlank() && value.length <= 128) { "Invalid async operation id" } }
    }

    enum class Phase {
        ACCEPTED, ACKNOWLEDGED, CLAIMED, RUNNING, SUCCEEDED, FAILED, CANCELLED, OUTCOME_UNKNOWN;
        val isTerminal: Boolean get() = this !in setOf(ACCEPTED, ACKNOWLEDGED, CLAIMED, RUNNING)
    }

    init {
        require(phase.isTerminal == (completedAt != null) && phase.isTerminal == (outcome != null)) {
            "Terminal async operation requires its durable outcome and completion time"
        }
        require(workerRequestCreatedAt == null || (invocation.target is ConversationRuntimeTaskTarget.Worker && claimedAt != null && acknowledgedAt != null)) {
            "A durable Worker child requires its acknowledged parent claim"
        }
        require((claimedAt == null) == (executorSessionId == null)) { "Async claim requires its owner session" }
        require(startedAt == null || claimedAt != null) { "Async execution cannot start before its claim" }
        require((phase in setOf(Phase.CLAIMED, Phase.RUNNING)) == (leaseDeadline != null)) { "Only a claimed or running async operation holds an execution lease" }
        require(phase !in setOf(Phase.ACKNOWLEDGED, Phase.CLAIMED, Phase.RUNNING) || acknowledgedAt != null) {
            "Async execution cannot pass its durable acknowledgement fence"
        }
        require(phase != Phase.ACCEPTED || (acknowledgedAt == null && claimedAt == null && startedAt == null)) { "Invalid accepted async state" }
        require(phase != Phase.ACKNOWLEDGED || claimedAt == null) { "Acknowledged async operation has already started" }
        require(phase != Phase.CLAIMED || (claimedAt != null && startedAt == null)) { "Claimed async operation has invalid start state" }
        require(phase != Phase.RUNNING || startedAt != null) { "Running async operation has no execution start" }
        require(phase != Phase.CANCELLED || startedAt == null) { "Cancellation after execution starts is not proof that effects were avoided" }
        require(phase != Phase.SUCCEEDED || (outcome?.isError == false && acknowledgedAt != null && startedAt != null)) {
            "A tool cannot succeed before acknowledgement and execution, or with an error outcome"
        }
        require(phase != Phase.OUTCOME_UNKNOWN || claimedAt != null) { "An unknown outcome requires an execution claim" }
        require(phase !in setOf(Phase.FAILED, Phase.CANCELLED, Phase.OUTCOME_UNKNOWN) || outcome?.isError == true) {
            "Failed, cancelled and unknown operations require an explicit error outcome"
        }
        require(projectedAt == null || (phase.isTerminal && acknowledgedAt != null)) {
            "Async completion cannot be projected before its source acknowledgement"
        }
    }
}
