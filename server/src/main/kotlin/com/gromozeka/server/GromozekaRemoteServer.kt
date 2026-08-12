package com.gromozeka.server

import com.gromozeka.application.service.AiUserCredentialApplicationService
import com.gromozeka.application.service.McpServerManagementService
import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpTransportValueRemovals
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeIngressService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.QuickTextActionService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.domain.service.SecurityAuditService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.service.UserConversationTabLayoutService
import com.gromozeka.domain.service.UserAdministrationService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.infrastructure.ai.openai.TtsService
import com.gromozeka.remote.protocol.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

@Service
class GromozekaRemoteServer(
    private val settingsService: SettingsService,
    private val aiConfigurationService: AiConfigurationService,
    private val mcpServerManagementService: McpServerManagementService,
    private val runtimeCatalogTemplateService: RuntimeCatalogTemplateService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val agentDomainService: AgentDomainService,
    private val agentSkillDomainService: AgentSkillDomainService,
    private val promptDomainService: PromptDomainService,
    private val conversationDomainService: ConversationDomainService,
    private val conversationTabLayoutService: UserConversationTabLayoutService,
    private val projectAccessService: ProjectAccessService,
    private val workspaceCatalogService: WorkspaceCatalogService,
    private val workspaceManagementService: WorkspaceManagementService,
    private val workerCatalogService: WorkerCatalogService,
    private val workerAccessService: WorkerAccessService,
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationRuntimeIngressService: ConversationRuntimeIngressService,
    private val conversationTokenStatsService: ConversationTokenStatsService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val quickTextActionService: QuickTextActionService,
    private val conversationNameSearchService: ConversationNameSearchService,
    private val sttService: SttService,
    private val ttsService: TtsService,
    private val memoryStore: MemoryStore,
    private val liveInterpreterApplicationService: LiveInterpreterApplicationService,
    private val liveVoiceProviderVadApplicationService: LiveVoiceProviderVadApplicationService,
    private val speechCaptureApplicationService: SpeechCaptureApplicationService,
    private val clientPresentationRegistry: ClientPresentationRegistry,
    private val authenticationService: AuthenticationService,
    private val personalAccessTokenService: PersonalAccessTokenService,
    private val aiUserCredentialService: AiUserCredentialApplicationService,
    private val userAdministrationService: UserAdministrationService,
    private val securityAuditService: SecurityAuditService,
    private val userDirectoryService: UserDirectoryService,
    private val remoteAuthorization: GromozekaRemoteAuthorization,
) {
    private val log = KLoggers.logger(this)
    private val sessionAccessGuard = RemoteSessionAccessGuard(authenticationService, remoteAuthorization)
    private val memoryActionItemRevisionJson = Json {
        encodeDefaults = true
        classDiscriminator = "memoryType"
    }

    internal suspend fun handle(
        session: DefaultWebSocketServerSession,
        authenticatedSession: AuthenticatedRemoteSession,
    ) {
        val connectionId = uuid7()
        val sender = RemoteSessionSender(session)
        val conversationSubscriptions = mutableMapOf<String, Job>()
        val conversationTabLayoutSubscriptions = mutableMapOf<String, Job>()
        val liveInterpreterOwner = LiveInterpreterSessionOwner(
            userId = authenticatedSession.principal.user.id,
            connectionId = connectionId,
        )
        val liveVoiceProviderVadOwner = LiveVoiceProviderVadSessionOwner(
            userId = authenticatedSession.principal.user.id,
            connectionId = connectionId,
        )
        val speechCaptureOwner = SpeechCaptureSessionOwner(
            userId = authenticatedSession.principal.user.id,
            connectionId = connectionId,
        )
        coroutineScope {
            val concurrentSpeechRequests = mutableListOf<Job>()
            val authenticationMonitor = launch {
                while (isActive) {
                    delay(AUTHENTICATION_RECHECK_INTERVAL_MILLIS)
                    if (authenticationService.authenticate(authenticatedSession.token) == null) {
                        session.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "Authentication session is no longer active",
                            )
                        )
                        break
                    }
                }
            }
            try {
                for (frame in session.incoming) {
                    val currentUser = sessionAccessGuard.requireUser(authenticatedSession)
                    val decoded = when (frame) {
                        is Frame.Binary -> RemoteProtocolEncoding.CBOR to RemoteProtocolCodec.decodeClientBinary(frame.readBytes())
                        is Frame.Text -> RemoteProtocolEncoding.JSON to RemoteProtocolCodec.decodeClientText(frame.readText())
                        else -> null
                    }
                    if (decoded != null) {
                        val (encoding, envelope) = decoded
                        if (envelope.payload !is RegisterClientSessionCommand) {
                            clientPresentationRegistry.requireRegistered(connectionId)
                            clientPresentationRegistry.updateEncoding(connectionId, encoding)
                        }
                        when (val payload = envelope.payload) {
                            is RegisterClientSessionCommand -> clientPresentationRegistry.register(
                                userId = currentUser.id,
                                connectionId = connectionId,
                                command = payload,
                                encoding = encoding,
                                send = { presentationPayload, presentationEncoding ->
                                    sender.send(uuid7(), presentationPayload, presentationEncoding)
                                },
                            )
                            is ReportClientActivityCommand ->
                                clientPresentationRegistry.activate(connectionId, payload.kind)
                            is ClientRequest -> {
                                if (payload is SubmitMessageRequest || payload is EnqueueMessageRequest) {
                                    clientPresentationRegistry.activate(
                                        connectionId,
                                        ClientActivityKind.USER_INTERACTION,
                                    )
                                }
                                val handle = suspend {
                                    handleRequest(
                                        sender = sender,
                                        requestId = envelope.id,
                                        request = payload,
                                        encoding = encoding,
                                        user = currentUser,
                                        liveInterpreterOwner = liveInterpreterOwner,
                                        liveVoiceProviderVadOwner = liveVoiceProviderVadOwner,
                                        speechCaptureOwner = speechCaptureOwner,
                                    )
                                }
                                if (payload.isConcurrentSpeechRequest()) {
                                    concurrentSpeechRequests.removeAll(Job::isCompleted)
                                    concurrentSpeechRequests += launch { handle() }
                                } else {
                                    handle()
                                }
                            }
                            is ObserveConversationCommand -> {
                                conversationSubscriptions[payload.subscriptionId]?.cancel()
                                conversationSubscriptions[payload.subscriptionId] = launch {
                                    observeConversation(
                                        sender,
                                        payload,
                                        encoding,
                                        authenticatedSession,
                                    )
                                }
                            }
                            is StopObserveConversationCommand -> conversationSubscriptions.remove(payload.subscriptionId)?.cancel()
                            is ObserveConversationTabLayoutCommand -> {
                                conversationTabLayoutSubscriptions[payload.subscriptionId]?.cancel()
                                conversationTabLayoutSubscriptions[payload.subscriptionId] = launch {
                                    observeConversationTabLayout(
                                        sender,
                                        payload,
                                        encoding,
                                        authenticatedSession,
                                    )
                                }
                            }
                            is StopObserveConversationTabLayoutCommand ->
                                conversationTabLayoutSubscriptions.remove(payload.subscriptionId)?.cancel()
                            is SynthesizeSpeechStreamCommand -> launch {
                                handleSynthesizeSpeechStream(sender, envelope.id, payload, encoding)
                            }
                            is LiveInterpreterAudioChunkCommand ->
                                liveInterpreterApplicationService.append(liveInterpreterOwner, payload)
                            is LiveInterpreterTranscriptChunkCommand ->
                                liveInterpreterApplicationService.append(liveInterpreterOwner, payload)
                            is StopLiveInterpreterCommand ->
                                liveInterpreterApplicationService.stop(liveInterpreterOwner, payload)
                            is LiveVoiceProviderVadAudioChunkCommand ->
                                liveVoiceProviderVadApplicationService.append(liveVoiceProviderVadOwner, payload)
                            is StopLiveVoiceProviderVadCommand ->
                                liveVoiceProviderVadApplicationService.stop(liveVoiceProviderVadOwner, payload)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(error) { "Remote WebSocket session failed: ${error.message}" }
                throw error
            } finally {
                authenticationMonitor.cancel()
                concurrentSpeechRequests.forEach(Job::cancel)
                concurrentSpeechRequests.joinAll()
                conversationSubscriptions.values.forEach { it.cancel() }
                conversationSubscriptions.clear()
                conversationTabLayoutSubscriptions.values.forEach { it.cancel() }
                conversationTabLayoutSubscriptions.clear()
                liveInterpreterApplicationService.stopOwnedBy(liveInterpreterOwner)
                liveVoiceProviderVadApplicationService.stopOwnedBy(liveVoiceProviderVadOwner)
                speechCaptureApplicationService.stopOwnedBy(speechCaptureOwner)
                clientPresentationRegistry.disconnect(connectionId)
            }
        }
    }

    private fun ClientRequest.isConcurrentSpeechRequest(): Boolean =
        this is GetSpeechCaptureAvailabilityRequest ||
            this is StartSpeechCaptureRequest ||
            this is StopSpeechCaptureRequest ||
            this is CancelSpeechCaptureRequest ||
            this is StartLiveVoiceProviderVadRequest

    private suspend fun handleRequest(
        sender: RemoteSessionSender,
        requestId: String,
        request: ClientRequest,
        encoding: RemoteProtocolEncoding,
        user: User,
        liveInterpreterOwner: LiveInterpreterSessionOwner,
        liveVoiceProviderVadOwner: LiveVoiceProviderVadSessionOwner,
        speechCaptureOwner: SpeechCaptureSessionOwner,
    ) {
        val response = try {
            remoteAuthorization.authorize(user, request)
            when (request) {
                GetSettingsRequest -> SettingsResponse(settingsService.settings)
                is SaveSettingsRequest -> {
                    settingsService.saveSettings(request.settings)
                    SavedResponse
                }
                GetAiCatalogRequest -> AiCatalogResponse(
                    RemoteAiCatalogSnapshot.from(aiConfigurationService.snapshot)
                )
                is SaveAiCatalogRequest -> AiCatalogResponse(
                    RemoteAiCatalogSnapshot.from(
                        aiConfigurationService.replaceCatalog(
                            request.catalog,
                            request.expectedRevision,
                            request.secretMutations,
                        )
                    )
                )
                ListMcpServersRequest -> McpServersResponse(
                    mcpServerManagementService.list().map(McpServer::toRemoteView)
                )
                is CreateMcpServerRequest -> McpServerResponse(
                    mcpServerManagementService.create(request.config).toRemoteView()
                )
                is UpdateMcpServerRequest -> McpServerResponse(
                    mcpServerManagementService.update(
                        config = request.config,
                        expectedRevision = request.expectedRevision,
                        transportValueRemovals = McpTransportValueRemovals(
                            environmentVariables = request.removeEnvironmentVariables,
                            httpHeaders = request.removeHttpHeaders,
                        ),
                    ).toRemoteView()
                )
                is RefreshMcpServerRequest -> McpServerResponse(
                    mcpServerManagementService.refresh(
                        serverId = request.serverId,
                        expectedRevision = request.expectedRevision,
                    ).toRemoteView()
                )
                is TestBrowserUseRequest -> mcpServerManagementService
                    .testBrowserUse(request.serverId)
                    .let { result ->
                        BrowserUseProbeResponse(
                            screenshot = result.screenshot,
                            mediaType = result.mediaType,
                            fileName = result.fileName,
                        )
                    }
                is DeleteMcpServerRequest -> {
                    mcpServerManagementService.delete(
                        serverId = request.serverId,
                        expectedRevision = request.expectedRevision,
                    )
                    SavedResponse
                }
                ListPersonalAccessTokensRequest -> PersonalAccessTokensResponse(
                    personalAccessTokenService.list(user.id)
                        .map { it.toPersonalAccessTokenView() }
                )
                is CreatePersonalAccessTokenRequest -> {
                    val expiresAt = request.expiresInDays?.let { lifetimeDays ->
                        require(lifetimeDays in 1..MAX_PERSONAL_ACCESS_TOKEN_LIFETIME_DAYS) {
                            "Personal access token lifetime must be between 1 and " +
                                "$MAX_PERSONAL_ACCESS_TOKEN_LIFETIME_DAYS days"
                        }
                        Clock.System.now() + lifetimeDays.days
                    }
                    val issued = personalAccessTokenService.issue(
                        userId = user.id,
                        name = request.name,
                        scopes = request.scopes,
                        expiresAt = expiresAt,
                    )
                    IssuedPersonalAccessTokenResponse(
                        token = issued.token.toPersonalAccessTokenView(),
                        rawToken = issued.rawToken,
                    )
                }
                is RevokePersonalAccessTokenRequest -> PersonalAccessTokenRevokedResponse(
                    personalAccessTokenService.revoke(
                        userId = user.id,
                        tokenId = request.tokenId,
                    )
                )
                is GetAiUserCredentialStatusRequest -> AiUserCredentialStatusResponse(
                    aiUserCredentialService.status(user.id, request.connectionId)
                )
                is ConfigureAiUserCredentialRequest -> AiUserCredentialStatusResponse(
                    aiUserCredentialService.configure(user.id, request.connectionId, request.secret)
                )
                is RemoveAiUserCredentialRequest -> AiUserCredentialStatusResponse(
                    aiUserCredentialService.remove(user.id, request.connectionId)
                )
                ListUsersRequest -> UsersResponse(
                    userAdministrationService.list(user)
                )
                is ListSecurityAuditEventsRequest -> SecurityAuditEventsResponse(
                    securityAuditService.listRecent(user, request.limit)
                )
                is CreateUserRequest -> UserResponse(
                    request.password.usePasswordChars { password ->
                        userAdministrationService.create(
                            actor = user,
                            username = request.username,
                            displayName = request.displayName,
                            password = password,
                            role = request.role,
                        )
                    }
                )
                is UpdateUserRequest -> UserResponse(
                    userAdministrationService.update(
                        actor = user,
                        userId = request.userId,
                        displayName = request.displayName,
                        status = request.status,
                        role = request.role,
                    )
                )
                is ResetUserPasswordRequest -> {
                    request.password.usePasswordChars { password ->
                        userAdministrationService.resetPassword(
                            actor = user,
                            userId = request.userId,
                            password = password,
                        )
                    }
                    UserPasswordResetResponse
                }
                ListUserDirectoryRequest -> UserDirectoryResponse(
                    userDirectoryService.listActive().map {
                        UserDirectoryEntry(
                            id = it.id,
                            username = it.username,
                            displayName = it.displayName,
                        )
                    }
                )
                is ListProjectMembershipsRequest -> ProjectMembershipsResponse(
                    projectAccessService.listMemberships(user.id, request.projectId)
                )
                is SetProjectMembershipRequest -> ProjectMembershipResponse(
                    projectAccessService.setMembership(
                        actorUserId = user.id,
                        projectId = request.projectId,
                        userId = request.userId,
                        role = request.role,
                    )
                )
                is RemoveProjectMembershipRequest -> ProjectMembershipRemovedResponse(
                    projectAccessService.removeMembership(
                        actorUserId = user.id,
                        projectId = request.projectId,
                        userId = request.userId,
                    )
                )
                GetRuntimeCatalogTemplatesRequest -> RuntimeCatalogTemplatesResponse(
                    runtimeCatalogTemplateService.getTemplates()
                )

                GetDefaultAgentRequest -> DefaultAgentResponse(defaultAgentProvider.getDefault())
                is FindAgentRequest -> AgentResponse(agentDomainService.findById(request.agentId))
                is FindAgentsRequest -> {
                    val readableProjectIds = remoteAuthorization.readableProjectIds(user)
                    AgentsResponse(
                        request.projectId?.let { agentDomainService.findByProject(it) }
                            ?: agentDomainService.findAll().filter {
                                it.projectId == null || it.projectId in readableProjectIds
                            }
                    )
                }
                is CreateAgentRequest -> AgentResponse(
                    agentDomainService.createAgent(
                        request.projectId,
                        request.name,
                        request.prompts,
                        request.runtimeSelection,
                        request.runtimeOverrides,
                        request.tools,
                        request.description,
                        request.skills,
                    )
                )
                is DuplicateAgentRequest -> AgentResponse(
                    agentDomainService.duplicateAgent(
                        request.projectId,
                        request.sourceAgentId,
                        request.name,
                    )
                )
                is UpdateAgentRequest -> AgentResponse(
                    agentDomainService.update(
                        request.agentId,
                        request.name,
                        request.prompts,
                        request.description,
                        request.skills,
                        request.runtimeSelection,
                        request.runtimeOverrides,
                        request.tools,
                    )
                )
                is DeleteAgentRequest -> {
                    agentDomainService.delete(request.agentId)
                    SavedResponse
                }
                CountAgentsRequest -> {
                    val readableProjectIds = remoteAuthorization.readableProjectIds(user)
                    CountResponse(
                        agentDomainService.findAll().count {
                            it.projectId == null || it.projectId in readableProjectIds
                        }
                    )
                }
                is FindAgentSkillsRequest -> AgentSkillsResponse(
                    agentSkillDomainService.findByProject(request.projectId)
                )
                is FindAgentSkillRequest -> AgentSkillResponse(
                    agentSkillDomainService.findById(request.skillId)
                )
                is ImportAgentSkillRequest -> AgentSkillResponse(
                    agentSkillDomainService.importPackage(request.projectId, request.source)
                )
                is ExportAgentSkillRequest -> AgentSkillPackageResponse(
                    agentSkillDomainService.exportPackage(request.skillId)
                )
                is DeleteAgentSkillRequest -> {
                    agentSkillDomainService.delete(request.skillId)
                    SavedResponse
                }
                is FindPromptRequest -> PromptResponse(promptDomainService.findById(request.promptId))
                is FindPromptsRequest -> {
                    val readableProjectIds = remoteAuthorization.readableProjectIds(user)
                    PromptsResponse(
                        request.projectId?.let { promptDomainService.findByProject(it) }
                            ?: promptDomainService.findAll().filter {
                                it.projectId == null || it.projectId in readableProjectIds
                            }
                    )
                }
                is CreatePromptRequest -> PromptResponse(
                    promptDomainService.createPrompt(request.projectId, request.name, request.content)
                )
                is UpdatePromptRequest -> PromptResponse(
                    promptDomainService.updatePrompt(request.promptId, request.name, request.content)
                )
                is DeletePromptRequest -> {
                    promptDomainService.deletePrompt(request.promptId)
                    SavedResponse
                }
                is CreateProjectRequest -> ProjectResponse(
                    projectAccessService.create(user.id, request.name, request.description)
                )
                is UpdateProjectRequest -> ProjectResponse(
                    projectAccessService.update(
                        user.id,
                        request.projectId,
                        request.name,
                        request.description,
                    )
                )
                is DeleteProjectRequest -> {
                    projectAccessService.delete(user.id, request.projectId)
                    SavedResponse
                }
                is FindProjectByIdRequest -> NullableProjectResponse(
                    projectAccessService.findById(user.id, request.projectId)
                )
                is UpdateProjectLastUsedRequest -> NullableProjectResponse(
                    projectAccessService.updateLastUsed(user.id, request.projectId)
                )
                is CreateConversationRequest -> ConversationResponse(
                    conversationDomainService.create(
                        request.projectId,
                        request.displayName,
                        request.agentDefinitionId,
                    )
                )

                is FindConversationRequest -> ConversationResponse(conversationDomainService.findById(request.conversationId))
                is GetProjectRequest -> ProjectResponse(conversationDomainService.getProject(request.conversationId))
                is FindRecentProjectsRequest -> ProjectsResponse(
                    projectAccessService.findRecent(user.id, request.limit)
                )
                FindProjectsRequest -> ProjectsResponse(projectAccessService.findAll(user.id))
                is FindConversationsByProjectRequest -> ConversationsResponse(
                    conversationDomainService.findByProject(request.projectId)
                )
                GetConversationTabLayoutRequest -> ConversationTabLayoutResponse(
                    filterConversationTabLayout(user, conversationTabLayoutService.snapshot(user.id))
                )
                is OpenConversationTabRequest -> ConversationTabLayoutResponse(
                    filterConversationTabLayout(
                        user,
                        conversationTabLayoutService.open(user.id, request.conversationId),
                    )
                )
                is CloseConversationTabRequest -> ConversationTabLayoutResponse(
                    filterConversationTabLayout(
                        user,
                        conversationTabLayoutService.close(user.id, request.conversationId),
                    )
                )
                is FindWorkspaceRequest -> WorkspaceResponse(workspaceCatalogService.findById(request.workspaceId))
                is FindWorkspacesByProjectRequest -> WorkspacesResponse(
                    workspaceCatalogService.findByProject(request.projectId)
                )
                is FindWorkspaceMountsRequest -> WorkspaceMountsResponse(
                    workspaceCatalogService.findMounts(request.workspaceId)
                )
                ListWorkersRequest -> {
                    val accessibleWorkerIds = workerAccessService.listAccessible(user)
                        .mapTo(mutableSetOf()) { it.id }
                    WorkersResponse(
                        workerCatalogService.listWorkers()
                            .filter { it.workerId in accessibleWorkerIds }
                    )
                }
                is CreateFilesystemWorkspaceRequest -> WorkspaceResponse(
                    workspaceManagementService.create(request.projectId, request.name)
                )
                is UpdateWorkspaceRequest -> WorkspaceResponse(
                    workspaceManagementService.update(request.workspaceId, request.name)
                )
                is DeleteWorkspaceRequest -> {
                    workspaceManagementService.delete(request.workspaceId)
                    SavedResponse
                }
                is DeleteWorkspaceMountRequest -> {
                    workspaceManagementService.deleteMount(request.mountId)
                    SavedResponse
                }
                is DeleteConversationRequest -> {
                    conversationDomainService.delete(request.conversationId)
                    SavedResponse
                }
                is UpdateConversationDisplayNameRequest -> ConversationResponse(
                    conversationDomainService.updateDisplayName(request.conversationId, request.displayName)
                )
                is UpdateConversationAgentRequest -> ConversationResponse(
                    conversationDomainService.updateAgentDefinition(
                        request.conversationId,
                        request.agentDefinitionId,
                    )
                )
                is ForkConversationRequest -> ConversationResponse(conversationDomainService.fork(request.conversationId))
                is AddMessageRequest -> ConversationResponse(
                    conversationDomainService.addMessage(request.conversationId, request.message)
                )
                is LoadCurrentMessagesRequest -> MessagesResponse(conversationDomainService.loadCurrentMessages(request.conversationId))
                is GetTokenStatsRequest -> TokenStatsResponse(conversationTokenStatsService.getTokenStats(request.conversationId))
                is EditMessageRequest -> ConversationResponse(
                    conversationDomainService.editMessage(request.conversationId, request.messageId, request.newContent)
                )
                is DeleteMessagesRequest -> ConversationResponse(
                    conversationDomainService.deleteMessages(request.conversationId, request.messageIds)
                )
                is SquashMessagesRequest -> ConversationResponse(
                    conversationDomainService.squashMessages(
                        request.conversationId,
                        request.messageIds,
                        request.squashedContent
                    )
                )

                is SquashMessagesWithAiRequest -> TextResponse(
                    messageSquashGenerationService.squashWithAI(
                        request.conversationId,
                        request.messageIds,
                        request.squashType,
                        request.runtimeSelection,
                    )
                )

                ListQuickTextActionsRequest -> QuickTextActionsResponse(
                    quickTextActionService.listActions()
                )

                is RunQuickTextActionRequest -> QuickTextActionResultResponse(
                    quickTextActionService.runAction(request.actionId, request.text)
                )

                is SearchConversationsRequest -> {
                    val readableProjectIds = remoteAuthorization.readableProjectIds(user)
                    ConversationProjectItemsResponse(
                        conversationNameSearchService.searchConversations(request.query)
                            .filter { (_, project) -> project.id in readableProjectIds }
                            .map { (conversation, project) ->
                                ConversationProjectItem(conversation, project)
                            }
                    )
                }

                is MemoryActionRequest -> {
                    runMemoryAction(request.conversationId, request.action)
                    MemoryActionAcceptedResponse()
                }
                is SubmitMessageRequest -> OperationResultResponse(
                    conversationRuntimeIngressService.submitMessage(
                        user.id,
                        request.conversationId,
                        request.userMessage,
                        request.agentDefinitionId,
                    )
                )
                is EnqueueMessageRequest -> OperationResultResponse(
                    conversationRuntimeIngressService.enqueueMessage(
                        user.id,
                        request.conversationId,
                        request.userMessage,
                        request.agentDefinitionId,
                        request.placement
                    )
                )
                is CancelQueuedMessageRequest -> OperationResultResponse(
                    conversationRuntimeService.cancelQueuedMessage(request.conversationId, request.messageId)
                )
                is ControlConversationRuntimeRequest -> OperationResultResponse(
                    conversationRuntimeService.controlExecution(request.conversationId, request.action)
                )
                is CancelCommandTaskRequest -> OperationResultResponse(
                    conversationRuntimeService.cancelCommandTask(request.conversationId, request.taskId)
                )
                is CancelCommandMonitorRequest -> OperationResultResponse(
                    conversationRuntimeService.cancelCommandMonitor(request.conversationId, request.monitorId)
                )

                is GetMemoryActionItemsRequest -> loadMemoryActionItems(request)

                is TranscribeAudioRequest -> transcribeAudio(user, request.recording)
                GetSpeechCaptureAvailabilityRequest ->
                    speechCaptureApplicationService.availability(user)
                is StartSpeechCaptureRequest ->
                    speechCaptureApplicationService.start(speechCaptureOwner, user, request)
                is StopSpeechCaptureRequest ->
                    speechCaptureApplicationService.stop(speechCaptureOwner, request.sessionId)
                is CancelSpeechCaptureRequest -> OperationResultResponse(
                    speechCaptureApplicationService.cancel(speechCaptureOwner, request.sessionId)
                )
                is SynthesizeSpeechRequest -> synthesizeSpeech(request)
                is StartLiveInterpreterRequest ->
                    liveInterpreterApplicationService.start(liveInterpreterOwner, request) { payload ->
                        sender.send(uuid7(), payload, encoding)
                    }
                GetLiveVoiceProviderVadAvailabilityRequest ->
                    liveVoiceProviderVadApplicationService.availability()
                is StartLiveVoiceProviderVadRequest ->
                    liveVoiceProviderVadApplicationService.start(liveVoiceProviderVadOwner, user, request) { payload ->
                        sender.send(uuid7(), payload, encoding)
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Remote request failed: ${request::class.simpleName}: ${error.message}" }
            ErrorResponse(error.message ?: "Unknown server error", error::class.simpleName)
        }

        sender.send(requestId, response, encoding)
    }

    private suspend fun observeConversation(
        sender: RemoteSessionSender,
        command: ObserveConversationCommand,
        encoding: RemoteProtocolEncoding,
        authenticatedSession: AuthenticatedRemoteSession,
    ) {
        try {
            sessionAccessGuard.requireConversationRead(authenticatedSession, command.conversationId)
            var liveEventsStarted = false
            var lastActivitySignature: RuntimeActivitySignature? = null
            conversationRuntimeService.observeConversation(command.conversationId, command.afterEventSequence)
                .collect { event ->
                    val currentUser = sessionAccessGuard.requireConversationRead(
                        authenticatedSession,
                        command.conversationId,
                    )
                    when (event) {
                        is ConversationRuntimeEvent.SnapshotUpdated -> {
                            val activitySignature = event.snapshot.presentationActivitySignature()
                            val activityChanged = liveEventsStarted && activitySignature != lastActivitySignature
                            lastActivitySignature = activitySignature
                            sender.send(
                                command.subscriptionId,
                                ConversationRuntimeSnapshotEvent(
                                    subscriptionId = command.subscriptionId,
                                    conversationId = event.conversationId,
                                    snapshot = event.snapshot,
                                    cursorSequence = event.cursorSequence,
                                ),
                                encoding,
                            )
                            if (activityChanged && event.snapshot.hasPresentableActivity()) {
                                clientPresentationRegistry.presentActivity(
                                    userId = currentUser.id,
                                    conversationId = event.conversationId,
                                    eventKey = "${event.conversationId.value}:${event.snapshot.revision}",
                                )
                            }
                            liveEventsStarted = true
                        }
                        is ConversationRuntimeEvent.MessageEmitted -> {
                            sender.send(
                                command.subscriptionId,
                                MessageUpsertedEvent(
                                    subscriptionId = command.subscriptionId,
                                    conversationId = event.conversationId,
                                    taskId = event.taskId,
                                    message = event.message,
                                    cursorSequence = event.cursorSequence,
                                ),
                                encoding,
                            )
                            if (liveEventsStarted) {
                                if (event.message.containsUserVisibleError()) {
                                    clientPresentationRegistry.presentError(
                                        userId = currentUser.id,
                                        conversationId = event.conversationId,
                                        eventKey = "message:${event.message.id.value}",
                                    )
                                } else {
                                    clientPresentationRegistry.present(currentUser.id, event.message)
                                }
                            }
                        }
                        is ConversationRuntimeEvent.ExecutionCompleted -> sender.send(
                            command.subscriptionId,
                            ConversationExecutionCompletedEvent(command.subscriptionId, event.conversationId, event.cursorSequence),
                            encoding,
                        )
                        is ConversationRuntimeEvent.ExecutionFailed -> {
                            sender.send(
                                command.subscriptionId,
                                ConversationExecutionFailedEvent(
                                    subscriptionId = command.subscriptionId,
                                    conversationId = event.conversationId,
                                    message = event.message,
                                    type = event.failureType,
                                    cursorSequence = event.cursorSequence,
                                ),
                                encoding,
                            )
                            if (liveEventsStarted) {
                                clientPresentationRegistry.presentError(
                                    userId = currentUser.id,
                                    conversationId = event.conversationId,
                                    eventKey = event.cursorSequence
                                        ?.let { "execution:$it" }
                                        ?: timeBucketedPresentationKey(
                                            "execution",
                                            event.failureType.toString(),
                                            event.message,
                                        ),
                                )
                            }
                        }
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Remote conversation observation failed: conversation=${command.conversationId.value} error=${error.message}" }
            sender.send(
                command.subscriptionId,
                ConversationExecutionFailedEvent(
                    subscriptionId = command.subscriptionId,
                    conversationId = command.conversationId,
                    message = error.message ?: "Unknown conversation observation error",
                    type = error::class.simpleName,
                    cursorSequence = null,
                ),
                encoding,
            )
            clientPresentationRegistry.presentError(
                userId = authenticatedSession.principal.user.id,
                conversationId = command.conversationId,
                eventKey = timeBucketedPresentationKey(
                    "observation",
                    error::class.simpleName.orEmpty(),
                    error.message.orEmpty(),
                ),
            )
        }
    }

    private suspend fun observeConversationTabLayout(
        sender: RemoteSessionSender,
        command: ObserveConversationTabLayoutCommand,
        encoding: RemoteProtocolEncoding,
        authenticatedSession: AuthenticatedRemoteSession,
    ) {
        conversationTabLayoutService.observe(authenticatedSession.principal.user.id).collect { layout ->
            val user = sessionAccessGuard.requireUser(authenticatedSession)
            sender.send(
                command.subscriptionId,
                ConversationTabLayoutSnapshotEvent(
                    command.subscriptionId,
                    filterConversationTabLayout(user, layout),
                ),
                encoding,
            )
        }
    }

    private suspend fun filterConversationTabLayout(
        user: User,
        layout: ConversationTabLayout,
    ): ConversationTabLayout {
        val readableProjectIds = remoteAuthorization.readableProjectIds(user)
        return layout.copy(
            conversationIds = layout.conversationIds.filter { conversationId ->
                conversationDomainService.findById(conversationId)
                    ?.projectId in readableProjectIds
            }
        )
    }

    private suspend fun handleSynthesizeSpeechStream(
        sender: RemoteSessionSender,
        requestId: String,
        command: SynthesizeSpeechStreamCommand,
        encoding: RemoteProtocolEncoding,
    ) {
        try {
            log.info {
                "Remote speech synthesis stream requested: stream=${command.streamId} " +
                    "textChars=${command.text.length} tone=${command.tone}"
            }
            sender.send(
                requestId,
                SpeechSynthesisStartedEvent(
                    streamId = command.streamId,
                    mediaType = "audio/pcm",
                    fileExtension = "pcm",
                    sampleRate = OPENAI_TTS_PCM_SAMPLE_RATE,
                    channels = OPENAI_TTS_PCM_CHANNELS,
                    bitsPerSample = OPENAI_TTS_PCM_BITS_PER_SAMPLE,
                ),
                encoding,
            )

            var sequenceNumber = 0
            ttsService.streamSpeechPcm(command.text, command.tone).collect { chunk ->
                sender.send(
                    requestId,
                    SpeechSynthesisChunkEvent(command.streamId, sequenceNumber++, chunk.data),
                    encoding,
                )
            }

            log.info {
                "Remote speech synthesis stream completed: stream=${command.streamId} chunks=$sequenceNumber"
            }
            sender.send(requestId, SpeechSynthesisCompletedEvent(command.streamId), encoding)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Remote speech synthesis stream failed: stream=${command.streamId} error=${error.message}" }
            sender.send(requestId, SpeechSynthesisFailedEvent(command.streamId, error.message ?: "Unknown TTS error"), encoding)
        }
    }

    private suspend fun transcribeAudio(
        user: User,
        recording: RemoteAudioRecording,
    ): AudioTranscriptionResponse {
        speechCaptureApplicationService.requireClientAudioRoute(user)
        require(recording.chunks.isNotEmpty()) { "Audio recording has no chunks" }

        val audioBytes = ByteArrayOutputStream().use { output ->
            recording.chunks
                .sortedBy { it.sequenceNumber }
                .forEach { chunk ->
                    output.write(chunk.data)
                }
            output.toByteArray()
        }

        log.info {
            "Remote audio transcription requested: session=${recording.sessionId} " +
                "chunks=${recording.chunks.size} bytes=${audioBytes.size} format=${recording.format}"
        }

        val text = sttService.transcribe(
            audioData = audioBytes,
            format = recording.format,
        ).trim()

        log.info {
            "Remote audio transcription completed: session=${recording.sessionId} textChars=${text.length}"
        }

        return AudioTranscriptionResponse(text)
    }

    private suspend fun synthesizeSpeech(request: SynthesizeSpeechRequest): SpeechSynthesisResponse {
        log.info {
            "Remote speech synthesis requested: textChars=${request.text.length} tone=${request.tone}"
        }
        val audioFile = ttsService.generateSpeech(request.text, request.tone)
            ?: return SpeechSynthesisResponse(ByteArray(0), "audio/wav", "wav")
        return try {
            val audioData = audioFile.readBytes()
            log.info {
                "Remote speech synthesis completed: textChars=${request.text.length} bytes=${audioData.size}"
            }
            SpeechSynthesisResponse(audioData, "audio/wav", "wav")
        } finally {
            audioFile.delete()
        }
    }

    private suspend fun runMemoryAction(
        conversationId: com.gromozeka.domain.model.Conversation.Id,
        action: MemoryAction,
    ) {
        when (action) {
            MemoryAction.REMEMBER_THREAD -> conversationRuntimeService.rememberCurrentThread(conversationId)
            MemoryAction.CONSOLIDATE -> conversationRuntimeService.consolidateCurrentMemory(conversationId)
            MemoryAction.REPAIR -> conversationRuntimeService.repairCurrentMemory(conversationId)
            MemoryAction.MAINTAIN_ENTITIES -> conversationRuntimeService.maintainMemoryEntities(conversationId)
            MemoryAction.APPLY_RETENTION -> conversationRuntimeService.applyCurrentMemoryRetention(conversationId)
        }
    }

    private suspend fun loadMemoryActionItems(request: GetMemoryActionItemsRequest): MemoryActionItemsResponse {
        val conversation = conversationDomainService.findById(request.conversationId)
            ?: error("Conversation not found: ${request.conversationId.value}")
        val namespace = MemoryNamespace.forProject(conversation.projectId)
        val snapshot = memoryStore.loadNamespaceSnapshot(namespace)
        val nonArchivedActionItems = snapshot.actionItems.filter { it.archivedAt == null }
        val visibleActionItems = nonArchivedActionItems
            .filter { request.includeClosed || it.status !in closedMemoryActionItemStatuses }
            .sortedWith(
                compareBy<MemoryActionItem> { it.status.memoryActionItemStatusRank() }
                    .thenBy { it.priority.memoryActionItemPriorityRank() }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.title.lowercase() }
            )

        log.info {
            "Remote memory actionItems loaded: conversation=${request.conversationId.value} namespace=${namespace.value} " +
                "includeClosed=${request.includeClosed} visible=${visibleActionItems.size} total=${nonArchivedActionItems.size}"
        }

        return MemoryActionItemsResponse(
            revision = visibleActionItems.memoryActionItemRevision(),
            counts = MemoryActionItemCounts(
                open = nonArchivedActionItems.count { it.status == MemoryActionItem.Status.OPEN },
                inProgress = nonArchivedActionItems.count { it.status == MemoryActionItem.Status.IN_PROGRESS },
                blocked = nonArchivedActionItems.count { it.status == MemoryActionItem.Status.BLOCKED },
                done = nonArchivedActionItems.count { it.status == MemoryActionItem.Status.DONE },
                cancelled = nonArchivedActionItems.count { it.status == MemoryActionItem.Status.CANCELLED },
            ),
            actionItems = visibleActionItems,
        )
    }

    private fun List<MemoryActionItem>.memoryActionItemRevision(): String {
        val json = memoryActionItemRevisionJson.encodeToString(ListSerializer(MemoryActionItem.serializer()), this)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val AUTHENTICATION_RECHECK_INTERVAL_MILLIS = 30_000L
        const val MAX_PERSONAL_ACCESS_TOKEN_LIFETIME_DAYS = 3_650
        val closedMemoryActionItemStatuses = setOf(MemoryActionItem.Status.DONE, MemoryActionItem.Status.CANCELLED)
        const val OPENAI_TTS_PCM_SAMPLE_RATE = 24_000
        const val OPENAI_TTS_PCM_CHANNELS = 1
        const val OPENAI_TTS_PCM_BITS_PER_SAMPLE = 16
    }
}

private fun McpServer.toRemoteView(): RemoteMcpServerView {
    val environmentVariables = when (val transport = config.transport) {
        is McpServerTransport.Stdio -> transport.environment.keys
        is McpServerTransport.BundledStdio -> transport.environment.keys
        is McpServerTransport.StreamableHttp -> emptySet()
    }
    val httpHeaders = (config.transport as? McpServerTransport.StreamableHttp)
        ?.headers
        ?.keys
        .orEmpty()
    val redactedTransport = when (val transport = config.transport) {
        is McpServerTransport.Stdio -> transport.copy(environment = emptyMap())
        is McpServerTransport.BundledStdio -> transport.copy(environment = emptyMap())
        is McpServerTransport.StreamableHttp -> transport.copy(headers = emptyMap())
    }
    return RemoteMcpServerView(
        server = copy(config = config.copy(transport = redactedTransport)),
        configuredEnvironmentVariables = environmentVariables,
        configuredHttpHeaders = httpHeaders,
    )
}

private data class RuntimeActivitySignature(
    val activeTaskId: String?,
    val toolExecutions: List<String>,
    val memoryOperations: List<String>,
    val commandTasks: List<String>,
    val commandMonitors: List<String>,
    val lastTraceSequence: Long?,
)

private fun ConversationRuntimeSnapshot.presentationActivitySignature(): RuntimeActivitySignature =
    RuntimeActivitySignature(
        activeTaskId = activeTask?.id?.value,
        toolExecutions = toolExecutions.map {
            "${it.toolCallId.value}:${it.status}:${it.isError}"
        },
        memoryOperations = memoryOperations.map {
            val progress = it.progress
            "${it.runId.value}:${it.status}:${progress?.completedUnits}:${progress?.failedUnits}:${progress?.currentUnitLabel}"
        },
        commandTasks = commandTasks.map {
            "${it.id.value}:${it.status}:${it.outputBytes}:${it.exitCode}"
        },
        commandMonitors = commandMonitors.map {
            "${it.id.value}:${it.status}:${it.outputBytes}:${it.eventCount}:${it.exitCode}"
        },
        lastTraceSequence = trace.lastOrNull()?.sequence,
    )

private fun ConversationRuntimeSnapshot.hasPresentableActivity(): Boolean =
    state != null ||
        activeTask != null ||
        toolExecutions.any { it.status == ConversationRuntimeToolExecution.Status.RUNNING } ||
        memoryOperations.any { it.status == MemoryRun.Status.QUEUED || it.status == MemoryRun.Status.RUNNING } ||
        commandTasks.any { it.status == CommandTask.Status.WORKING } ||
        commandMonitors.any { it.status == CommandMonitor.Status.WORKING }

private fun Conversation.Message.containsUserVisibleError(): Boolean =
    error != null || content.any { item ->
        when (item) {
            is Conversation.Message.ContentItem.System ->
                item.level == Conversation.Message.ContentItem.System.SystemLevel.ERROR
            is Conversation.Message.ContentItem.ToolResult -> item.isError
            else -> false
        }
    }

private fun timeBucketedPresentationKey(vararg parts: String): String =
    parts.joinToString(":") + ":${Clock.System.now().toEpochMilliseconds() / PRESENTATION_ERROR_BUCKET_MILLIS}"

private const val PRESENTATION_ERROR_BUCKET_MILLIS = 5_000L

private fun com.gromozeka.domain.model.PersonalAccessToken.toPersonalAccessTokenView() =
    PersonalAccessTokenView(
        id = id,
        name = name,
        tokenPrefix = tokenPrefix,
        scopes = scopes,
        createdAt = createdAt,
        expiresAt = expiresAt,
        lastUsedAt = lastUsedAt,
        revokedAt = revokedAt,
    )

internal suspend fun <T> String.usePasswordChars(block: suspend (CharArray) -> T): T {
    val password = toCharArray()
    return try {
        block(password)
    } finally {
        password.fill('\u0000')
    }
}

private class RemoteSessionSender(
    private val session: DefaultWebSocketServerSession,
) {
    private val mutex = Mutex()

    suspend fun send(
        id: String,
        payload: ServerPayload,
        encoding: RemoteProtocolEncoding,
    ) {
        val envelope = GromozekaServerEnvelope(id, payload)
        mutex.withLock {
            when (encoding) {
                RemoteProtocolEncoding.CBOR -> session.send(Frame.Binary(true, RemoteProtocolCodec.encodeServerBinary(envelope)))
                RemoteProtocolEncoding.JSON -> session.send(RemoteProtocolCodec.encodeServerText(envelope))
            }
        }
    }
}

private fun MemoryActionItem.Status.memoryActionItemStatusRank(): Int =
    when (this) {
        MemoryActionItem.Status.BLOCKED -> 0
        MemoryActionItem.Status.IN_PROGRESS -> 1
        MemoryActionItem.Status.OPEN -> 2
        MemoryActionItem.Status.DONE -> 3
        MemoryActionItem.Status.CANCELLED -> 4
    }

private fun MemoryActionItem.Priority.memoryActionItemPriorityRank(): Int =
    when (this) {
        MemoryActionItem.Priority.HIGH -> 0
        MemoryActionItem.Priority.NORMAL -> 1
        MemoryActionItem.Priority.LOW -> 2
    }
