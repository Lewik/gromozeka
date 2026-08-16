package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.*
import org.springframework.stereotype.Service

@Service
class GromozekaRemoteAuthorization(
    private val projectAccessService: ProjectAccessService,
    private val conversationService: ConversationDomainService,
    private val agentService: AgentDomainService,
    private val promptService: PromptDomainService,
    private val skillService: AgentSkillDomainService,
    private val workspaceService: WorkspaceDomainService,
) {
    suspend fun authorize(
        user: User,
        request: ClientRequest,
    ) {
        when (request) {
            GetSettingsRequest,
            is SaveSettingsRequest,
            is SaveAiCatalogRequest,
            ListMcpServersRequest,
            is CreateMcpServerRequest,
            is UpdateMcpServerRequest,
            is RefreshMcpServerRequest,
            is TestBrowserUseRequest,
            is DeleteMcpServerRequest,
            ListUsersRequest,
            is ListSecurityAuditEventsRequest,
            is CreateUserRequest,
            is UpdateUserRequest,
            is ResetUserPasswordRequest,
            -> requireServerOwner(user)

            ListPersonalAccessTokensRequest,
            is CreatePersonalAccessTokenRequest,
            is RevokePersonalAccessTokenRequest,
            is GetAiUserCredentialStatusRequest,
            is ConfigureAiUserCredentialRequest,
            is RemoveAiUserCredentialRequest,
            GetAiCatalogRequest,
            ListUserDirectoryRequest,
            GetRuntimeCatalogTemplatesRequest,
            GetDefaultAgentRequest,
            CountAgentsRequest,
            is FindRecentProjectsRequest,
            FindProjectsRequest,
            GetConversationTabLayoutRequest,
            is SearchConversationsRequest,
            ListQuickTextActionsRequest,
            is RunQuickTextActionRequest,
            is TranscribeAudioRequest,
            GetSpeechCaptureAvailabilityRequest,
            is StartSpeechCaptureRequest,
            is StopSpeechCaptureRequest,
            is CancelSpeechCaptureRequest,
            is SynthesizeSpeechRequest,
            is StartLiveInterpreterRequest,
            GetLiveVoiceProviderVadAvailabilityRequest,
            is StartLiveVoiceProviderVadRequest,
            is CreateProjectRequest,
            ListWorkersRequest,
            -> Unit

            is FindAgentRequest ->
                requireAgent(user, request.agentId, ProjectPermission.READ)

            is FindAgentsRequest ->
                request.projectId?.let {
                    projectAccessService.requirePermission(user.id, it, ProjectPermission.READ)
                }

            is CreateAgentRequest ->
                requireScope(user, request.projectId, ProjectPermission.WRITE)

            is DuplicateAgentRequest -> {
                requireAgent(user, request.sourceAgentId, ProjectPermission.READ)
                requireScope(user, request.projectId, ProjectPermission.WRITE)
            }

            is UpdateAgentRequest ->
                requireAgent(user, request.agentId, ProjectPermission.WRITE)

            is DeleteAgentRequest ->
                requireAgent(user, request.agentId, ProjectPermission.WRITE)

            is FindAgentSkillsRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.READ,
                )

            is FindAgentSkillRequest ->
                requireSkill(user, request.skillId, ProjectPermission.READ)

            is ImportAgentSkillRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.WRITE,
                )

            is ExportAgentSkillRequest ->
                requireSkill(user, request.skillId, ProjectPermission.READ)

            is DeleteAgentSkillRequest ->
                requireSkill(user, request.skillId, ProjectPermission.WRITE)

            is FindPromptRequest ->
                requirePrompt(user, request.promptId, ProjectPermission.READ)

            is FindPromptsRequest ->
                request.projectId?.let {
                    projectAccessService.requirePermission(user.id, it, ProjectPermission.READ)
                }

            is CreatePromptRequest ->
                requireScope(user, request.projectId, ProjectPermission.WRITE)

            is UpdatePromptRequest ->
                requirePrompt(user, request.promptId, ProjectPermission.WRITE)

            is DeletePromptRequest ->
                requirePrompt(user, request.promptId, ProjectPermission.WRITE)

            is UpdateProjectRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.WRITE,
                )

            is DeleteProjectRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.ADMIN,
                )

            is ListProjectMembershipsRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.READ,
                )

            is SetProjectMembershipRequest,
            is RemoveProjectMembershipRequest,
            -> {
                val projectId = when (request) {
                    is SetProjectMembershipRequest -> request.projectId
                    is RemoveProjectMembershipRequest -> request.projectId
                    else -> error("Unreachable project membership request")
                }
                projectAccessService.requirePermission(
                    user.id,
                    projectId,
                    ProjectPermission.ADMIN,
                )
            }

            is FindProjectByIdRequest,
            is UpdateProjectLastUsedRequest,
            -> {
                val projectId = when (request) {
                    is FindProjectByIdRequest -> request.projectId
                    is UpdateProjectLastUsedRequest -> request.projectId
                    else -> error("Unreachable project request")
                }
                projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
            }

            is PullStateSyncRequest -> authorizeStateQuery(user, request.query)

            is CreateConversationRequest,
            is FindConversationsByProjectRequest,
            is FindWorkspacesByProjectRequest,
            -> {
                val projectId = when (request) {
                    is CreateConversationRequest -> request.projectId
                    is FindConversationsByProjectRequest -> request.projectId
                    is FindWorkspacesByProjectRequest -> request.projectId
                    else -> error("Unreachable project resource request")
                }
                val permission = if (request is CreateConversationRequest) {
                    ProjectPermission.WRITE
                } else {
                    ProjectPermission.READ
                }
                projectAccessService.requirePermission(user.id, projectId, permission)
            }

            is CreateFilesystemWorkspaceRequest ->
                projectAccessService.requirePermission(
                    user.id,
                    request.projectId,
                    ProjectPermission.WRITE,
                )

            is FindConversationRequest,
            is GetProjectRequest,
            is OpenConversationTabRequest,
            is CloseConversationTabRequest,
            is LoadCurrentMessagesRequest,
            is GetTokenStatsRequest,
            is GetMemoryActionItemsRequest,
            -> requireConversation(
                user,
                request.conversationId(),
                ProjectPermission.READ,
            )

            is DeleteConversationRequest,
            is UpdateConversationDisplayNameRequest,
            is UpdateConversationAgentRequest,
            is ForkConversationRequest,
            is AddMessageRequest,
            is EditMessageRequest,
            is DeleteMessagesRequest,
            is SquashMessagesRequest,
            is SquashMessagesWithAiRequest,
            is MemoryActionRequest,
            is SubmitMessageRequest,
            is EnqueueMessageRequest,
            is CancelQueuedMessageRequest,
            is ControlConversationRuntimeRequest,
            is CancelCommandTaskRequest,
            is CancelCommandMonitorRequest,
            -> requireConversation(
                user,
                request.conversationId(),
                ProjectPermission.WRITE,
            )

            is FindWorkspaceRequest,
            is FindWorkspaceMountsRequest,
            -> requireWorkspace(
                user,
                request.workspaceId(),
                ProjectPermission.READ,
            )

            is UpdateWorkspaceRequest ->
                requireWorkspace(user, request.workspaceId, ProjectPermission.WRITE)

            is DeleteWorkspaceRequest ->
                requireWorkspace(user, request.workspaceId, ProjectPermission.ADMIN)

            is DeleteWorkspaceMountRequest -> {
                requireMount(user, request.mountId, ProjectPermission.ADMIN)
            }
        }
    }

    suspend fun requireConversation(
        user: User,
        conversationId: Conversation.Id,
        permission: ProjectPermission,
    ): Conversation {
        val conversation = conversationService.findById(conversationId)
            ?: throw ProjectAccessDeniedException()
        projectAccessService.requirePermission(user.id, conversation.projectId, permission)
        return conversation
    }

    suspend fun readableProjectIds(user: User): Set<Project.Id> =
        projectAccessService.findAll(user.id).mapTo(mutableSetOf(), Project::id)

    suspend fun authorizeStateQuery(
        user: User,
        query: RemoteStateSyncQuery,
    ) {
        when (query) {
            ConversationTabLayoutStateQuery -> Unit
            is ConversationRuntimeStateQuery ->
                requireConversation(user, query.conversationId, ProjectPermission.READ)
            is DeclarativeStateRevisionQuery -> authorizeDeclarativeStateQuery(user, query)
        }
    }

    private suspend fun authorizeDeclarativeStateQuery(
        user: User,
        query: DeclarativeStateRevisionQuery,
    ) {
        when (query.resource) {
            RemoteDeclarativeStateResource.SETTINGS,
            RemoteDeclarativeStateResource.MCP_SERVERS,
            RemoteDeclarativeStateResource.USERS,
            -> requireServerOwner(user)

            RemoteDeclarativeStateResource.PROJECT_CONVERSATIONS,
            RemoteDeclarativeStateResource.PROJECT_WORKSPACES,
            RemoteDeclarativeStateResource.PROJECT_AGENT_SKILLS,
            RemoteDeclarativeStateResource.PROJECT_MEMBERSHIPS,
            -> projectAccessService.requirePermission(
                user.id,
                Project.Id(requireNotNull(query.scopeId)),
                ProjectPermission.READ,
            )

            RemoteDeclarativeStateResource.WORKSPACE_MOUNTS -> requireWorkspace(
                user,
                Workspace.Id(requireNotNull(query.scopeId)),
                ProjectPermission.READ,
            )

            RemoteDeclarativeStateResource.PROJECTS,
            RemoteDeclarativeStateResource.AGENTS,
            RemoteDeclarativeStateResource.PROMPTS,
            RemoteDeclarativeStateResource.AI_CATALOG,
            RemoteDeclarativeStateResource.WORKERS,
            RemoteDeclarativeStateResource.USER_DIRECTORY,
            RemoteDeclarativeStateResource.QUICK_TEXT_ACTIONS,
            -> Unit
        }
    }

    private suspend fun requireAgent(
        user: User,
        agentId: AgentDefinition.Id,
        permission: ProjectPermission,
    ) {
        val agent = agentService.findById(agentId) ?: throw ProjectAccessDeniedException()
        requireScope(user, agent.projectId, permission)
    }

    private suspend fun requirePrompt(
        user: User,
        promptId: Prompt.Id,
        permission: ProjectPermission,
    ) {
        val prompt = promptService.findById(promptId) ?: throw ProjectAccessDeniedException()
        requireScope(user, prompt.projectId, permission)
    }

    private suspend fun requireSkill(
        user: User,
        skillId: AgentSkill.Id,
        permission: ProjectPermission,
    ) {
        val skill = skillService.findById(skillId) ?: throw ProjectAccessDeniedException()
        projectAccessService.requirePermission(user.id, skill.projectId, permission)
    }

    private suspend fun requireWorkspace(
        user: User,
        workspaceId: Workspace.Id,
        permission: ProjectPermission,
    ) {
        val workspace = workspaceService.findById(workspaceId) ?: throw ProjectAccessDeniedException()
        projectAccessService.requirePermission(user.id, workspace.projectId, permission)
    }

    private suspend fun requireMount(
        user: User,
        mountId: WorkspaceMount.Id,
        permission: ProjectPermission,
    ) {
        val mount = workspaceService.findMount(mountId) ?: throw ProjectAccessDeniedException()
        requireWorkspace(user, mount.workspaceId, permission)
    }

    private suspend fun requireScope(
        user: User,
        projectId: Project.Id?,
        permission: ProjectPermission,
    ) {
        if (projectId == null) {
            if (permission != ProjectPermission.READ) {
                requireServerOwner(user)
            }
        } else {
            projectAccessService.requirePermission(user.id, projectId, permission)
        }
    }

    private fun requireServerOwner(user: User) {
        if (user.role != User.Role.OWNER) {
            throw ProjectAccessDeniedException()
        }
    }
}

