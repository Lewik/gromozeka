package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteProtocolCodecTest {
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

        val layoutEvent = GromozekaServerEnvelope(
            id = "layout-event-1",
            payload = ConversationTabLayoutSnapshotEvent(
                subscriptionId = "layout-subscription-1",
                layout = ConversationTabLayout(
                    conversationIds = listOf(Conversation.Id("conversation-1")),
                    revision = 7,
                    updatedAt = Instant.parse("2026-07-22T00:00:00Z"),
                ),
            ),
        )
        val decodedLayout = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(layoutEvent)
        ).payload as ConversationTabLayoutSnapshotEvent
        assertEquals(7, decodedLayout.layout.revision)
        assertEquals("conversation-1", decodedLayout.layout.conversationIds.single().value)
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
    fun cborRoundTripPreservesCommandTasksInRuntimeSnapshot() {
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
        val envelope = GromozekaServerEnvelope(
            id = "runtime-command-1",
            payload = ConversationRuntimeSnapshotEvent(
                subscriptionId = "subscription-1",
                conversationId = commandTask.conversationId,
                snapshot = ConversationRuntimeSnapshot(
                    revision = 1,
                    conversationId = commandTask.conversationId,
                    state = null,
                    pendingTasks = emptyList(),
                    commandTasks = listOf(commandTask),
                ),
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(
            RemoteProtocolCodec.encodeServerBinary(envelope)
        ).payload as ConversationRuntimeSnapshotEvent

        assertEquals(commandTask, decoded.snapshot.commandTasks.single())
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
