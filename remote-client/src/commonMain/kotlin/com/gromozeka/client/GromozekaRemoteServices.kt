package com.gromozeka.client

import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.AiSubscriptionQuotaService
import com.gromozeka.domain.service.CurrentUserAiCredentialService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationSearchService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.QuickTextActionService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.domain.service.WorkerCatalogService
import io.ktor.client.HttpClient
import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.RemoteClientPlatform
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class GromozekaRemoteServices(
    url: String = GromozekaRemoteDefaults.REMOTE_URL,
    httpClient: HttpClient? = null,
    scope: CoroutineScope,
    clientHomeDirectory: String,
    clientPlatform: RemoteClientPlatform,
    clientSettingsStore: RemoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
) {
    private val initialClientSettings = (clientSettingsStore.load() ?: RemoteClientSettings()).let { loaded ->
        val persisted = loaded.copy(
            clientInstanceId = loaded.clientInstanceId ?: ClientInstanceId(uuid7()),
        )
        if (persisted != loaded) {
            clientSettingsStore.save(persisted)
        }
        persisted.copy(remoteUrl = url)
    }
    private val clientInstanceId = requireNotNull(initialClientSettings.clientInstanceId)
    private val client = if (httpClient == null) {
        GromozekaWsClient(
            url = url,
            encoding = initialClientSettings.protocolEncoding,
            scope = scope,
            clientInstanceId = clientInstanceId,
            clientPlatform = clientPlatform,
        )
    } else {
        GromozekaWsClient(
            url = url,
            encoding = initialClientSettings.protocolEncoding,
            httpClient = httpClient,
            scope = scope,
            clientInstanceId = clientInstanceId,
            clientPlatform = clientPlatform,
        )
    }
    val clientSettingsService: RemoteClientSettingsService =
        RemoteClientSettingsService(client, clientSettingsStore, initialClientSettings)
    val connectionState: StateFlow<RemoteConnectionState> = client.connectionState
    private val remoteSettingsService = RemoteSettingsService(client, scope, clientHomeDirectory)
    private val remoteAiConfigurationService = RemoteAiConfigurationService(client, scope)
    private val remoteRuntimeCatalogTemplateService = RemoteRuntimeCatalogTemplateService(client)
    private val remoteAgentService = RemoteAgentService(client)

    val settingsService: SettingsService = remoteSettingsService
    val aiConfigurationService: AiConfigurationService = remoteAiConfigurationService
    val aiSubscriptionQuotaService: AiSubscriptionQuotaService = RemoteAiSubscriptionQuotaService(client)
    val runtimeCatalogTemplateService: RuntimeCatalogTemplateService = remoteRuntimeCatalogTemplateService
    val defaultAgentProvider: DefaultAgentProvider = remoteAgentService
    val agentService: AgentDomainService = remoteAgentService
    val agentSkillService: AgentSkillDomainService = RemoteAgentSkillService(client)
    val promptService: PromptDomainService = RemotePromptService(client)
    val projectService: ProjectDomainService = RemoteProjectService(client)
    private val remoteWorkspaceService = RemoteWorkspaceCatalogService(client)
    val workspaceCatalogService: WorkspaceCatalogService = remoteWorkspaceService
    val workspaceManagementService: WorkspaceManagementService = remoteWorkspaceService
    val workerCatalogService: WorkerCatalogService = RemoteWorkerCatalogService(client)
    val conversationService: ConversationDomainService = RemoteConversationService(client)
    val conversationTabLayoutService: ConversationTabLayoutService = RemoteConversationTabLayoutService(client)
    val conversationRuntimeService: ConversationRuntimeService = RemoteConversationRuntimeService(client)
    val conversationSearchService: ConversationSearchService = RemoteConversationSearchService(client)
    val conversationTokenStatsService: ConversationTokenStatsService = RemoteConversationTokenStatsService(client)
    val messageSquashGenerationService: MessageSquashGenerationService = RemoteMessageSquashGenerationService(client)
    val quickTextActionService: QuickTextActionService = RemoteQuickTextActionService(client)
    val audioTranscriptionService: RemoteAudioTranscriptionService = RemoteAudioTranscriptionService(client)
    val artifactTransferService: ArtifactTransferService = RemoteArtifactTransferService(client)
    val speechSynthesisService: RemoteSpeechSynthesisService = RemoteSpeechSynthesisService(client)
    val liveInterpreterService: RemoteLiveInterpreterService = RemoteLiveInterpreterService(client)
    val liveVoiceProviderVadService: LiveVoiceProviderVadService = RemoteLiveVoiceProviderVadService(client)
    val memoryActionItemService: RemoteMemoryActionItemService = RemoteMemoryActionItemService(client)
    val clientPresentationService: RemoteClientPresentationService = RemoteClientPresentationService(client)
    val distributionService: RemoteDistributionService = RemoteDistributionService(client)
    val deviceConnectionService: RemoteDeviceConnectionClient = RemoteDeviceConnectionClient(client)
    val mcpServerService: RemoteMcpServerService = RemoteMcpServerService(client)
    val personalAccessTokenService: RemotePersonalAccessTokenService =
        RemotePersonalAccessTokenService(client)
    val aiUserCredentialService: CurrentUserAiCredentialService =
        RemoteAiUserCredentialService(client)
    val namedSecretService: com.gromozeka.domain.service.CurrentUserNamedSecretService =
        RemoteNamedSecretService(client)
    val userAdministrationService: RemoteUserAdministrationService =
        RemoteUserAdministrationService(client)
    val securityAuditService: RemoteSecurityAuditService =
        RemoteSecurityAuditService(client)
    val userDirectoryService: RemoteUserDirectoryService =
        RemoteUserDirectoryService(client)
    val projectMembershipService: RemoteProjectMembershipService =
        RemoteProjectMembershipService(client)

    suspend fun initialize() {
        remoteSettingsService.refreshFromServer()
        remoteAiConfigurationService.reload()
        remoteRuntimeCatalogTemplateService.reload()
        remoteSettingsService.startSync()
        remoteAiConfigurationService.startSync()
    }

    fun close() {
        client.close()
    }
}