private fun ClientRequest.conversationId(): Conversation.Id = when (this) {
    is FindConversationRequest -> conversationId
    is GetProjectRequest -> conversationId
    is OpenConversationTabRequest -> conversationId
    is CloseConversationTabRequest -> conversationId
    is DeleteConversationRequest -> conversationId
    is UpdateConversationDisplayNameRequest -> conversationId
    is UpdateConversationAgentRequest -> conversationId
    is ForkConversationRequest -> conversationId
    is AddMessageRequest -> conversationId
    is LoadCurrentMessagesRequest -> conversationId
    is GetTokenStatsRequest -> conversationId
    is EditMessageRequest -> conversationId
    is DeleteMessagesRequest -> conversationId
    is SquashMessagesRequest -> conversationId
    is SquashMessagesWithAiRequest -> conversationId
    is MemoryActionRequest -> conversationId
    is GetMemoryActionItemsRequest -> conversationId
    is SubmitMessageRequest -> conversationId
    is EnqueueMessageRequest -> conversationId
    is CancelQueuedMessageRequest -> conversationId
    is ControlConversationRuntimeRequest -> conversationId
    is CancelCommandTaskRequest -> conversationId
    is CancelCommandMonitorRequest -> conversationId
    else -> error("Request does not address a conversation: ${this::class.simpleName}")
}

private fun ClientRequest.workspaceId(): Workspace.Id = when (this) {
    is FindWorkspaceRequest -> workspaceId
    is FindWorkspaceMountsRequest -> workspaceId
    else -> error("Request does not address a workspace: ${this::class.simpleName}")
}
