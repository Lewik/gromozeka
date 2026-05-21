package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.remote.protocol.ClientPayload
import com.gromozeka.remote.protocol.ClientRequest
import com.gromozeka.remote.protocol.ConversationExecutionCompletedEvent
import com.gromozeka.remote.protocol.ConversationExecutionFailedEvent
import com.gromozeka.remote.protocol.ErrorResponse
import com.gromozeka.remote.protocol.GromozekaClientEnvelope
import com.gromozeka.remote.protocol.GromozekaServerEnvelope
import com.gromozeka.remote.protocol.LiveInterpreterAudioChunkCommand
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterDraftsEvent
import com.gromozeka.remote.protocol.LiveInterpreterStartedResponse
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranslationEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptChunkCommand
import com.gromozeka.remote.protocol.MessageUpsertedEvent
import com.gromozeka.remote.protocol.ObserveConversationCommand
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import com.gromozeka.remote.protocol.RemoteLiveTranscriptChunk
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.ServerResponse
import com.gromozeka.remote.protocol.SpeechSynthesisChunkEvent
import com.gromozeka.remote.protocol.SpeechSynthesisCompletedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisFailedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisStartedEvent
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import com.gromozeka.remote.protocol.StopObserveConversationCommand
import com.gromozeka.remote.protocol.SynthesizeSpeechStreamCommand
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
    private val conversationSubscriptions = mutableMapOf<String, Channel<ServerPayload>>()
    private val liveInterpreterSessions = mutableMapOf<String, Channel<ServerPayload>>()

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

    fun observeConversation(conversationId: Conversation.Id): Flow<ConversationRuntimeEvent> = flow {
        val subscriptionId = uuid7()
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        conversationSubscriptions[subscriptionId] = channel
        send(ObserveConversationCommand(subscriptionId, conversationId))

        try {
            for (event in channel) {
                when (event) {
                    is MessageUpsertedEvent -> emit(
                        ConversationRuntimeEvent.MessageEmitted(
                            conversationId = event.conversationId,
                            commandId = event.commandId,
                            message = event.message,
                        )
                    )
                    is ConversationExecutionCompletedEvent -> emit(
                        ConversationRuntimeEvent.ExecutionCompleted(event.conversationId)
                    )
                    is ConversationExecutionFailedEvent -> emit(
                        ConversationRuntimeEvent.ExecutionFailed(
                            conversationId = event.conversationId,
                            message = event.message,
                            type = event.type,
                        )
                    )
                    else -> Unit
                }
            }
        } finally {
            conversationSubscriptions.remove(subscriptionId)
            runCatching { sendIfConnected(StopObserveConversationCommand(subscriptionId)) }
            channel.close()
        }
    }

    fun synthesizeSpeech(
        text: String,
        tone: String,
    ): Flow<ServerPayload> = flow {
        val streamId = uuid7()
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        streams[streamId] = channel
        send(SynthesizeSpeechStreamCommand(streamId, text, tone))

        try {
            for (event in channel) {
                emit(event)
                when (event) {
                    is SpeechSynthesisCompletedEvent,
                    is SpeechSynthesisFailedEvent -> break

                    else -> Unit
                }
            }
        } finally {
            streams.remove(streamId)
            channel.close()
        }
    }

    suspend fun startLiveInterpreter(request: StartLiveInterpreterRequest): LiveInterpreterClientSession {
        val response = requestTyped<StartLiveInterpreterRequest, LiveInterpreterStartedResponse>(request)
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        liveInterpreterSessions[response.sessionId] = channel
        return LiveInterpreterClientSession(response.sessionId, channel)
    }

    suspend fun sendLiveInterpreterAudioChunk(
        sessionId: String,
        chunk: RemoteLiveAudioChunk,
    ) {
        send(LiveInterpreterAudioChunkCommand(sessionId, chunk))
    }

    suspend fun sendLiveInterpreterTranscriptChunk(
        sessionId: String,
        chunk: RemoteLiveTranscriptChunk,
    ) {
        send(LiveInterpreterTranscriptChunkCommand(sessionId, chunk))
    }

    suspend fun stopLiveInterpreter(sessionId: String) {
        send(StopLiveInterpreterCommand(sessionId))
    }

    fun closeLiveInterpreterSession(sessionId: String) {
        liveInterpreterSessions.remove(sessionId)?.close()
    }

    private suspend fun send(payload: ClientPayload) {
        sendEnvelope(GromozekaClientEnvelope(uuid7(), payload))
    }

    private suspend fun sendIfConnected(payload: ClientPayload) {
        val activeSession = session?.takeIf { it.isActive } ?: return
        sendEnvelope(activeSession, GromozekaClientEnvelope(uuid7(), payload))
    }

    private suspend fun sendEnvelope(envelope: GromozekaClientEnvelope) {
        val activeSession = ensureConnected()
        sendEnvelope(activeSession, envelope)
    }

    private suspend fun sendEnvelope(
        activeSession: DefaultClientWebSocketSession,
        envelope: GromozekaClientEnvelope,
    ) {
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
                    is MessageUpsertedEvent -> conversationSubscriptions[payload.subscriptionId]?.send(payload)
                    is ConversationExecutionCompletedEvent -> conversationSubscriptions[payload.subscriptionId]?.send(payload)
                    is ConversationExecutionFailedEvent -> conversationSubscriptions[payload.subscriptionId]?.send(payload)
                    is SpeechSynthesisStartedEvent -> streams[payload.streamId]?.send(payload)
                    is SpeechSynthesisChunkEvent -> streams[payload.streamId]?.send(payload)
                    is SpeechSynthesisCompletedEvent -> streams[payload.streamId]?.send(payload)
                    is SpeechSynthesisFailedEvent -> streams[payload.streamId]?.send(payload)
                    is LiveInterpreterStatusEvent -> routeLiveInterpreterEvent(payload.sessionId, payload)
                    is LiveInterpreterTranscriptEvent -> routeLiveInterpreterEvent(payload.sessionId, payload)
                    is LiveInterpreterDraftsEvent -> routeLiveInterpreterEvent(payload.sessionId, payload)
                    is LiveInterpreterTranslationEvent -> routeLiveInterpreterEvent(payload.sessionId, payload)
                    is LiveInterpreterStoppedEvent -> {
                        routeLiveInterpreterEvent(payload.sessionId, payload)
                        closeLiveInterpreterSession(payload.sessionId)
                    }
                    is LiveInterpreterFailedEvent -> {
                        routeLiveInterpreterEvent(payload.sessionId, payload)
                        closeLiveInterpreterSession(payload.sessionId)
                    }
                }
            }
        } catch (error: Throwable) {
            println("Gromozeka WS read loop failed: ${error.message ?: error.toString()}")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            streams.values.forEach { it.close(error) }
            streams.clear()
            conversationSubscriptions.values.forEach { it.close(error) }
            conversationSubscriptions.clear()
            liveInterpreterSessions.values.forEach { it.close(error) }
            liveInterpreterSessions.clear()
            if (session === activeSession) {
                session = null
            }
        }
    }

    private fun routeLiveInterpreterEvent(sessionId: String, payload: ServerPayload) {
        liveInterpreterSessions[sessionId]?.trySend(payload)
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        session?.cancel()
        session = null
        httpClient.close()
    }
}

internal class LiveInterpreterClientSession(
    val sessionId: String,
    internal val channel: Channel<ServerPayload>,
)

internal suspend inline fun <reified TRequest : ClientRequest, reified TResponse : ServerResponse> GromozekaWsClient.requestTyped(
    payload: TRequest,
): TResponse =
    when (val response = request(payload)) {
        is ErrorResponse -> error(response.message)
        is TResponse -> response
        else -> error("Unexpected response type: $response")
    }
