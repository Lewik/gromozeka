package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.remote.protocol.ClientPayload
import com.gromozeka.remote.protocol.ClientRequest
import com.gromozeka.remote.protocol.ErrorResponse
import com.gromozeka.remote.protocol.GromozekaClientEnvelope
import com.gromozeka.remote.protocol.GromozekaServerEnvelope
import com.gromozeka.remote.protocol.MessageUpsertedEvent
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.SendCompletedEvent
import com.gromozeka.remote.protocol.SendFailedEvent
import com.gromozeka.remote.protocol.SendMessageCommand
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.ServerResponse
import com.gromozeka.shared.uuid.uuid7
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GromozekaWsClient(
    private val url: String = GromozekaRemoteDefaults.REMOTE_URL,
    encoding: RemoteProtocolEncoding = RemoteProtocolEncoding.CBOR,
    private val httpClient: HttpClient = HttpClient {
        install(WebSockets)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val encodingState = MutableStateFlow(encoding)
    private val connectMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private val pending = mutableMapOf<String, CompletableDeferred<ServerResponse>>()
    private val streams = mutableMapOf<String, Channel<ServerPayload>>()

    suspend fun request(payload: ClientRequest): ServerResponse {
        val id = uuid7()
        val deferred = CompletableDeferred<ServerResponse>()
        pending[id] = deferred

        try {
            println("Gromozeka WS request id=$id type=${payload::class.simpleName}")
            sendEnvelope(GromozekaClientEnvelope(id, payload))
            return deferred.await().also { response ->
                println("Gromozeka WS response id=$id type=${response::class.simpleName}")
            }
        } finally {
            pending.remove(id)
        }
    }

    fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message> = flow {
        val streamId = uuid7()
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        streams[streamId] = channel
        send(SendMessageCommand(streamId, conversationId, userMessage, agent))

        try {
            for (event in channel) {
                when (event) {
                    is MessageUpsertedEvent -> emit(event.message)
                    is SendCompletedEvent -> break
                    is SendFailedEvent -> error(event.message)
                    else -> Unit
                }
            }
        } finally {
            streams.remove(streamId)
            channel.close()
        }
    }

    private suspend fun send(payload: ClientPayload) {
        sendEnvelope(GromozekaClientEnvelope(uuid7(), payload))
    }

    private suspend fun sendEnvelope(envelope: GromozekaClientEnvelope) {
        val activeSession = ensureConnected()
        val frame = when (encodingState.value) {
            RemoteProtocolEncoding.CBOR -> Frame.Binary(true, RemoteProtocolCodec.encodeClientBinary(envelope))
            RemoteProtocolEncoding.JSON -> Frame.Text(RemoteProtocolCodec.encodeClientText(envelope))
        }
        activeSession.outgoing.send(frame)
    }

    fun setEncoding(encoding: RemoteProtocolEncoding) {
        encodingState.value = encoding
        println("Gromozeka WS protocol encoding=${encoding.name}")
    }

    private suspend fun ensureConnected(): DefaultClientWebSocketSession =
        connectMutex.withLock {
            val current = session
            if (current != null && current.isActive) {
                return@withLock current
            }

            val newSession = httpClient.webSocketSession(url)
            println("Gromozeka WS connected url=$url")
            session = newSession
            readerJob?.cancel()
            readerJob = scope.launch {
                readLoop(newSession)
            }
            newSession
        }

    private suspend fun readLoop(activeSession: DefaultClientWebSocketSession) {
        try {
            for (frame in activeSession.incoming) {
                val envelope = when (frame) {
                    is Frame.Binary -> RemoteProtocolCodec.decodeServerBinary(frame.readBytes())
                    is Frame.Text -> RemoteProtocolCodec.decodeServerText(frame.readText())
                    else -> continue
                }
                println("Gromozeka WS incoming id=${envelope.id} type=${envelope.payload::class.simpleName}")
                when (val payload = envelope.payload) {
                    is ServerResponse -> pending.remove(envelope.id)?.complete(payload)
                    is MessageUpsertedEvent -> streams[payload.streamId]?.send(payload)
                    is SendCompletedEvent -> streams[payload.streamId]?.send(payload)
                    is SendFailedEvent -> streams[payload.streamId]?.send(payload)
                }
            }
        } catch (error: Throwable) {
            println("Gromozeka WS read loop failed: ${error.message ?: error.toString()}")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            streams.values.forEach { it.close(error) }
            streams.clear()
            if (session === activeSession) {
                session = null
            }
        }
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        session?.cancel()
        session = null
        httpClient.close()
    }
}

internal suspend inline fun <reified TRequest : ClientRequest, reified TResponse : ServerResponse> GromozekaWsClient.requestTyped(
    payload: TRequest,
): TResponse =
    when (val response = request(payload)) {
        is ErrorResponse -> error(response.message)
        is TResponse -> response
        else -> error("Unexpected response type: $response")
    }
