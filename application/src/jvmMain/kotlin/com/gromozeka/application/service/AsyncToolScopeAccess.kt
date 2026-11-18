package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.UserDirectoryService
import org.springframework.stereotype.Service

class AsyncToolScopeDeniedException(val code: String) : IllegalStateException("Async dispatch denied: $code")

/** Current access checks never replace the captured owner, branch or delivery destination. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class AsyncToolScopeAccess(
    private val users: UserDirectoryService,
    private val conversations: ConversationRepository,
    private val projects: ProjectAccessService,
    private val agents: AgentDomainService,
) {
    suspend fun requireExecution(origin: AsyncToolOrigin): Conversation {
        val user = users.findActiveById(origin.actorUserId) ?: throw AsyncToolScopeDeniedException("OWNER_UNAVAILABLE")
        if (!user.canUseAi) throw AsyncToolScopeDeniedException("AI_ACCESS_REVOKED")
        val conversation = conversations.findById(origin.conversationId) ?: throw AsyncToolScopeDeniedException("CONVERSATION_UNAVAILABLE")
        requireBinding(origin, conversation)
        projects.requirePermission(origin.actorUserId, origin.projectId, ProjectPermission.WRITE)
        val agent = agents.findById(origin.agentDefinitionId) ?: throw AsyncToolScopeDeniedException("AGENT_UNAVAILABLE")
        if (agent.type !is AgentDefinition.Type.Global && agent.projectId != origin.projectId) throw AsyncToolScopeDeniedException("AGENT_PROJECT_CHANGED")
        return conversation
    }

    suspend fun deliveryConversation(origin: AsyncToolOrigin): Conversation? {
        if (users.findActiveById(origin.actorUserId) == null) return null
        val conversation = conversations.findById(origin.conversationId) ?: return null
        if (!bindingMatches(origin, conversation) || !projects.can(origin.actorUserId, origin.projectId, ProjectPermission.READ)) return null
        if (agents.findById(origin.agentDefinitionId) == null) return null
        return conversation
    }

    suspend fun requireInspection(actorId: User.Id, operation: AsyncToolOperation, write: Boolean) {
        requireNotNull(users.findActiveById(actorId)) { "Active authenticated owner required" }
        require(actorId == operation.invocation.origin.actorUserId) { "Async operation is unavailable" }
        projects.requirePermission(actorId, operation.invocation.origin.projectId,
            if (write) ProjectPermission.WRITE else ProjectPermission.READ)
    }

    private fun requireBinding(origin: AsyncToolOrigin, conversation: Conversation) {
        if (!bindingMatches(origin, conversation)) throw AsyncToolScopeDeniedException("ORIGIN_BINDING_CHANGED")
    }

    private fun bindingMatches(origin: AsyncToolOrigin, conversation: Conversation): Boolean =
        conversation.projectId == origin.projectId && conversation.currentThread == origin.threadId &&
            conversation.externalChannel == origin.externalChannel &&
            Conversation.Participant.User(origin.actorUserId) in conversation.participants &&
            Conversation.Participant.Agent(origin.agentDefinitionId) in conversation.participants
}
