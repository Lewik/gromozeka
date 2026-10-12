package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.tool.*
import org.springframework.stereotype.Service

/** Shared attributed-agent boundary; deliberately independent of the collaboration feature flag.
 * This is not the human UI READ/WRITE path and never reads history or Runtime, or schedules work.
 */
@Service
class ConversationAgentAccessService(
    private val conversations: ConversationRepository,
    private val identities: IdentityRepository,
    private val access: ProjectAccessService,
    private val agents: AgentDomainService,
) {
    suspend fun source(context: ToolExecutionContext?): Pair<AgentEndpoint, User.Id> {
        val id = context?.getString(TOOL_CONTEXT_CONVERSATION_ID)?.let(Conversation::Id) ?: error("Conversation context required")
        val thread = context.getString(TOOL_CONTEXT_THREAD_ID)?.let(Conversation.Thread::Id) ?: error("Thread context required")
        val endpoint = AgentEndpoint(id, thread, context.requiredAgentDefinitionId())
        val actor = context.requiredUserId()
        authorize(endpoint, actor)
        return endpoint to actor
    }

    suspend fun authorize(endpoint: AgentEndpoint, actor: User.Id): Conversation {
        val user = identities.findUserById(actor)
        require(user?.canUseAi == true) { "Active AI-enabled user required" }
        val conversation = conversations.findById(endpoint.conversationId) ?: error("Conversation unavailable")
        access.requirePermission(actor, conversation.projectId, ProjectPermission.WRITE)
        require(conversation.currentThread == endpoint.threadId) { "History branch changed; collaboration needs a new request" }
        require(conversation.externalChannel == null) { "External-channel conversations are not supported by this experiment" }
        require(Conversation.Participant.User(actor) in conversation.participants) { "User must participate in both conversations" }
        require(Conversation.Participant.Agent(endpoint.agentId) in conversation.participants) { "Target agent is no longer connected" }
        return conversation
    }

    internal data class Boundary(
        val sourceConversation: Conversation,
        val targetConversation: Conversation,
        val sourceAgent: AgentDefinition,
        val targetAgent: AgentDefinition,
    )

    // The single policy implementation used by both A2A and conversation-wide agent operations.
    // Agent selection and A2A's no-self rule remain in AgentCollaborationService.
    internal suspend fun sameBoundary(source: AgentEndpoint, target: AgentEndpoint, actor: User.Id): Boundary {
        val from = authorize(source, actor)
        val to = authorize(target, actor)
        require(from.projectId == to.projectId) { "Cross-project communication is disabled" }
        val sourceAgent = agents.findById(source.agentId) ?: error("Source agent unavailable")
        val targetAgent = agents.findById(target.agentId) ?: error("Target agent unavailable")
        require(sourceAgent.toolAccess == targetAgent.toolAccess) { "This experiment requires matching agent tool policies" }
        return Boundary(from, to, sourceAgent, targetAgent)
    }

    /**
     * Resolve a self/other conversation-wide target only if ALL its connected agents meet the
     * existing boundary. Empty sets and missing definitions fail; no representative agent is chosen.
     * Observed membership, branch or source-policy changes fail closed. These separate reads are
     * neither an atomic snapshot nor a durable permit; callers must check immediately before access
     * and handle subsequent changes. No history, scheduling, outbox or model effects occur here.
     */
    suspend fun resolveEligibleConversation(
        source: AgentEndpoint,
        actor: User.Id,
        targetConversationId: String,
    ): Conversation {
        require(targetConversationId.isNotBlank()) { "targetConversationId must not be blank" }
        val from = authorize(source, actor)
        val candidate = conversations.findById(Conversation.Id(targetConversationId)) ?: error("Target conversation unavailable")
        val agentIds = candidate.participants.filterIsInstance<Conversation.Participant.Agent>()
            .map { it.agentDefinitionId }.sortedBy { it.value }
        require(agentIds.isNotEmpty()) { "Conversation-wide agent access requires connected agents" }
        var sourcePolicy: ToolAccessPolicy? = null

        suspend fun checkAgent(agentId: AgentDefinition.Id): Conversation {
            val target = AgentEndpoint(candidate.id, candidate.currentThread, agentId)
            val boundary = sameBoundary(source, target, actor)
            requireUnchanged(from, boundary.sourceConversation)
            requireUnchanged(candidate, boundary.targetConversation)
            // Do not stitch together matches against different source policies during the scan.
            if (sourcePolicy != null && sourcePolicy != boundary.sourceAgent.toolAccess) {
                throw ConversationAgentAccessChangedException()
            }
            sourcePolicy = boundary.sourceAgent.toolAccess
            return boundary.targetConversation
        }

        for (agentId in agentIds) checkAgent(agentId)
        // Revalidate both endpoints after the entire set was checked. A newly added/removed agent
        // or changed branch is a failure, not a partially authorized conversation or a retry loop.
        return checkAgent(agentIds.last())
    }

    private fun requireUnchanged(expected: Conversation, observed: Conversation) {
        if (expected.id != observed.id || expected.projectId != observed.projectId ||
            expected.participants != observed.participants || expected.currentThread != observed.currentThread ||
            expected.externalChannel != observed.externalChannel
        ) throw ConversationAgentAccessChangedException()
    }
}

/** A content-free, distinguishable failure for a boundary change observed during resolution. */
class ConversationAgentAccessChangedException : IllegalStateException("Conversation access boundary changed during resolution")
