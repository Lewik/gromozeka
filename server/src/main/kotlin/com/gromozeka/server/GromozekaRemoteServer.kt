package com.gromozeka.server

import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
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
    private val conversationTokenStatsService: ConversationTokenStatsService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val conversationNameSearchService: ConversationNameSearchService,
    private val sttService: SttService,
    private val ttsService: TtsService,
    private val memoryStore: MemoryStore,
    private val liveInterpreterApplicationService: LiveInterpreterApplicationService,
    private val clientPresentationRegistry: ClientPresentationRegistry,
    private val authenticationService: AuthenticationService,
    private val personalAccessTokenService: PersonalAccessTokenService,
    private val userAdministrationService: UserAdministrationService,
    private val userDirectoryService: UserDirectoryService,
    private val remoteAuthorization: GromozekaRemoteAuthorization,
) {
    private val log = KLoggers.logger(this)
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
        coroutineScope {
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
                    check(authenticationService.authenticate(authenticatedSession.token) != null) {
                        "Authentication session is no longer active"
                    }
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
                                userId = authenticatedSession.principal.user.id,
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
                                handleRequest(
                                    sender = sender,
                                    requestId = envelope.id,
                                    request = payload,
                                    encoding = encoding,
                                    authenticatedSession = authenticatedSession,
                                )
                            }
                            is ObserveConversationCommand -> {
                                conversationSubscriptions[payload.subscriptionId]?.cancel()
                                conversationSubscriptions[payload.subscriptionId] = launch {
                                    observeConversation(
                                        sender,
                                        payload,
                                        encoding,
                                        authenticatedSession.principal.user,
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
                                        authenticatedSession.principal.user,
                                    )
                                }
                            }
                            is StopObserveConversationTabLayoutCommand ->
                                conversationTabLayoutSubscriptions.remove(payload.subscriptionId)?.cancel()
                            is SynthesizeSpeechStreamCommand -> launch {
                                handleSynthesizeSpeechStream(sender, envelope.id, payload, encoding)
                            }
                            is LiveInterpreterAudioChunkCommand -> liveInterpreterApplicationService.append(payload)
                            is LiveInterpreterTranscriptChunkCommand -> liveInterpreterApplicationService.append(payload)
                            is StopLiveInterpreterCommand -> liveInterpreterApplicationService.stop(payload)
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
                conversationSubscriptions.values.forEach { it.cancel() }
                conversationSubscriptions.clear()
                conversationTabLayoutSubscriptions.values.forEach { it.cancel() }
                conversationTabLayoutSubscriptions.clear()
                clientPresentationRegistry.disconnect(connectionId)
            }
        }
    }

    private suspend fun handleRequest(
        sender: RemoteSessionSender,
        requestId: String,
        request: ClientRequest,
        encoding: RemoteProtocolEncoding,
        authenticatedSession: AuthenticatedRemoteSession,
    ) {
        val response = runCatching {
            val user = authenticatedSession.principal.user
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
                ListPersonalAccessTokensRequest -> PersonalAccessTokensResponse(
                    personalAccessTokenService.list(authenticatedSession.principal.user.id)
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
                        userId = authenticatedSession.principal.user.id,
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
                        userId = authenticatedSession.principal.user.id,
                        tokenId = request.tokenId,
                    )
                )
                ListUsersRequest -> UsersResponse(
                    userAdministrationService.list(user)
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
                    conversationRuntimeService.submitMessage(
                        request.conversationId,
                        request.userMessage,
                        request.agentDefinitionId,
                    )
                )
                is EnqueueMessageRequest -> OperationResultResponse(
                    conversationRuntimeService.enqueueMessage(
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

                is TranscribeAudioRequest -> transcribeAudio(request.recording)
                is SynthesizeSpeechRequest -> synthesizeSpeech(request)
                is StartLiveInterpreterRequest -> liveInterpreterApplicationService.start(request) { payload ->
                    sender.send(uuid7(), payload, encoding)
                }
            }
        }.getOrElse { error ->
            log.warn(error) { "Remote request failed: ${request::class.simpleName}: ${error.message}" }
            ErrorResponse(error.message ?: "Unknown server error", error::class.simpleName)
        }

        sender.send(requestId, response, encoding)
    }

    private suspend fun observeConversation(
        sender: RemoteSessionSender,
        command: ObserveConversationCommand,
        encoding: RemoteProtocolEncoding,
        user: User,
    ) {
        try {
            remoteAuthorization.requireConversation(
                user,
                command.conversationId,
                com.gromozeka.domain.model.ProjectPermission.READ,
            )
            var liveEventsStarted = false
            conversationRuntimeService.observeConversation(command.conversationId, command.afterEventSequence)
                .collect { event ->
                    when (event) {
                        is ConversationRuntimeEvent.SnapshotUpdated -> {
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
                                clientPresentationRegistry.present(user.id, event.message)
                            }
                        }
                        is ConversationRuntimeEvent.ExecutionCompleted -> sender.send(
                            command.subscriptionId,
                            ConversationExecutionCompletedEvent(command.subscriptionId, event.conversationId, event.cursorSequence),
                            encoding,
                        )
                        is ConversationRuntimeEvent.ExecutionFailed -> sender.send(
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
        }
    }

    private suspend fun observeConversationTabLayout(
        sender: RemoteSessionSender,
        command: ObserveConversationTabLayoutCommand,
        encoding: RemoteProtocolEncoding,
        user: User,
    ) {
        conversationTabLayoutService.observe(user.id).collect { layout ->
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
        runCatching {
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
        }.onFailure { error ->
            log.warn(error) { "Remote speech synthesis stream failed: stream=${command.streamId} error=${error.message}" }
            sender.send(requestId, SpeechSynthesisFailedEvent(command.streamId, error.message ?: "Unknown TTS error"), encoding)
        }
    }

    private suspend fun transcribeAudio(recording: RemoteAudioRecording): AudioTranscriptionResponse {
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
