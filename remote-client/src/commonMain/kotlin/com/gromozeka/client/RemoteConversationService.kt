package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.remote.protocol.AddMessageRequest
import com.gromozeka.remote.protocol.ConversationResponse
import com.gromozeka.remote.protocol.ConversationsResponse
import com.gromozeka.remote.protocol.CreateConversationRequest
import com.gromozeka.remote.protocol.DeleteConversationRequest
import com.gromozeka.remote.protocol.DeleteMessagesRequest
import com.gromozeka.remote.protocol.EditMessageRequest
import com.gromozeka.remote.protocol.FindConversationRequest
import com.gromozeka.remote.protocol.FindConversationsByProjectRequest
import com.gromozeka.remote.protocol.FindPinnedConversationsRequest
import com.gromozeka.remote.protocol.ForkConversationRequest
import com.gromozeka.remote.protocol.GetProjectRequest
import com.gromozeka.remote.protocol.GetWorkspaceRequest
import com.gromozeka.remote.protocol.LoadCurrentMessagesRequest
import com.gromozeka.remote.protocol.MessagesResponse
import com.gromozeka.remote.protocol.ProjectResponse
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.SetConversationPinnedRequest
import com.gromozeka.remote.protocol.SquashMessagesRequest
import com.gromozeka.remote.protocol.UpdateConversationDisplayNameRequest
import com.gromozeka.remote.protocol.WorkspaceResponse

internal class RemoteConversationService(
    private val client: GromozekaWsClient,
) : ConversationDomainService {
    override suspend fun create(
        projectId: Project.Id,
        workspaceId: Workspace.Id,
        displayName: String,
        agentDefinitionId: AgentDefinition.Id,
    ): Conversation =
        client.requestTyped<CreateConversationRequest, ConversationResponse>(
            CreateConversationRequest(projectId, workspaceId, agentDefinitionId, displayName)
        ).conversation ?: error("Server returned null conversation after create")

    override suspend fun findById(id: Conversation.Id): Conversation? =
        client.requestTyped<FindConversationRequest, ConversationResponse>(FindConversationRequest(id)).conversation

    override suspend fun getProject(conversationId: Conversation.Id): Project =
        client.requestTyped<GetProjectRequest, ProjectResponse>(GetProjectRequest(conversationId)).project

    override suspend fun getWorkspace(conversationId: Conversation.Id): Workspace =
        client.requestTyped<GetWorkspaceRequest, WorkspaceResponse>(GetWorkspaceRequest(conversationId))
            .workspace ?: error("Server returned no workspace for conversation ${conversationId.value}")

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> =
        client.requestTyped<FindConversationsByProjectRequest, ConversationsResponse>(
            FindConversationsByProjectRequest(projectId)
        ).conversations

    override suspend fun findPinned(): List<Conversation> =
        client.requestTyped<FindPinnedConversationsRequest, ConversationsResponse>(
            FindPinnedConversationsRequest
        ).conversations

    override suspend fun delete(id: Conversation.Id) {
        client.requestTyped<DeleteConversationRequest, SavedResponse>(DeleteConversationRequest(id))
    }

    override suspend fun updateDisplayName(conversationId: Conversation.Id, displayName: String): Conversation? =
        client.requestTyped<UpdateConversationDisplayNameRequest, ConversationResponse>(
            UpdateConversationDisplayNameRequest(conversationId, displayName)
        ).conversation

    override suspend fun setPinned(conversationId: Conversation.Id, pinned: Boolean): Conversation? =
        client.requestTyped<SetConversationPinnedRequest, ConversationResponse>(
            SetConversationPinnedRequest(conversationId, pinned)
        ).conversation

    override suspend fun fork(conversationId: Conversation.Id): Conversation =
        client.requestTyped<ForkConversationRequest, ConversationResponse>(ForkConversationRequest(conversationId))
            .conversation ?: error("Server returned null conversation after fork")

    override suspend fun addMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message,
    ): Conversation? =
        client.requestTyped<AddMessageRequest, ConversationResponse>(AddMessageRequest(conversationId, message))
            .conversation

    override suspend fun loadCurrentMessages(conversationId: Conversation.Id): List<Conversation.Message> =
        client.requestTyped<LoadCurrentMessagesRequest, MessagesResponse>(LoadCurrentMessagesRequest(conversationId))
            .messages

    override suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>,
    ): Conversation? = client.requestTyped<EditMessageRequest, ConversationResponse>(
        EditMessageRequest(conversationId, messageId, newContent)
    ).conversation

    override suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
    ): Conversation? = client.requestTyped<DeleteMessagesRequest, ConversationResponse>(
        DeleteMessagesRequest(conversationId, messageIds)
    ).conversation

    override suspend fun squashMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        squashedContent: List<Conversation.Message.ContentItem>,
    ): Conversation? = client.requestTyped<SquashMessagesRequest, ConversationResponse>(
        SquashMessagesRequest(conversationId, messageIds, squashedContent)
    ).conversation
}
