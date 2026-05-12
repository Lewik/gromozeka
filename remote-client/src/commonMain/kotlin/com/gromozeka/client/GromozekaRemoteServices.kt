package com.gromozeka.client

import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.SettingsService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

class GromozekaRemoteServices(
    url: String = GromozekaRemoteDefaults.REMOTE_URL,
    httpClient: HttpClient? = null,
    scope: CoroutineScope,
    clientHomeDirectory: String,
    clientSettingsStore: RemoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
) {
    private val initialClientSettings = clientSettingsStore.load() ?: RemoteClientSettings()
    private val client = if (httpClient == null) {
        GromozekaWsClient(url = url, encoding = initialClientSettings.protocolEncoding, scope = scope)
    } else {
        GromozekaWsClient(
            url = url,
            encoding = initialClientSettings.protocolEncoding,
            httpClient = httpClient,
            scope = scope
        )
    }
    val clientSettingsService: RemoteClientSettingsService =
        RemoteClientSettingsService(client, clientSettingsStore, initialClientSettings)
    private val remoteSettingsService = RemoteSettingsService(client, scope, clientHomeDirectory)
    private val remoteAgentService = RemoteAgentService(client)

    val settingsService: SettingsService = remoteSettingsService
    val defaultAgentProvider: DefaultAgentProvider = remoteAgentService
    val agentService: AgentDomainService = remoteAgentService
    val promptService: PromptDomainService = RemotePromptService(client)
    val projectService: ProjectDomainService = RemoteProjectService(client)
    val conversationService: ConversationDomainService = RemoteConversationService(client)
    val conversationRuntimeService: ConversationRuntimeService = RemoteConversationRuntimeService(client)
    val conversationNameSearchService: ConversationNameSearchService = RemoteConversationNameSearchService(client)
    val conversationTokenStatsService: ConversationTokenStatsService = RemoteConversationTokenStatsService(client)
    val messageSquashGenerationService: MessageSquashGenerationService = RemoteMessageSquashGenerationService(client)
    val audioTranscriptionService: RemoteAudioTranscriptionService = RemoteAudioTranscriptionService(client)
    val memoryTaskService: RemoteMemoryTaskService = RemoteMemoryTaskService(client)

    suspend fun initialize() {
        remoteSettingsService.refreshFromServer()
    }

    fun close() {
        client.close()
    }
}
