package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.remote.protocol.ClientPayload
import com.gromozeka.remote.protocol.ClientRequest
import com.gromozeka.remote.protocol.ConversationExecutionCompletedEvent
import com.gromozeka.remote.protocol.ConversationExecutionFailedEvent
import com.gromozeka.remote.protocol.ConversationRuntimeSnapshotEvent
import com.gromozeka.remote.protocol.ConversationTabLayoutSnapshotEvent
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
import com.gromozeka.remote.protocol.ObserveConversationTabLayoutCommand
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
import com.gromozeka.remote.protocol.StopObserveConversationTabLayoutCommand
import com.gromozeka.remote.protocol.SynthesizeSpeechStreamCommand
import com.gromozeka.shared.uuid.uuid7
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val registryMutex = Mutex()
    private val _connectionState = MutableStateFlow(RemoteConnectionState(RemoteConnectionState.Status.DISCONNECTED))
    val connectionState: StateFlow<RemoteConnectionState> = _connectionState.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var hasConnected = false
    private var closed = false
    private val pending = mutableMapOf<String, CompletableDeferred<ServerResponse>>()
    private val streams = mutableMapOf<String, Channel<ServerPayload>>()
    private val conversationSubscriptions = mutableMapOf<String, ConversationSubscription>()
    private val conversationEventSequences = mutableMapOf<Conversation.Id, Long>()
    private val conversationTabLayoutSubscriptions = mutableMapOf<String, Channel<ConversationTabLayout>>()
    private val liveInterpreterSessions = mutableMapOf<String, Channel<ServerPayload>>()

    suspend fun request(payload: ClientRequest): ServerResponse {
        val id = uuid7()
        val deferred = CompletableDeferred<ServerResponse>()
        registryMutex.withLock {
            pending[id] = deferred
        }

        try {
            println("Gromozeka WS request id=$id type=${payload::class.simpleName}")
            sendEnvelope(GromozekaClientEnvelope(id, payload))
            return deferred.await().also { response ->
                println("Gromozeka WS response id=$id type=${response::class.simpleName}")
            }
        } finally {
            registryMutex.withLock {
                pending.remove(id)
            }
        }
    }

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent> = flow {
        val subscriptionId = uuid7()
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        val subscription = ConversationSubscription(
            subscriptionId = subscriptionId,
            conversationId = conversationId,
            initialAfterEventSequence = afterEventSequence,
            channel = channel,
        )
        registryMutex.withLock {
            conversationSubscriptions[subscriptionId] = subscription
        }

        runCatching {
            val connection = ensureConnected()
            if (!connection.newlyConnected) {
                sendObserveConversation(connection.session, subscription)
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            println(
                "Gromozeka WS initial conversation observation deferred until reconnect: " +
                    "conversation=${conversationId.value} error=${error.message}"
            )
            scheduleReconnect()
        }

        try {
            for (event in channel) {
                val cursorSequence = event.cursorSequenceOrNull()
                if (cursorSequence != null) {
                    val previousSequence = registryMutex.withLock {
                        conversationEventSequences[conversationId] ?: 0L
                    }
                    if (cursorSequence <= previousSequence && event !is ConversationRuntimeSnapshotEvent) {
                        continue
                    }
                    registryMutex.withLock {
                        conversationEventSequences[conversationId] = cursorSequence
                    }
                }
                when (event) {
                    is ConversationRuntimeSnapshotEvent -> emit(
                        ConversationRuntimeEvent.SnapshotUpdated(
                            conversationId = event.conversationId,
                            snapshot = event.snapshot,
                            cursorSequence = event.cursorSequence,
                        )
                    )
                    is MessageUpsertedEvent -> emit(
                        ConversationRuntimeEvent.MessageEmitted(
                            conversationId = event.conversationId,
                            taskId = event.taskId,
                            message = event.message,
                            cursorSequence = event.cursorSequence,
                        )
                    )
                    is ConversationExecutionCompletedEvent -> emit(
                        ConversationRuntimeEvent.ExecutionCompleted(
                            conversationId = event.conversationId,
                            cursorSequence = event.cursorSequence,
                        )
                    )
                    is ConversationExecutionFailedEvent -> emit(
                        ConversationRuntimeEvent.ExecutionFailed(
                            conversationId = event.conversationId,
                            message = event.message,
                            failureType = event.type,
                            cursorSequence = event.cursorSequence,
                        )
                    )
                    else -> Unit
                }
            }
        } finally {
            registryMutex.withLock {
                conversationSubscriptions.remove(subscriptionId)
            }
            runCatching { sendIfConnected(StopObserveConversationCommand(subscriptionId)) }
            channel.close()
        }
    }

    fun observeConversationTabLayout(): Flow<ConversationTabLayout> = flow {
        val subscriptionId = uuid7()
        val channel = Channel<ConversationTabLayout>(Channel.CONFLATED)
        registryMutex.withLock {
            conversationTabLayoutSubscriptions[subscriptionId] = channel
        }

        runCatching {
            val connection = ensureConnected()
            if (!connection.newlyConnected) {
                sendEnvelope(
                    connection.session,
                    GromozekaClientEnvelope(
                        id = uuid7(),
                        payload = ObserveConversationTabLayoutCommand(subscriptionId),
                    ),
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            scheduleReconnect()
        }

        try {
            for (layout in channel) {
                emit(layout)
            }
        } finally {
            registryMutex.withLock {
                conversationTabLayoutSubscriptions.remove(subscriptionId)
            }
            runCatching { sendIfConnected(StopObserveConversationTabLayoutCommand(subscriptionId)) }
            channel.close()
        }
    }

    private fun ServerPayload.cursorSequenceOrNull(): Long? =
        when (this) {
            is ConversationRuntimeSnapshotEvent -> cursorSequence
            is MessageUpsertedEvent -> cursorSequence
            is ConversationExecutionCompletedEvent -> cursorSequence
            is ConversationExecutionFailedEvent -> cursorSequence
            else -> null
        }

    fun synthesizeSpeech(
        text: String,
        tone: String,
    ): Flow<ServerPayload> = flow {
        val streamId = uuid7()
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        registryMutex.withLock {
            streams[streamId] = channel
        }
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
            registryMutex.withLock {
                streams.remove(streamId)
            }
            channel.close()
        }
    }

    suspend fun startLiveInterpreter(request: StartLiveInterpreterRequest): LiveInterpreterClientSession {
        val response = requestTyped<StartLiveInterpreterRequest, LiveInterpreterStartedResponse>(request)
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        registryMutex.withLock {
            liveInterpreterSessions[response.sessionId] = channel
        }
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
        scope.launch {
            registryMutex.withLock {
                liveInterpreterSessions.remove(sessionId)
            }?.close()
        }
    }

    private suspend fun send(payload: ClientPayload) {
        sendEnvelope(GromozekaClientEnvelope(uuid7(), payload))
    }

    private suspend fun sendIfConnected(payload: ClientPayload) {
        val activeSession = connectMutex.withLock {
            session?.takeIf { it.isActive }
        } ?: return
        sendEnvelope(activeSession, GromozekaClientEnvelope(uuid7(), payload))
    }

    private suspend fun sendEnvelope(envelope: GromozekaClientEnvelope) {
        val connection = ensureConnected()
        sendEnvelope(connection.session, envelope)
    }

    private suspend fun sendEnvelope(
        activeSession: DefaultClientWebSocketSession,
        envelope: GromozekaClientEnvelope,
    ) {
        try {
            sendEnvelopeRaw(activeSession, envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleConnectionLoss(activeSession, error)
            throw error
        }
    }

    private suspend fun sendEnvelopeRaw(
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

    private suspend fun ensureConnected(reconnectAttempt: Int = 0): ActiveConnection =
        connectMutex.withLock {
            check(!closed) { "Gromozeka WS client is closed" }

            val current = session
            if (current != null && current.isActive) {
                return@withLock ActiveConnection(current, newlyConnected = false)
            }

            _connectionState.value = RemoteConnectionState(
                status = if (hasConnected) {
                    RemoteConnectionState.Status.RECONNECTING
                } else {
                    RemoteConnectionState.Status.CONNECTING
                },
                reconnectAttempt = reconnectAttempt,
                lastError = _connectionState.value.lastError,
            )

            try {
                val newSession = httpClient.webSocketSession(url)
                println("Gromozeka WS connected url=$url")
                session = newSession
                readerJob?.cancel()
                readerJob = scope.launch {
                    readLoop(newSession)
                }
                resubscribe(newSession)
                hasConnected = true
                _connectionState.value = RemoteConnectionState(RemoteConnectionState.Status.CONNECTED)
                ActiveConnection(newSession, newlyConnected = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                session?.cancel()
                session = null
                _connectionState.value = RemoteConnectionState(
                    status = RemoteConnectionState.Status.OFFLINE,
                    reconnectAttempt = reconnectAttempt,
                    lastError = error.message ?: error.toString(),
                )
                throw error
            }
        }

    private suspend fun readLoop(activeSession: DefaultClientWebSocketSession) {
        var failure: Throwable? = null
        try {
            for (frame in activeSession.incoming) {
                val envelope = when (frame) {
                    is Frame.Binary -> RemoteProtocolCodec.decodeServerBinary(frame.readBytes())
                    is Frame.Text -> RemoteProtocolCodec.decodeServerText(frame.readText())
                    else -> continue
                }
                println("Gromozeka WS incoming id=${envelope.id} type=${envelope.payload::class.simpleName}")
                when (val payload = envelope.payload) {
                    is ServerResponse -> registryMutex.withLock { pending.remove(envelope.id) }?.complete(payload)
                    is ConversationRuntimeSnapshotEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is MessageUpsertedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is ConversationExecutionCompletedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is ConversationExecutionFailedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is ConversationTabLayoutSnapshotEvent -> routeConversationTabLayoutEvent(payload)
                    is SpeechSynthesisStartedEvent -> routeStreamEvent(payload.streamId, payload)
                    is SpeechSynthesisChunkEvent -> routeStreamEvent(payload.streamId, payload)
                    is SpeechSynthesisCompletedEvent -> routeStreamEvent(payload.streamId, payload)
                    is SpeechSynthesisFailedEvent -> routeStreamEvent(payload.streamId, payload)
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
            failure = IllegalStateException("Gromozeka WebSocket closed")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure = error
            println("Gromozeka WS read loop failed: ${error.message ?: error.toString()}")
        } finally {
            if (!closed) {
                handleConnectionLoss(
                    activeSession,
                    failure ?: IllegalStateException("Gromozeka WebSocket read loop stopped"),
                )
            }
        }
    }

    private suspend fun resubscribe(activeSession: DefaultClientWebSocketSession) {
        val (conversationSubscriptionsSnapshot, tabLayoutSubscriptionIds) = registryMutex.withLock {
            conversationSubscriptions.values.toList() to conversationTabLayoutSubscriptions.keys.toList()
        }
        conversationSubscriptionsSnapshot.forEach { subscription ->
            sendObserveConversationRaw(activeSession, subscription)
        }
        tabLayoutSubscriptionIds.forEach { subscriptionId ->
            sendEnvelopeRaw(
                activeSession,
                GromozekaClientEnvelope(
                    id = uuid7(),
                    payload = ObserveConversationTabLayoutCommand(subscriptionId),
                ),
            )
        }
    }

    private suspend fun sendObserveConversation(
        activeSession: DefaultClientWebSocketSession,
        subscription: ConversationSubscription,
    ) {
        sendEnvelope(
            activeSession,
            observeConversationEnvelope(subscription),
        )
    }

    private suspend fun sendObserveConversationRaw(
        activeSession: DefaultClientWebSocketSession,
        subscription: ConversationSubscription,
    ) {
        sendEnvelopeRaw(
            activeSession,
            observeConversationEnvelope(subscription),
        )
    }

    private suspend fun observeConversationEnvelope(
        subscription: ConversationSubscription,
    ): GromozekaClientEnvelope {
        val replayAfterSequence = registryMutex.withLock {
            listOfNotNull(
                subscription.initialAfterEventSequence,
                conversationEventSequences[subscription.conversationId],
            ).maxOrNull()
        }
        return GromozekaClientEnvelope(
            id = uuid7(),
            payload = ObserveConversationCommand(
                subscriptionId = subscription.subscriptionId,
                conversationId = subscription.conversationId,
                afterEventSequence = replayAfterSequence,
            ),
        )
    }

    private suspend fun handleConnectionLoss(
        activeSession: DefaultClientWebSocketSession,
        error: Throwable,
    ) {
        val disconnected = connectMutex.withLock {
            if (session !== activeSession || closed) {
                false
            } else {
                session = null
                _connectionState.value = RemoteConnectionState(
                    status = RemoteConnectionState.Status.OFFLINE,
                    lastError = error.message ?: error.toString(),
                )
                true
            }
        }
        if (!disconnected) {
            return
        }

        activeSession.cancel()
        failNonResumableOperations(error)
        scheduleReconnect()
    }

    private suspend fun failNonResumableOperations(error: Throwable) {
        val pendingRequests: List<CompletableDeferred<ServerResponse>>
        val activeStreams: List<Channel<ServerPayload>>
        val activeInterpreterSessions: List<Channel<ServerPayload>>
        registryMutex.withLock {
            pendingRequests = pending.values.toList()
            pending.clear()
            activeStreams = streams.values.toList()
            streams.clear()
            activeInterpreterSessions = liveInterpreterSessions.values.toList()
            liveInterpreterSessions.clear()
        }
        pendingRequests.forEach { it.completeExceptionally(error) }
        activeStreams.forEach { it.close(error) }
        activeInterpreterSessions.forEach { it.close(error) }
    }

    private suspend fun scheduleReconnect() {
        if (!hasResumableSubscriptions()) {
            return
        }
        connectMutex.withLock {
            if (closed || reconnectJob?.isActive == true) {
                return@withLock
            }
            reconnectJob = scope.launch {
                reconnectLoop()
            }
        }
    }

    private suspend fun reconnectLoop() {
        var attempt = 1
        try {
            while (scope.isActive && !closed && hasResumableSubscriptions()) {
                delay(reconnectDelayMillis(attempt))
                if (!hasResumableSubscriptions()) {
                    return
                }
                try {
                    ensureConnected(reconnectAttempt = attempt)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    attempt++
                }
            }
        } finally {
            connectMutex.withLock {
                reconnectJob = null
            }
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (RECONNECT_INITIAL_DELAY_MILLIS * (1L shl exponent))
            .coerceAtMost(RECONNECT_MAX_DELAY_MILLIS)
    }

    private suspend fun hasResumableSubscriptions(): Boolean =
        registryMutex.withLock {
            conversationSubscriptions.isNotEmpty() || conversationTabLayoutSubscriptions.isNotEmpty()
        }

    private suspend fun routeConversationEvent(subscriptionId: String, payload: ServerPayload) {
        registryMutex.withLock {
            conversationSubscriptions[subscriptionId]?.channel
        }?.send(payload)
    }

    private suspend fun routeConversationTabLayoutEvent(event: ConversationTabLayoutSnapshotEvent) {
        registryMutex.withLock {
            conversationTabLayoutSubscriptions[event.subscriptionId]
        }?.send(event.layout)
    }

    private suspend fun routeStreamEvent(streamId: String, payload: ServerPayload) {
        registryMutex.withLock {
            streams[streamId]
        }?.send(payload)
    }

    private suspend fun routeLiveInterpreterEvent(sessionId: String, payload: ServerPayload) {
        registryMutex.withLock {
            liveInterpreterSessions[sessionId]
        }?.send(payload)
    }

    fun close() {
        closed = true
        _connectionState.value = RemoteConnectionState(RemoteConnectionState.Status.CLOSED)
        reconnectJob?.cancel()
        reconnectJob = null
        readerJob?.cancel()
        readerJob = null
        session?.cancel()
        session = null
        httpClient.close()
    }

    private data class ActiveConnection(
        val session: DefaultClientWebSocketSession,
        val newlyConnected: Boolean,
    )

    private data class ConversationSubscription(
        val subscriptionId: String,
        val conversationId: Conversation.Id,
        val initialAfterEventSequence: Long?,
        val channel: Channel<ServerPayload>,
    )

    private companion object {
        const val RECONNECT_INITIAL_DELAY_MILLIS = 500L
        const val RECONNECT_MAX_DELAY_MILLIS = 10_000L
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
