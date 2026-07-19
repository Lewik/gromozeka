package com.gromozeka.server

import com.gromozeka.domain.model.MemoryAction
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.infrastructure.ai.openai.TtsService
import com.gromozeka.remote.protocol.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Service
class GromozekaRemoteServer(
    private val settingsService: SettingsService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val agentDomainService: AgentDomainService,
    private val promptDomainService: PromptDomainService,
    private val conversationDomainService: ConversationDomainService,
    private val projectDomainService: ProjectDomainService,
    private val workspaceCatalogService: WorkspaceCatalogService,
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationTokenStatsService: ConversationTokenStatsService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val conversationNameSearchService: ConversationNameSearchService,
    private val sttService: SttService,
    private val ttsService: TtsService,
    private val memoryStore: MemoryStore,
    private val liveInterpreterApplicationService: LiveInterpreterApplicationService,
) {
    private val log = KLoggers.logger(this)
    private val memoryActionItemRevisionJson = Json {
        encodeDefaults = true
        classDiscriminator = "memoryType"
    }

    suspend fun handle(session: DefaultWebSocketServerSession) {
        val sender = RemoteSessionSender(session)
        val conversationSubscriptions = mutableMapOf<String, Job>()
        coroutineScope {
            try {
                for (frame in session.incoming) {
                    val decoded = when (frame) {
                        is Frame.Binary -> RemoteProtocolEncoding.CBOR to RemoteProtocolCodec.decodeClientBinary(frame.readBytes())
                        is Frame.Text -> RemoteProtocolEncoding.JSON to RemoteProtocolCodec.decodeClientText(frame.readText())
                        else -> null
                    }
                    if (decoded != null) {
                        val (encoding, envelope) = decoded
                        when (val payload = envelope.payload) {
                            is ClientRequest -> handleRequest(sender, envelope.id, payload, encoding)
                            is ObserveConversationCommand -> {
                                conversationSubscriptions[payload.subscriptionId]?.cancel()
                                conversationSubscriptions[payload.subscriptionId] = launch {
                                    observeConversation(sender, payload, encoding)
                                }
                            }
                            is StopObserveConversationCommand -> conversationSubscriptions.remove(payload.subscriptionId)?.cancel()
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
                conversationSubscriptions.values.forEach { it.cancel() }
                conversationSubscriptions.clear()
            }
        }
    }

    private suspend fun handleRequest(
        sender: RemoteSessionSender,
        requestId: String,
        request: ClientRequest,
        encoding: RemoteProtocolEncoding,
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
                FindAgentsRequest -> AgentsResponse(agentDomainService.findAll())
                is CreateAgentRequest -> AgentResponse(
                    agentDomainService.createAgent(
                        request.name,
                        request.prompts,
                        request.runtimeSelection,
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
                is CreateProjectRequest -> ProjectResponse(
                    projectDomainService.create(request.name, request.description)
                )
                is FindProjectByIdRequest -> NullableProjectResponse(projectDomainService.findById(request.projectId))
                is UpdateProjectLastUsedRequest -> NullableProjectResponse(projectDomainService.updateLastUsed(request.projectId))
                is CreateConversationRequest -> ConversationResponse(
                    conversationDomainService.create(
                        request.projectId,
                        request.workspaceId,
                        request.displayName,
                        request.agentDefinitionId,
                    )
                )

                is FindConversationRequest -> ConversationResponse(conversationDomainService.findById(request.conversationId))
                is GetProjectRequest -> ProjectResponse(conversationDomainService.getProject(request.conversationId))
                is GetWorkspaceRequest -> WorkspaceResponse(conversationDomainService.getWorkspace(request.conversationId))
                is FindRecentProjectsRequest -> ProjectsResponse(projectDomainService.findRecent(request.limit))
                is FindConversationsByProjectRequest -> ConversationsResponse(
                    conversationDomainService.findByProject(request.projectId)
                )
                is FindWorkspaceRequest -> WorkspaceResponse(workspaceCatalogService.findById(request.workspaceId))
                is FindWorkspacesByProjectRequest -> WorkspacesResponse(
                    workspaceCatalogService.findByProject(request.projectId)
                )
                is FindWorkspaceMountsRequest -> WorkspaceMountsResponse(
                    workspaceCatalogService.findMounts(request.workspaceId)
                )
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

                is SearchConversationsRequest -> ConversationProjectItemsResponse(
                    conversationNameSearchService.searchConversations(request.query)
                        .map { (conversation, project) -> ConversationProjectItem(conversation, project) }
                )

                is MemoryActionRequest -> {
                    runMemoryAction(request.conversationId, request.action)
                    MemoryActionAcceptedResponse()
                }
                is SubmitMessageRequest -> OperationResultResponse(
                    conversationRuntimeService.submitMessage(
                        request.conversationId,
                        request.userMessage,
                        request.agent,
                    )
                )
                is EnqueueMessageRequest -> OperationResultResponse(
                    conversationRuntimeService.enqueueMessage(
                        request.conversationId,
                        request.userMessage,
                        request.agent,
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
    ) {
        try {
            conversationRuntimeService.observeConversation(command.conversationId, command.afterEventSequence)
                .collect { event ->
                    when (event) {
                        is ConversationRuntimeEvent.SnapshotUpdated -> sender.send(
                            command.subscriptionId,
                            ConversationRuntimeSnapshotEvent(
                                subscriptionId = command.subscriptionId,
                                conversationId = event.conversationId,
                                snapshot = event.snapshot,
                                cursorSequence = event.cursorSequence,
                            ),
                            encoding,
                        )
                        is ConversationRuntimeEvent.MessageEmitted -> sender.send(
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
        val namespace = MemoryNamespace.Global
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

    private companion object {
        val closedMemoryActionItemStatuses = setOf(MemoryActionItem.Status.DONE, MemoryActionItem.Status.CANCELLED)
        const val OPENAI_TTS_PCM_SAMPLE_RATE = 24_000
        const val OPENAI_TTS_PCM_CHANNELS = 1
        const val OPENAI_TTS_PCM_BITS_PER_SAMPLE = 16
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
