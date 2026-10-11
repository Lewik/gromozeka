package com.gromozeka.application.service

import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** A query is not a collaboration obligation or a target turn. Eligibility stays owned by A2A. */
@Service
@ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
class AgentContextQueryService(
    private val collaboration: AgentCollaborationService,
    private val snapshots: ConversationContextSnapshotService,
    private val invoker: IsolatedContextQueryInvoker,
) {
    data class Result(val answer: String, val snapshot: ConversationContextSnapshot)

    suspend fun query(context: ToolExecutionContext?, targetConversationId: String, question: String, targetAgentId: String? = null): Result {
        require(targetConversationId.isNotBlank()) { "target_conversation_id must not be blank" }
        require(targetAgentId == null || targetAgentId.isNotBlank()) { "target_agent_id must not be blank" }
        require(question.isNotBlank() && question.length <= 32_000) { "Question must contain 1–32000 characters" }
        val (source, actor) = collaboration.source(context)
        val target = collaboration.resolveEligibleTarget(source, actor, targetConversationId, targetAgentId)
        val snapshot = snapshots.capture(target.endpoint, target.conversation, target.agent, actor)
        // Resolution is neither a lock nor a durable authorization. Do not invoke against a
        // changed agent/branch after assembly, and do not return context after access was revoked.
        val beforeCall = collaboration.resolveEligibleTarget(source, actor, targetConversationId, targetAgentId)
        check(beforeCall.endpoint == target.endpoint && beforeCall.agent == target.agent) {
            "Target agent or history branch changed during context snapshot assembly"
        }
        context?.cancellationSignal?.throwIfCancellationRequested()
        val answer = invoker.answer(snapshot, question)
        collaboration.resolveEligibleTarget(source, actor, targetConversationId, targetAgentId)
        context?.cancellationSignal?.throwIfCancellationRequested()
        return Result(answer, snapshot)
    }
}
