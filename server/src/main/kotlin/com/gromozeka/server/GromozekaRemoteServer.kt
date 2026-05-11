package com.gromozeka.server

import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.infrastructure.ai.springai.SttService
import com.gromozeka.remote.protocol.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Base64

@Service
class GromozekaRemoteServer(
    private val settingsService: SettingsService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val agentDomainService: AgentDomainService,
    private val promptDomainService: PromptDomainService,
    private val conversationDomainService: ConversationDomainService,
    private val projectDomainService: ProjectDomainService,
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationTokenStatsService: ConversationTokenStatsService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val conversationNameSearchService: ConversationNameSearchService,
    private val sttService: SttService,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "payloadType"
    }

    suspend fun handle(session: DefaultWebSocketServerSession) {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue

            val envelope = json.decodeFromString(GromozekaClientEnvelope.serializer(), frame.readText())
            when (val payload = envelope.payload) {
                is ClientRequest -> handleRequest(session, envelope.id, payload)
                is SendMessageCommand -> handleSendMessage(session, envelope.id, payload)
            }
        }
    }

    private suspend fun handleRequest(
        session: DefaultWebSocketServerSession,
        requestId: String,
        request: ClientRequest,
    ) {
        val response = runCatching {
            when (request) {
                GetSettingsRequest -> SettingsResponse(settingsService.settings)
                is SaveSettingsRequest -> {
                    settingsService.saveSettings(request.settings)
                    SavedResponse
                }

                GetDefaultAgentRequest -> DefaultAgentResponse(defaultAgentProvider.getDefault())
                is FindAgentRequest -> AgentResponse(agentDomainService.findById(request.agentId))
                is FindAgentsRequest -> AgentsResponse(agentDomainService.findAll(request.projectPath))
                is CreateAgentRequest -> AgentResponse(
                    agentDomainService.createAgent(
                        request.name,
                        request.prompts,
                        request.aiProvider,
                        request.modelName,
                        request.tools,
                        request.description,
                        request.type
                    )
                )
                is UpdateAgentRequest -> AgentResponse(
                    agentDomainService.update(request.agentId, request.prompts, request.description)
                )
                is DeleteAgentRequest -> {
                    agentDomainService.delete(request.agentId)
                    SavedResponse
                }
                CountAgentsRequest -> CountResponse(agentDomainService.count())
                is AssembleAgentSystemPromptRequest -> TextListResponse(
                    agentDomainService.assembleSystemPrompt(request.agent, request.project)
                )
                is AssemblePromptSystemPromptRequest -> TextListResponse(
                    promptDomainService.assembleSystemPrompt(request.promptIds, request.project)
                )
                is FindPromptRequest -> PromptResponse(promptDomainService.findById(request.promptId))
                FindPromptsRequest -> PromptsResponse(promptDomainService.findAll())
                RefreshPromptsRequest -> {
                    promptDomainService.refresh()
                    SavedResponse
                }
                is CreateEnvironmentPromptRequest -> PromptResponse(
                    promptDomainService.createEnvironmentPrompt(request.name, request.content)
                )
                is CopyBuiltinPromptToUserRequest -> resultResponse(
                    promptDomainService.copyBuiltinPromptToUser(request.promptId)
                )
                ResetAllBuiltinPromptsRequest -> countResultResponse(promptDomainService.resetAllBuiltinPrompts())
                ImportAllClaudeMdRequest -> countResultResponse(promptDomainService.importAllClaudeMd())
                is GetOrCreateProjectRequest -> ProjectResponse(projectDomainService.getOrCreate(request.path))
                is FindProjectByIdRequest -> NullableProjectResponse(projectDomainService.findById(request.projectId))
                is FindProjectByPathRequest -> NullableProjectResponse(projectDomainService.findByPath(request.path))
                is UpdateProjectLastUsedRequest -> NullableProjectResponse(projectDomainService.updateLastUsed(request.projectId))
                is CreateConversationRequest -> ConversationResponse(
                    conversationDomainService.create(request.projectPath, request.displayName, request.agentDefinitionId)
                )

                is FindConversationRequest -> ConversationResponse(conversationDomainService.findById(request.conversationId))
                is GetProjectRequest -> ProjectResponse(conversationDomainService.getProject(request.conversationId))
                is FindRecentProjectsRequest -> ProjectsResponse(projectDomainService.findRecent(request.limit))
                is FindConversationsByProjectRequest -> ConversationsResponse(conversationDomainService.findByProject(request.projectPath))
                is DeleteConversationRequest -> {
                    conversationDomainService.delete(request.conversationId)
                    SavedResponse
                }
                is UpdateConversationDisplayNameRequest -> ConversationResponse(
                    conversationDomainService.updateDisplayName(request.conversationId, request.displayName)
                )
                is ForkConversationRequest -> ConversationResponse(conversationDomainService.fork(request.conversationId))
                is AddMessageRequest -> ConversationResponse(
                    conversationDomainService.addMessage(request.conversationId, request.message)
                )
                is LoadCurrentMessagesRequest -> MessagesResponse(conversationDomainService.loadCurrentMessages(request.conversationId))
                is GetTokenStatsRequest -> TokenStatsResponse(conversationTokenStatsService.getTokenStats(request.conversationId))
                is UpdateStrideEnabledRequest -> ConversationResponse(
                    conversationDomainService.updateStrideEnabled(request.conversationId, request.enabled)
                )
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
                        request.aiProvider,
                        request.modelName,
                        request.projectPath
                    )
                )

                is SearchConversationsRequest -> ConversationProjectItemsResponse(
                    conversationNameSearchService.searchConversations(request.query)
                        .map { (conversation, project) -> ConversationProjectItem(conversation, project) }
                )

                is MemoryActionRequest -> {
                    runMemoryAction(request.conversationId, request.action)
                    MemoryActionCompletedResponse
                }

                is TranscribeAudioRequest -> transcribeAudio(request.recording)
            }
        }.getOrElse { error ->
            log.warn(error) { "Remote request failed: ${request::class.simpleName}: ${error.message}" }
            ErrorResponse(error.message ?: "Unknown server error", error::class.simpleName)
        }

        send(session, requestId, response)
    }

    private suspend fun handleSendMessage(
        session: DefaultWebSocketServerSession,
        requestId: String,
        command: SendMessageCommand,
    ) {
        runCatching {
            conversationRuntimeService
                .sendMessage(command.conversationId, command.userMessage, command.agent)
                .collect { message ->
                    send(session, requestId, MessageUpsertedEvent(command.streamId, message))
                }
            send(session, requestId, SendCompletedEvent(command.streamId))
        }.onFailure { error ->
            log.warn(error) { "Remote send failed: conversation=${command.conversationId.value} error=${error.message}" }
            send(session, requestId, SendFailedEvent(command.streamId, error.message ?: "Unknown send error"))
        }
    }

    private suspend fun send(
        session: DefaultWebSocketServerSession,
        id: String,
        payload: ServerPayload,
    ) {
        val text = json.encodeToString(GromozekaServerEnvelope.serializer(), GromozekaServerEnvelope(id, payload))
        session.send(text)
    }

    private suspend fun transcribeAudio(recording: RemoteAudioRecording): AudioTranscriptionResponse {
        require(recording.chunks.isNotEmpty()) { "Audio recording has no chunks" }

        val audioBytes = ByteArrayOutputStream().use { output ->
            recording.chunks
                .sortedBy { it.sequenceNumber }
                .forEach { chunk ->
                    output.write(Base64.getDecoder().decode(chunk.dataBase64))
                }
            output.toByteArray()
        }

        log.info {
            "Remote audio transcription requested: session=${recording.sessionId} " +
                "chunks=${recording.chunks.size} bytes=${audioBytes.size} mediaType=${recording.mediaType}"
        }

        val text = sttService.transcribe(
            audioData = audioBytes,
            fileExtension = recording.fileExtension,
            mediaType = recording.mediaType
        ).trim()

        log.info {
            "Remote audio transcription completed: session=${recording.sessionId} textChars=${text.length}"
        }

        return AudioTranscriptionResponse(text)
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

    private fun resultResponse(result: Result<Unit>): OperationResultResponse =
        result.fold(
            onSuccess = { OperationResultResponse(success = true) },
            onFailure = { OperationResultResponse(success = false, error = it.message ?: it::class.simpleName) }
        )

    private fun countResultResponse(result: Result<Int>): OperationResultResponse =
        result.fold(
            onSuccess = { OperationResultResponse(success = true, count = it) },
            onFailure = { OperationResultResponse(success = false, error = it.message ?: it::class.simpleName) }
        )
}
