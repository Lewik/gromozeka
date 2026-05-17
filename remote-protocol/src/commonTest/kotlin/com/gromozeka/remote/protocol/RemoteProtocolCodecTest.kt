package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryTask
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
                    mediaType = "audio/wav",
                    fileExtension = "wav",
                    sampleRate = 16_000,
                    channels = 1,
                    bitDepth = 16,
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
        assertEquals("audio/wav", command.chunk.mediaType)

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
    fun cborRoundTripSupportsMemoryTasks() {
        val task = MemoryTask(
            id = MemoryTask.Id("task-1"),
            namespace = MemoryNamespace("project:demo"),
            title = "Check memory task UI",
            description = "Render current memory tasks in a read-only panel.",
            status = MemoryTask.Status.IN_PROGRESS,
            priority = MemoryTask.Priority.HIGH,
            scope = MemoryScope.Global("Demo project"),
            acceptanceCriteria = listOf("Panel shows active tasks"),
            blockers = listOf("No blocker"),
            confidence = 0.9,
            createdAt = Instant.parse("2026-05-11T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-11T01:00:00Z"),
        )
        val envelope = GromozekaServerEnvelope(
            id = "response-2",
            payload = MemoryTasksResponse(
                revision = "revision-1",
                counts = MemoryTaskCounts(inProgress = 1),
                tasks = listOf(task)
            )
        )

        val decoded = RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope))
        val response = decoded.payload as MemoryTasksResponse

        assertEquals("revision-1", response.revision)
        assertEquals(MemoryTask.Status.IN_PROGRESS, response.tasks.single().status)
        assertEquals("Check memory task UI", response.tasks.single().title)
    }

    private fun audioEnvelope(bytes: ByteArray): GromozekaClientEnvelope =
        GromozekaClientEnvelope(
            id = "request-1",
            payload = TranscribeAudioRequest(
                recording = RemoteAudioRecording(
                    sessionId = "session-1",
                    mediaType = "audio/webm",
                    fileExtension = "webm",
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
