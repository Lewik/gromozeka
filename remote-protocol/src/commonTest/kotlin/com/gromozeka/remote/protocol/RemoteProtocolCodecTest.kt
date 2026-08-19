package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.QuickTextActionResult
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.BundledMcpRuntime
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpToolNamespace
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteProtocolCodecTest {
    @Test
    fun roundTripSupportsSuggestedRepliesRegeneration() {
        val request = RegenerateSuggestedRepliesRequest(
            conversationId = Conversation.Id("conversation-1"),
            sourceMessageId = Conversation.Message.Id("assistant-1"),
        )
        val decodedRequest = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(
                GromozekaClientEnvelope("suggested-replies-request", request)
            )
        ).payload as RegenerateSuggestedRepliesRequest
        assertEquals(request, decodedRequest)

        val response = SuggestedRepliesResponse(
            sourceMessageId = request.sourceMessageId,
            replies = listOf("Yes", "Show details"),
        )
        val decodedResponse = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(
                GromozekaServerEnvelope("suggested-replies-response", response)
            )
        ).payload as SuggestedRepliesResponse
        assertEquals(response, decodedResponse)
    }

    @Test
    fun roundTripSupportsActiveGenerationState() {
        val conversationId = Conversation.Id("conversation-1")
        val query = ActiveGenerationStateQuery(conversationId)
        val cursor = RemoteStateSyncCursor("server-1", streamEpoch = 4, generation = 9)
        val activeGeneration = ActiveGenerationSnapshot(
            generationId = "generation-1",
            conversationId = conversationId,
            taskId = ConversationRuntimeTask.Id("task-1"),
            provider = "OPENAI_SUBSCRIPTION",
            modelName = "gpt-5.6-sol",
            iteration = 2,
            phase = ActiveGenerationSnapshot.Phase.WAITING_FOR_MODEL,
            startedAt = Instant.parse("2026-08-17T12:00:00Z"),
            updatedAt = Instant.parse("2026-08-17T12:00:01Z"),
            inputMessageCount = 7,
            inputContentItemCount = 9,
            systemPromptCount = 3,
            availableToolCount = 11,
        )
        val encoded = GromozekaServerEnvelope(
            id = "active-generation-1",
            payload = StateSyncSnapshotResponse(
                query = query,
                cursor = cursor,
                state = ActiveGenerationStatePayload(activeGeneration),
            ),
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(encoded)
        ).payload as StateSyncSnapshotResponse

        assertEquals(query, decoded.query)
        assertEquals(activeGeneration, (decoded.state as ActiveGenerationStatePayload).snapshot)
    }

    @Test
    fun roundTripSupportsRemoteSpeechCaptureLifecycle() {
        val start = GromozekaClientEnvelope(
            id = "speech-start-1",
            payload = StartSpeechCaptureRequest("capture-1"),
        )
        val decodedStart = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(start)
        ).payload as StartSpeechCaptureRequest
        assertEquals("capture-1", decodedStart.sessionId)

        val availability = GromozekaServerEnvelope(
            id = "speech-availability-1",
            payload = SpeechCaptureAvailabilityResponse(
                available = false,
                unavailableReason = "Worker is offline",
            ),
        )
        val decodedAvailability = RemoteProtocolCodec.decodeServerText(
            RemoteProtocolCodec.encodeServerText(availability)
        ).payload as SpeechCaptureAvailabilityResponse
        assertFalse(decodedAvailability.available)
        assertEquals("Worker is offline", decodedAvailability.unavailableReason)
    }

    @Test
    fun roundTripSupportsClientActivityAndPresentationDirectives() {
        val registration = GromozekaClientEnvelope(
            id = "register-client-1",
            payload = RegisterClientSessionCommand(
                clientInstanceId = ClientInstanceId("client-1"),
                clientSessionId = ClientSessionId("session-1"),
                platform = RemoteClientPlatform.WEB_DESKTOP,
            ),
        )
        val decodedRegistration = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(registration)
        ).payload as RegisterClientSessionCommand

        assertEquals("client-1", decodedRegistration.clientInstanceId.value)
        assertEquals("session-1", decodedRegistration.clientSessionId.value)
        assertEquals(RemoteClientPlatform.WEB_DESKTOP, decodedRegistration.platform)

        val activity = GromozekaClientEnvelope(
            id = "client-activity-1",
            payload = ReportClientActivityCommand(ClientActivityKind.USER_INTERACTION),
        )
        val decodedActivity = RemoteProtocolCodec.decodeClientText(
            RemoteProtocolCodec.encodeClientText(activity)
        ).payload as ReportClientActivityCommand

        assertEquals(ClientActivityKind.USER_INTERACTION, decodedActivity.kind)

        val directive = GromozekaServerEnvelope(
            id = "present-message-1",
            payload = PresentAssistantMessageDirective(
                messageId = Conversation.Message.Id("message-1"),
                conversationId = Conversation.Id("conversation-1"),
                signal = AssistantMessageSignal.ATTENTION,
                speech = AssistantMessageSpeech(
                    text = "Hello",
                    tone = "warm",
                ),
            ),
        )
        val decodedDirective = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(directive)
        ).payload as PresentAssistantMessageDirective

        assertEquals("message-1", decodedDirective.messageId.value)
        assertEquals("conversation-1", decodedDirective.conversationId.value)
        assertEquals(AssistantMessageSignal.ATTENTION, decodedDirective.signal)
        assertEquals("Hello", decodedDirective.speech?.text)
        assertEquals("warm", decodedDirective.speech?.tone)

        val soundDirective = GromozekaServerEnvelope(
            id = "play-sound-1",
            payload = PlayClientFeedbackDirective(
                conversationId = Conversation.Id("conversation-1"),
                effect = ClientFeedbackEffect.ATTENTION,
            ),
        )
        val decodedSoundDirective = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(soundDirective)
        ).payload as PlayClientFeedbackDirective

        assertEquals("conversation-1", decodedSoundDirective.conversationId.value)
        assertEquals(ClientFeedbackEffect.ATTENTION, decodedSoundDirective.effect)
    }

    @Test
    fun roundTripSupportsAgentSkillPackages() {
        val skillFile = AgentSkillFile(
            path = "references/checklist.md",
            content = byteArrayOf(0, 1, 2, 127, -1),
        )
        val envelope = GromozekaClientEnvelope(
            id = "import-skill-1",
            payload = ImportAgentSkillRequest(
                projectId = Project.Id("project-1"),
                source = AgentSkillPackageSource(
                    directoryName = "release-check",
                    files = listOf(
                        AgentSkillFile(
                            path = "SKILL.md",
                            content = """
                                ---
                                name: release-check
                                description: Verify releases.
                                ---
                                Follow the checklist.
                            """.trimIndent().encodeToByteArray(),
                        ),
                        skillFile,
                    ),
                ),
            ),
        )

        val jsonDecoded = RemoteProtocolCodec.decodeClientText(
            RemoteProtocolCodec.encodeClientText(envelope)
        ).payload as ImportAgentSkillRequest
        val cborDecoded = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(envelope)
        ).payload as ImportAgentSkillRequest

        assertEquals("release-check", jsonDecoded.source.directoryName)
        assertContentEquals(skillFile.content, jsonDecoded.source.files.last().content)
        assertContentEquals(skillFile.content, cborDecoded.source.files.last().content)
    }

    @Test
    fun roundTripSupportsProjectWorkspaceAndSharedTabManagement() {
        val updateProject = GromozekaClientEnvelope(
            id = "update-project-1",
            payload = UpdateProjectRequest(Project.Id("project-1"), "Renamed", "Description"),
        )
        val decodedProject = RemoteProtocolCodec.decodeClientText(
            RemoteProtocolCodec.encodeClientText(updateProject)
        ).payload as UpdateProjectRequest
        assertEquals("Renamed", decodedProject.name)

        val updateWorkspace = GromozekaClientEnvelope(
            id = "update-workspace-1",
            payload = UpdateWorkspaceRequest(Workspace.Id("workspace-1"), "Mac checkout"),
        )
        val decodedWorkspace = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(updateWorkspace)
        ).payload as UpdateWorkspaceRequest
        assertEquals("Mac checkout", decodedWorkspace.name)

        val listWorkers = GromozekaClientEnvelope(
            id = "list-workers-1",
            payload = ListWorkersRequest,
        )
        assertEquals(
            ListWorkersRequest,
            RemoteProtocolCodec.decodeClientText(
                RemoteProtocolCodec.encodeClientText(listWorkers)
            ).payload,
        )

        val workerEnvelope = GromozekaServerEnvelope(
            id = "workers-1",
            payload = WorkersResponse(
                listOf(
                    WorkerCatalogEntry(
                        workerId = ConversationRuntimeWorkerId("mac-worker"),
                        status = WorkerCatalogEntry.Status.ONLINE,
                        version = "test",
                        startedAt = Instant.parse("2026-07-28T00:00:00Z"),
                        lastHeartbeatAt = Instant.parse("2026-07-28T00:00:05Z"),
                        environmentProfile = WorkerEnvironmentProfile(
                            observedAt = Instant.parse("2026-07-28T00:00:00Z"),
                            operatingSystem = WorkerOperatingSystem(
                                family = WorkerOperatingSystem.Family.MACOS,
                                name = "macOS",
                                version = "26.0",
                            ),
                            architecture = "arm64",
                            nativeShell = WorkerNativeShell(
                                WorkerNativeShell.Kind.POSIX_SH,
                                "/bin/sh",
                            ),
                            timezoneId = "Asia/Jerusalem",
                            localeTag = "en-IL",
                            logicalProcessorCount = 10,
                            totalMemoryBytes = 32_000_000_000,
                            availableExecutables = listOf("git", "sh"),
                        ),
                    )
                )
            ),
        )
        val decodedWorkers = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(workerEnvelope)
        ).payload as WorkersResponse
        assertEquals("mac-worker", decodedWorkers.workers.single().workerId.value)
        assertEquals("arm64", decodedWorkers.workers.single().environmentProfile.architecture)

        val layoutSnapshot = GromozekaServerEnvelope(
            id = "layout-event-1",
            payload = StateSyncSnapshotResponse(
                query = ConversationTabLayoutStateQuery,
                cursor = RemoteStateSyncCursor("server-1", streamEpoch = 2, generation = 7),
                state = ConversationTabLayoutStatePayload(
                    ConversationTabLayout(
                        conversationIds = listOf(Conversation.Id("conversation-1")),
                        revision = 7,
                        updatedAt = Instant.parse("2026-07-22T00:00:00Z"),
                    ),
                ),
            ),
        )
        val decodedLayout = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(layoutSnapshot)
        ).payload as StateSyncSnapshotResponse
        val decodedLayoutPayload = decodedLayout.state as ConversationTabLayoutStatePayload
        assertEquals(ConversationTabLayoutStateQuery, decodedLayout.query)
        assertEquals(7, decodedLayoutPayload.layout.revision)
        assertEquals("conversation-1", decodedLayoutPayload.layout.conversationIds.single().value)
    }

    @Test
    fun roundTripSupportsRedactedMcpServerManagement() {
        val workerId = ConversationRuntimeWorkerId("browser-worker")
        val createEnvelope = GromozekaClientEnvelope(
            id = "create-mcp-1",
            payload = CreateMcpServerRequest(
                McpServerConfig(
                    id = McpServerId("browser_worker"),
                    namespace = McpToolNamespace("playwright"),
                    displayName = "Browser",
                    workerId = workerId,
                    transport = McpServerTransport.BundledStdio(
                        runtime = BundledMcpRuntime.BROWSER_USE,
                        arguments = listOf("--extension"),
                        environment = mapOf("PLAYWRIGHT_MCP_EXTENSION_TOKEN" to "secret-token"),
                        ephemeralWorkingDirectory = true,
                    ),
                )
            ),
        )
        val decodedCreate = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(createEnvelope)
        ).payload as CreateMcpServerRequest
        val decodedCreateTransport = decodedCreate.config.transport as McpServerTransport.BundledStdio
        assertEquals("secret-token", decodedCreateTransport.environment["PLAYWRIGHT_MCP_EXTENSION_TOKEN"])
        assertTrue(decodedCreateTransport.ephemeralWorkingDirectory)

        val tool = McpToolSnapshot(
            remoteName = "browser_snapshot",
            description = "Capture a semantic page snapshot.",
            inputSchema = "{}",
        )
        val snapshot = McpServerSnapshot(
            serverName = "playwright-mcp",
            serverVersion = "0.0.78",
            tools = listOf(tool),
            supportsToolsListChanged = false,
            fingerprint = McpServerSnapshot.calculateFingerprint(
                serverName = "playwright-mcp",
                serverVersion = "0.0.78",
                instructions = null,
                supportsToolsListChanged = false,
                tools = listOf(tool),
            ),
            capturedAt = Instant.parse("2026-08-03T00:00:00Z"),
        )
        val response = GromozekaServerEnvelope(
            id = "list-mcp-1",
            payload = McpServersResponse(
                listOf(
                    RemoteMcpServerView(
                        server = McpServer(
                            config = decodedCreate.config.copy(
                                transport = decodedCreateTransport.copy(environment = emptyMap())
                            ),
                            snapshot = snapshot,
                            revision = 1,
                            refreshAvailable = false,
                            createdAt = Instant.parse("2026-08-03T00:00:00Z"),
                            updatedAt = Instant.parse("2026-08-03T00:00:00Z"),
                        ),
                        configuredEnvironmentVariables = setOf("PLAYWRIGHT_MCP_EXTENSION_TOKEN"),
                    )
                )
            ),
        )
        val encodedResponse = RemoteProtocolCodec.encodeServerText(response)
        val decodedResponse = RemoteProtocolCodec.decodeServerText(encodedResponse)
            .payload as McpServersResponse

        assertFalse("secret-token" in encodedResponse)
        assertEquals(
            setOf("PLAYWRIGHT_MCP_EXTENSION_TOKEN"),
            decodedResponse.servers.single().configuredEnvironmentVariables,
        )
        assertTrue(
            (decodedResponse.servers.single().server.config.transport as McpServerTransport.BundledStdio)
                .environment
                .isEmpty()
        )

        val probeRequest = GromozekaClientEnvelope(
            id = "test-browser-use-1",
            payload = TestBrowserUseRequest(McpServerId("browser_worker")),
        )
        val decodedProbeRequest = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(probeRequest)
        ).payload as TestBrowserUseRequest
        assertEquals("browser_worker", decodedProbeRequest.serverId.value)

        val screenshot = byteArrayOf(1, 3, 3, 7)
        val probeResponse = GromozekaServerEnvelope(
            id = "test-browser-use-1",
            payload = BrowserUseProbeResponse(
                screenshot = screenshot,
                mediaType = "image/png",
                fileName = "page.png",
            ),
        )
        val decodedProbeResponse = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(probeResponse)
        ).payload as BrowserUseProbeResponse
        assertContentEquals(screenshot, decodedProbeResponse.screenshot)
        assertEquals("image/png", decodedProbeResponse.mediaType)
        assertEquals("page.png", decodedProbeResponse.fileName)
    }

    @Test
    fun jsonEncodesByteArrayAsBase64String() {
        val envelope = audioEnvelope(byteArrayOf(0, 1, 2, 3, 4))

        val encoded = RemoteProtocolCodec.encodeClientText(envelope)
        val decoded = RemoteProtocolCodec.decodeClientText(encoded)

        assertTrue(encoded.contains("\"data\":\"AAECAwQ=\""))
        assertFalse(encoded.contains("\"data\":[0,1,2,3,4]"))
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), decoded.audioBytes())
    }

    @Test
    fun cborRoundTripKeepsByteArrayCompact() {
        val bytes = ByteArray(512) { index -> index.toByte() }
        val envelope = audioEnvelope(bytes)

        val jsonBytes = RemoteProtocolCodec.encodeClientText(envelope).encodeToByteArray()
        val cborBytes = RemoteProtocolCodec.encodeClientBinary(envelope)
        val decoded = RemoteProtocolCodec.decodeClientBinary(cborBytes)

        assertContentEquals(bytes, decoded.audioBytes())
        assertTrue(cborBytes.size < jsonBytes.size)
    }

    @Test
    fun cborRoundTripSupportsSpeechSynthesisAudio() {
        val bytes = ByteArray(256) { index -> (255 - index).toByte() }
        val envelope = GromozekaServerEnvelope(
            id = "response-speech-1",
            payload = SpeechSynthesisResponse(
                audioData = bytes,
                mediaType = "audio/mpeg",
                fileExtension = "mp3",
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope))
        val response = decoded.payload as SpeechSynthesisResponse

        assertContentEquals(bytes, response.audioData)
        assertEquals("audio/mpeg", response.mediaType)
        assertEquals("mp3", response.fileExtension)
    }

    @Test
    fun cborRoundTripSupportsLiveInterpreterPayloads() {
        val startEnvelope = GromozekaClientEnvelope(
            id = "live-start-1",
            payload = StartLiveInterpreterRequest(
                targetLanguage = "ru",
                sourceLanguageCode = "he",
                sourceLanguageHint = "Hebrew workplace conversation",
            )
        )
        val decodedStart = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(startEnvelope)
        ).payload as StartLiveInterpreterRequest

        assertEquals("he", decodedStart.sourceLanguageCode)
        assertEquals("Hebrew workplace conversation", decodedStart.sourceLanguageHint)

        val bytes = ByteArray(128) { index -> index.toByte() }
        val clientEnvelope = GromozekaClientEnvelope(
            id = "live-command-1",
            payload = LiveInterpreterAudioChunkCommand(
                sessionId = "live-session-1",
                chunk = RemoteLiveAudioChunk(
                    sequenceNumber = 7,
                    data = bytes,
                    format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
                )
            )
        )
        val decodedClient = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(clientEnvelope)
        )
        val command = decodedClient.payload as LiveInterpreterAudioChunkCommand

        assertEquals("live-session-1", command.sessionId)
        assertEquals(7, command.chunk.sequenceNumber)
        assertContentEquals(bytes, command.chunk.data)
        assertEquals(SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ, command.chunk.format)

        val transcriptEnvelope = GromozekaClientEnvelope(
            id = "live-transcript-1",
            payload = LiveInterpreterTranscriptChunkCommand(
                sessionId = "live-session-1",
                chunk = RemoteLiveTranscriptChunk(
                    sequenceNumber = 8,
                    text = "שלום",
                )
            )
        )
        val decodedTranscript = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(transcriptEnvelope)
        ).payload as LiveInterpreterTranscriptChunkCommand

        assertEquals("live-session-1", decodedTranscript.sessionId)
        assertEquals(8, decodedTranscript.chunk.sequenceNumber)
        assertEquals("שלום", decodedTranscript.chunk.text)

        val serverEnvelope = GromozekaServerEnvelope(
            id = "live-event-1",
            payload = LiveInterpreterTranslationEvent(
                sessionId = "live-session-1",
                segmentId = "segment-7",
                sequenceNumber = 3,
                text = "Привет",
                targetLanguage = "ru",
            )
        )
        val decodedServer = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(serverEnvelope)
        )
        val event = decodedServer.payload as LiveInterpreterTranslationEvent

        assertEquals("segment-7", event.segmentId)
        assertEquals("Привет", event.text)
        assertTrue(event.isFinal)
    }

    @Test
    fun cborRoundTripSupportsConversationMessageJsonFields() {
        val envelope = GromozekaServerEnvelope(
            id = "response-1",
            payload = MessagesResponse(
                messages = listOf(
                    Conversation.Message(
                        id = Conversation.Message.Id("message-1"),
                        conversationId = Conversation.Id("conversation-1"),
                        role = Conversation.Message.Role.ASSISTANT,
                        content = listOf(
                            Conversation.Message.ContentItem.ToolCall(
                                id = Conversation.Message.ContentItem.ToolCall.Id("tool-1"),
                                call = Conversation.Message.ContentItem.ToolCall.Data(
                                    name = "debug_tool",
                                    input = JsonObject(mapOf("query" to JsonPrimitive("toyota")))
                                )
                            )
                        ),
                        providerMetadata = JsonObject(mapOf("provider" to JsonPrimitive("test"))),
                        createdAt = Instant.parse("2026-05-11T00:00:00Z"),
                    )
                )
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope))
        val message = ((decoded.payload as MessagesResponse).messages.single())
        val toolCall = message.content.single() as Conversation.Message.ContentItem.ToolCall

        assertEquals(JsonPrimitive("test"), message.providerMetadata["provider"])
        assertEquals(JsonPrimitive("toyota"), (toolCall.call.input as JsonObject)["query"])
    }

    @Test
    fun cborRoundTripSupportsQueuedMessageRequests() {
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("message-queued-1"),
            conversationId = Conversation.Id("conversation-queued-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Continue after the current tool result")),
            createdAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        val agentDefinitionId = AgentDefinition.Id("agent-queued-1")

        val enqueueEnvelope = GromozekaClientEnvelope(
            id = "enqueue-1",
            payload = EnqueueMessageRequest(
                conversationId = Conversation.Id("conversation-queued-1"),
                userMessage = userMessage,
                agentDefinitionId = agentDefinitionId,
                placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
            )
        )
        val decodedEnqueue = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(enqueueEnvelope)
        ).payload as EnqueueMessageRequest

        assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, decodedEnqueue.placement)
        assertEquals("message-queued-1", decodedEnqueue.userMessage.id.value)
        assertEquals(agentDefinitionId, decodedEnqueue.agentDefinitionId)

        val cancelEnvelope = GromozekaClientEnvelope(
            id = "cancel-queued-1",
            payload = CancelQueuedMessageRequest(
                conversationId = Conversation.Id("conversation-queued-1"),
                messageId = Conversation.Message.Id("message-queued-1"),
            )
        )
        val decodedCancel = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(cancelEnvelope)
        ).payload as CancelQueuedMessageRequest

        assertEquals("message-queued-1", decodedCancel.messageId.value)
    }

    @Test
    fun cborRoundTripSupportsQuickTextActions() {
        val runEnvelope = GromozekaClientEnvelope(
            id = "quick-text-run-1",
            payload = RunQuickTextActionRequest(
                actionId = QuickTextAction.FIX_TEXT_ID,
                text = "helo",
            ),
        )
        val decodedRun = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(runEnvelope)
        ).payload as RunQuickTextActionRequest

        assertEquals(QuickTextAction.FIX_TEXT_ID, decodedRun.actionId)
        assertEquals("helo", decodedRun.text)

        val listEnvelope = GromozekaServerEnvelope(
            id = "quick-text-list-1",
            payload = QuickTextActionsResponse(QuickTextAction.defaults()),
        )
        val decodedList = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(listEnvelope)
        ).payload as QuickTextActionsResponse

        assertEquals(QuickTextAction.defaults().map { it.id }, decodedList.actions.map { it.id })

        val resultEnvelope = GromozekaServerEnvelope(
            id = "quick-text-result-1",
            payload = QuickTextActionResultResponse(
                QuickTextActionResult(
                    actionId = QuickTextAction.FIX_TEXT_ID,
                    text = "hello",
                )
            ),
        )
        val decodedResult = RemoteProtocolCodec.decodeServerText(
            RemoteProtocolCodec.encodeServerText(resultEnvelope)
        ).payload as QuickTextActionResultResponse

        assertEquals(QuickTextAction.FIX_TEXT_ID, decodedResult.result.actionId)
        assertEquals("hello", decodedResult.result.text)
    }

    @Test
    fun cborRoundTripSupportsConversationSubmitAndObservation() {
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("message-submit-1"),
            conversationId = Conversation.Id("conversation-submit-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Submit this")),
            createdAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        val agentDefinitionId = AgentDefinition.Id("agent-submit-1")

        val submitEnvelope = GromozekaClientEnvelope(
            id = "submit-1",
            payload = SubmitMessageRequest(
                conversationId = Conversation.Id("conversation-submit-1"),
                userMessage = userMessage,
                agentDefinitionId = agentDefinitionId,
            )
        )
        val decodedSubmit = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(submitEnvelope)
        ).payload as SubmitMessageRequest

        assertEquals("conversation-submit-1", decodedSubmit.conversationId.value)
        assertEquals("message-submit-1", decodedSubmit.userMessage.id.value)
        assertEquals(agentDefinitionId, decodedSubmit.agentDefinitionId)

        val observeEnvelope = GromozekaClientEnvelope(
            id = "observe-1",
            payload = ObserveConversationCommand(
                subscriptionId = "subscription-1",
                conversationId = Conversation.Id("conversation-submit-1"),
                afterEventSequence = 41,
            )
        )
        val decodedObserve = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(observeEnvelope)
        ).payload as ObserveConversationCommand

        assertEquals("subscription-1", decodedObserve.subscriptionId)
        assertEquals("conversation-submit-1", decodedObserve.conversationId.value)
        assertEquals(41, decodedObserve.afterEventSequence)

        val messageEnvelope = GromozekaServerEnvelope(
            id = "subscription-1",
            payload = MessageUpsertedEvent(
                subscriptionId = "subscription-1",
                conversationId = Conversation.Id("conversation-submit-1"),
                taskId = ConversationRuntimeTask.Id("message-submit-1"),
                message = userMessage,
                cursorSequence = 42,
            )
        )
        val decodedMessage = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(messageEnvelope)
        ).payload as MessageUpsertedEvent

        assertEquals("subscription-1", decodedMessage.subscriptionId)
        assertEquals("message-submit-1", decodedMessage.taskId?.value)
        assertEquals("message-submit-1", decodedMessage.message.id.value)
        assertEquals(42, decodedMessage.cursorSequence)

        val completedEnvelope = GromozekaServerEnvelope(
            id = "subscription-1",
            payload = ConversationExecutionCompletedEvent(
                subscriptionId = "subscription-1",
                conversationId = Conversation.Id("conversation-submit-1"),
                cursorSequence = 43,
            )
        )
        val decodedCompleted = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(completedEnvelope)
        ).payload as ConversationExecutionCompletedEvent

        assertEquals("subscription-1", decodedCompleted.subscriptionId)
        assertEquals("conversation-submit-1", decodedCompleted.conversationId.value)
        assertEquals(43, decodedCompleted.cursorSequence)
    }

    @Test
    fun cborRoundTripSupportsRuntimeControlRequest() {
        val envelope = GromozekaClientEnvelope(
            id = "control-runtime-1",
            payload = ControlConversationRuntimeRequest(
                conversationId = Conversation.Id("conversation-control-1"),
                action = ConversationRuntimeControlAction.PAUSE,
            )
        )

        val decoded = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(envelope)
        ).payload as ControlConversationRuntimeRequest

        assertEquals("conversation-control-1", decoded.conversationId.value)
        assertEquals(ConversationRuntimeControlAction.PAUSE, decoded.action)
    }

    @Test
    fun cborRoundTripSupportsCommandTaskCancellation() {
        val envelope = GromozekaClientEnvelope(
            id = "cancel-command-1",
            payload = CancelCommandTaskRequest(
                conversationId = Conversation.Id("conversation-command-1"),
                taskId = CommandTask.Id("command-task-1"),
            )
        )

        val decoded = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(envelope)
        ).payload as CancelCommandTaskRequest

        assertEquals("conversation-command-1", decoded.conversationId.value)
        assertEquals("command-task-1", decoded.taskId.value)
    }

    @Test
    fun cborRoundTripSupportsCommandMonitorCancellation() {
        val envelope = GromozekaClientEnvelope(
            id = "cancel-monitor-1",
            payload = CancelCommandMonitorRequest(
                conversationId = Conversation.Id("conversation-command-1"),
                monitorId = CommandMonitor.Id("command-monitor-1"),
            )
        )

        val decoded = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(envelope)
        ).payload as CancelCommandMonitorRequest

        assertEquals("conversation-command-1", decoded.conversationId.value)
        assertEquals("command-monitor-1", decoded.monitorId.value)
    }

    @Test
    fun cborRoundTripPreservesCommandsAndMonitorsInRuntimeSnapshot() {
        val now = Instant.parse("2026-07-15T12:00:00Z")
        val commandTask = CommandTask(
            id = CommandTask.Id("command-task-1"),
            conversationId = Conversation.Id("conversation-command-1"),
            workerId = ConversationRuntimeWorkerId("worker-command-1"),
            workspaceMountId = WorkspaceMount.Id("mount-command-1"),
            command = "./gradlew build",
            workingDirectory = "/workspace",
            status = CommandTask.Status.WORKING,
            processId = 321,
            processStartedAt = now,
            outputFile = "/tmp/command-task-1.log",
            outputBytes = 42,
            createdAt = now,
            updatedAt = now,
        )
        val monitor = CommandMonitor(
            id = CommandMonitor.Id("command-monitor-1"),
            conversationId = commandTask.conversationId,
            commandTaskId = commandTask.id,
            workerId = commandTask.workerId,
            workspaceMountId = commandTask.workspaceMountId,
            filterCommand = "grep --line-buffered READY",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.NOW,
            status = CommandMonitor.Status.WORKING,
            sourceOutputCursor = 42,
            processId = 322,
            processStartedAt = now,
            outputFile = "/tmp/command-monitor-1.log",
            errorFile = "/tmp/command-monitor-1.err",
            outputBytes = 6,
            eventOutputCursor = 6,
            eventCount = 1,
            lastEventAt = now,
            lastEventPreview = "READY",
            createdAt = now,
            updatedAt = now,
        )
        val envelope = GromozekaServerEnvelope(
            id = "runtime-command-1",
            payload = StateSyncSnapshotResponse(
                query = ConversationRuntimeStateQuery(commandTask.conversationId),
                cursor = RemoteStateSyncCursor(
                    sourceEpoch = "server-1",
                    streamEpoch = 2,
                    generation = 3,
                ),
                state = ConversationRuntimeStatePayload(
                    ConversationRuntimeSnapshot(
                        revision = 1,
                        conversationId = commandTask.conversationId,
                        state = null,
                        pendingTasks = emptyList(),
                        commandTasks = listOf(commandTask),
                        commandMonitors = listOf(monitor),
                    ),
                ),
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(envelope)
        ).payload as StateSyncSnapshotResponse
        val snapshot = (decoded.state as ConversationRuntimeStatePayload).snapshot

        assertEquals(commandTask, snapshot.commandTasks.single())
        assertEquals(monitor, snapshot.commandMonitors.single())
    }

    @Test
    fun cborRoundTripSupportsStateSyncControlMessages() {
        val query = DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.PROJECTS)
        val cursor = RemoteStateSyncCursor(
            sourceEpoch = "server-1",
            streamEpoch = 4,
            generation = 7,
        )
        val observe = GromozekaClientEnvelope(
            id = "observe-state-1",
            payload = ObserveStateSyncCommand("subscription-1", query),
        )
        val invalidation = GromozekaServerEnvelope(
            id = "subscription-1",
            payload = StateSyncInvalidatedEvent("subscription-1", query, cursor),
        )

        val decodedObserve = RemoteProtocolCodec.decodeClientBinary(
            RemoteProtocolCodec.encodeClientBinary(observe)
        ).payload as ObserveStateSyncCommand
        val decodedInvalidation = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(invalidation)
        ).payload as StateSyncInvalidatedEvent

        assertEquals(query, decodedObserve.query)
        assertEquals(query, decodedInvalidation.query)
        assertEquals(cursor, decodedInvalidation.cursor)
    }

    @Test
    fun roundTripSupportsDeclarativeRevisionState() {
        val query = DeclarativeStateRevisionQuery(
            resource = RemoteDeclarativeStateResource.PROJECT_CONVERSATIONS,
            scopeId = "project-1",
        )
        val cursor = RemoteStateSyncCursor("server-1", streamEpoch = 3, generation = 9)
        val pull = GromozekaClientEnvelope(
            id = "pull-declarative-1",
            payload = PullStateSyncRequest(query, cursor),
        )
        val snapshot = GromozekaServerEnvelope(
            id = "pull-declarative-1",
            payload = StateSyncSnapshotResponse(query, cursor, DeclarativeStateRevisionPayload),
        )

        val decodedPull = RemoteProtocolCodec.decodeClientText(
            RemoteProtocolCodec.encodeClientText(pull)
        ).payload as PullStateSyncRequest
        val decodedSnapshot = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(snapshot)
        ).payload as StateSyncSnapshotResponse

        assertEquals(query, decodedPull.query)
        assertEquals(cursor, decodedPull.invalidationCursor)
        assertEquals(query, decodedSnapshot.query)
        assertEquals(DeclarativeStateRevisionPayload, decodedSnapshot.state)
    }

    @Test
    fun cborRoundTripSupportsMemoryActionItems() {
        val actionItem = MemoryActionItem(
            id = MemoryActionItem.Id("actionItem-1"),
            namespace = MemoryNamespace("project:demo"),
            title = "Check memory actionItem UI",
            description = "Render current memory actionItems in a read-only panel.",
            status = MemoryActionItem.Status.IN_PROGRESS,
            priority = MemoryActionItem.Priority.HIGH,
            scope = MemoryScope.Global("Demo project"),
            acceptanceCriteria = listOf("Panel shows active actionItems"),
            blockers = listOf("No blocker"),
            confidence = 0.9,
            createdAt = Instant.parse("2026-05-11T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-11T01:00:00Z"),
        )
        val envelope = GromozekaServerEnvelope(
            id = "response-2",
            payload = MemoryActionItemsResponse(
                revision = "revision-1",
                counts = MemoryActionItemCounts(inProgress = 1),
                actionItems = listOf(actionItem)
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope))
        val response = decoded.payload as MemoryActionItemsResponse

        assertEquals("revision-1", response.revision)
        assertEquals(MemoryActionItem.Status.IN_PROGRESS, response.actionItems.single().status)
        assertEquals("Check memory actionItem UI", response.actionItems.single().title)
    }

    private fun audioEnvelope(bytes: ByteArray): GromozekaClientEnvelope =
        GromozekaClientEnvelope(
            id = "request-1",
            payload = TranscribeAudioRequest(
                recording = RemoteAudioRecording(
                    sessionId = "session-1",
                    format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
                    chunks = listOf(
                        RemoteAudioChunk(
                            sequenceNumber = 0,
                            data = bytes
                        )
                    )
                )
            )
        )

    private fun GromozekaClientEnvelope.audioBytes(): ByteArray =
        ((payload as TranscribeAudioRequest).recording.chunks.single()).data
}
