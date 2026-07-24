package com.gromozeka.client

import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
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
    private val initialClientSettings = (clientSettingsStore.load() ?: RemoteClientSettings())
        .let { settings ->
            if (settings.clientInstanceId != null) {
                settings
            } else {
                settings.copy(clientInstanceId = ClientInstanceId(uuid7()))
                    .also(clientSettingsStore::save)
            }
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
    private val remoteAgentService = RemoteAgentService(client)

    val settingsService: SettingsService = remoteSettingsService
    val defaultAgentProvider: DefaultAgentProvider = remoteAgentService
    val agentService: AgentDomainService = remoteAgentService
    val agentSkillService: AgentSkillDomainService = RemoteAgentSkillService(client)
    val promptService: PromptDomainService = RemotePromptService(client)
    val projectService: ProjectDomainService = RemoteProjectService(client)
    private val remoteWorkspaceService = RemoteWorkspaceCatalogService(client)
    val workspaceCatalogService: WorkspaceCatalogService = remoteWorkspaceService
    val workspaceManagementService: WorkspaceManagementService = remoteWorkspaceService
    val conversationService: ConversationDomainService = RemoteConversationService(client)
    val conversationTabLayoutService: ConversationTabLayoutService = RemoteConversationTabLayoutService(client)
    val conversationRuntimeService: ConversationRuntimeService = RemoteConversationRuntimeService(client)
    val conversationNameSearchService: ConversationNameSearchService = RemoteConversationNameSearchService(client)
    val conversationTokenStatsService: ConversationTokenStatsService = RemoteConversationTokenStatsService(client)
    val messageSquashGenerationService: MessageSquashGenerationService = RemoteMessageSquashGenerationService(client)
    val audioTranscriptionService: RemoteAudioTranscriptionService = RemoteAudioTranscriptionService(client)
    val speechSynthesisService: RemoteSpeechSynthesisService = RemoteSpeechSynthesisService(client)
    val liveInterpreterService: RemoteLiveInterpreterService = RemoteLiveInterpreterService(client)
    val memoryActionItemService: RemoteMemoryActionItemService = RemoteMemoryActionItemService(client)
    val clientPresentationService: RemoteClientPresentationService = RemoteClientPresentationService(client)

    suspend fun initialize() {
        remoteSettingsService.refreshFromServer()
    }

    fun close() {
        client.close()
    }
}
