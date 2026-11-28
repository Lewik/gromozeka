package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.ClientPayload
import com.gromozeka.remote.protocol.CommitLiveInterpreterCommand
import com.gromozeka.remote.protocol.LiveInterpreterAudioChunkCommand
import com.gromozeka.remote.protocol.ErrorResponse
import com.gromozeka.remote.protocol.GetSettingsRequest
import com.gromozeka.remote.protocol.GromozekaClientEnvelope
import com.gromozeka.remote.protocol.GromozekaServerEnvelope
import com.gromozeka.remote.protocol.LiveInterpreterStartedResponse
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.RemoteClientPlatform
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import com.gromozeka.remote.protocol.LiveTranslationSourceBlock
import com.gromozeka.remote.protocol.TranslatedLiveBlock
import com.gromozeka.remote.protocol.LiveTextTranslatedResponse
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class RemoteLiveInterpreterTransportTest {
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("live-test"))

    @Test
    fun `events arriving before the start response are not lost`() = testApplication {
        val stopped = CompletableDeferred<Unit>()
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                incoming.receive()
                val request = incoming.receive().envelope()
                val start = request.payload as StartLiveInterpreterRequest
                assertEquals("ru", start.nativeTranslationLanguage)
                sendPayload("early-status", LiveInterpreterStatusEvent(start.sessionId, "interpreter.status.listening"))
                sendPayload(request.id, LiveInterpreterStartedResponse(start.sessionId))
                val stop = incoming.receive().envelope().payload as StopLiveInterpreterCommand
                assertEquals(start.sessionId, stop.sessionId)
                stopped.complete(Unit)
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient(
            url = "ws://localhost/ws",
            httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            clientInstanceId = ClientInstanceId("live-early"),
            clientPlatform = RemoteClientPlatform.DESKTOP,
        )
        try {
            withTimeout(10_000) {
                val session = RemoteLiveInterpreterService(client).start(
                    listOf("he", "en", "ru"), selection, manualTurnControl = false, nativeTranslationLanguage = "ru",
                )
                val status = session.events.first() as LiveInterpreterStatusEvent
                assertEquals("interpreter.status.listening", status.messageKey)
                session.close()
                stopped.await()
            }
        } finally {
            client.close()
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `an old live session never sends audio commit or stop to a reconnected socket`() = testApplication {
        val disconnect = CompletableDeferred<Unit>()
        val connections = AtomicInteger()
        val foreignCommands = Channel<ClientPayload>(Channel.UNLIMITED)
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                val number = connections.incrementAndGet()
                incoming.receive()
                if (number == 1) {
                    val request = incoming.receive().envelope()
                    val start = request.payload as StartLiveInterpreterRequest
                    sendPayload(request.id, LiveInterpreterStartedResponse(start.sessionId))
                    sendPayload("ready", LiveInterpreterStatusEvent(start.sessionId, "interpreter.status.listening"))
                    disconnect.await()
                    close()
                } else {
                    for (frame in incoming) {
                        val envelope = frame.envelope()
                        when (val payload = envelope.payload) {
                            GetSettingsRequest -> sendPayload(envelope.id, ErrorResponse(message = "synthetic fixture response"))
                            else -> foreignCommands.send(payload)
                        }
                    }
                }
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient(
            url = "ws://localhost/ws",
            httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            clientInstanceId = ClientInstanceId("live-reconnect"),
            clientPlatform = RemoteClientPlatform.DESKTOP,
        )
        try {
            withTimeout(10_000) {
                val old = RemoteLiveInterpreterService(client).start(emptyList(), selection)
                old.events.first()
                disconnect.complete(Unit)
                assertTrue(assertFailsWith<LiveTranscriptionException> { old.events.first() }.retryable)
                client.request(GetSettingsRequest)
                assertEquals(2, connections.get())
                assertFailsWith<LiveTranscriptionException> { old.sendAudioChunk(RemotePcmAudioChunk(0, byteArrayOf(0, 0))) }
                assertFailsWith<LiveTranscriptionException> { old.commit() }
                old.close()
                client.request(GetSettingsRequest)
                assertTrue(foreignCommands.tryReceive().isFailure)
                assertEquals(RemoteConnectionState.Status.CONNECTED, client.connectionState.value.status)
            }
        } finally {
            disconnect.complete(Unit)
            client.close()
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `text backfill uses an independent typed RPC without an active ASR session`() = testApplication {
        val request = TranslateLiveTextRequest("retained-text-meeting", 9, "he", emptyList(),
            listOf(LiveTranslationSourceBlock("one", "Retained original.", "Previous draft.")))
        val expected = LiveTextTranslatedResponse(9, "he", listOf(TranslatedLiveBlock("one", "תרגום")))
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                incoming.receive()
                val incomingRequest = incoming.receive().envelope()
                assertEquals(request, incomingRequest.payload)
                sendPayload(incomingRequest.id, expected)
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient(
            url = "ws://localhost/ws", httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            clientInstanceId = ClientInstanceId("live-text"), clientPlatform = RemoteClientPlatform.DESKTOP,
        )
        try {
            withTimeout(10_000) { assertEquals(expected, RemoteLiveInterpreterService(client).translate(request)) }
        } finally { client.close(); owner.cancelAndJoin() }
    }

    @Test
    fun `manual start audio commit and following audio retain their WebSocket order`() = testApplication {
        val observed = CompletableDeferred<List<ClientPayload>>()
        application {
            install(WebSockets)
            routing { webSocket("/ws") {
                incoming.receive()
                val request = incoming.receive().envelope()
                val start = request.payload as StartLiveInterpreterRequest
                assertTrue(start.manualTurnControl)
                sendPayload(request.id, LiveInterpreterStartedResponse(start.sessionId))
                sendPayload("ready", LiveInterpreterStatusEvent(start.sessionId, "interpreter.status.listening"))
                observed.complete((0 until 4).map { incoming.receive().envelope().payload })
            } }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient(
            url = "ws://localhost/ws", httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined), clientInstanceId = ClientInstanceId("live-manual"),
            clientPlatform = RemoteClientPlatform.DESKTOP,
        )
        try {
            withTimeout(10_000) {
                val session = RemoteLiveInterpreterService(client).start(listOf("en"), selection, manualTurnControl = true)
                session.events.first()
                val first = RemotePcmAudioChunk(0, byteArrayOf(0, 1))
                val second = RemotePcmAudioChunk(1, byteArrayOf(0, 2))
                session.sendAudioChunk(first)
                session.commit()
                session.sendAudioChunk(second)
                session.stop()
                val commands = observed.await()
                assertEquals(4, commands.size)
                val a = commands[0] as LiveInterpreterAudioChunkCommand
                val b = commands[2] as LiveInterpreterAudioChunkCommand
                assertEquals(session.sessionId, a.sessionId)
                assertEquals(session.sessionId, b.sessionId)
                assertEquals(0, a.chunk.sequenceNumber)
                assertEquals(1, b.chunk.sequenceNumber)
                assertContentEquals(first.data, a.chunk.data)
                assertContentEquals(second.data, b.chunk.data)
                assertEquals(CommitLiveInterpreterCommand(session.sessionId), commands[1])
                assertEquals(StopLiveInterpreterCommand(session.sessionId), commands[3])
                session.close()
            }
        } finally { client.close(); owner.cancelAndJoin() }
    }

    private fun Frame.envelope(): GromozekaClientEnvelope = when (this) {
        is Frame.Binary -> RemoteProtocolCodec.decodeClientBinary(data)
        is Frame.Text -> RemoteProtocolCodec.decodeClientText(readText())
        else -> error("Unexpected fixture frame: $frameType")
    }

    private suspend fun DefaultWebSocketServerSession.sendPayload(id: String, payload: ServerPayload) {
        send(Frame.Binary(true, RemoteProtocolCodec.encodeServerBinary(GromozekaServerEnvelope(id, payload))))
    }
}
