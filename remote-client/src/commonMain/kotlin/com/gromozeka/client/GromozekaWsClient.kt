package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.remote.protocol.ClientActivityKind
import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.ClientPayload
import com.gromozeka.remote.protocol.ClientPresentationDirective
import com.gromozeka.remote.protocol.ClientRequest
import com.gromozeka.remote.protocol.ClientSessionId
import com.gromozeka.remote.protocol.ConversationExecutionCompletedEvent
import com.gromozeka.remote.protocol.ConversationExecutionFailedEvent
import com.gromozeka.remote.protocol.ConversationRuntimeStatePayload
import com.gromozeka.remote.protocol.ConversationRuntimeStateQuery
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
import com.gromozeka.remote.protocol.LiveVoiceProviderVadAudioChunkCommand
import com.gromozeka.remote.protocol.LiveVoiceProviderVadFailedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStartedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStartedResponse
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStatusEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptCompletedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptDeltaEvent
import com.gromozeka.remote.protocol.MessageUpsertedEvent
import com.gromozeka.remote.protocol.ObserveConversationCommand
import com.gromozeka.remote.protocol.ObserveStateSyncCommand
import com.gromozeka.remote.protocol.PullStateSyncRequest
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import com.gromozeka.remote.protocol.RemoteLiveTranscriptChunk
import com.gromozeka.remote.protocol.RemoteClientPlatform
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.RemoteStateSyncCursor
import com.gromozeka.remote.protocol.RemoteStateSyncPayload
import com.gromozeka.remote.protocol.RemoteStateSyncQuery
import com.gromozeka.remote.protocol.RegisterClientSessionCommand
import com.gromozeka.remote.protocol.ReportClientActivityCommand
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.ServerResponse
import com.gromozeka.remote.protocol.SpeechSynthesisChunkEvent
import com.gromozeka.remote.protocol.SpeechSynthesisCompletedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisFailedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisStartedEvent
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StartLiveVoiceProviderVadRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import com.gromozeka.remote.protocol.StopLiveVoiceProviderVadCommand
import com.gromozeka.remote.protocol.StopObserveConversationCommand
import com.gromozeka.remote.protocol.StopObserveStateSyncCommand
import com.gromozeka.remote.protocol.StateSyncInvalidatedEvent
import com.gromozeka.remote.protocol.StateSyncObservationFailedEvent
import com.gromozeka.remote.protocol.StateSyncSnapshotResponse
import com.gromozeka.remote.protocol.SynthesizeSpeechStreamCommand
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.shared.logging.GromozekaLogging
import com.gromozeka.statesync.StateSyncCursor
import com.gromozeka.statesync.StateSyncInvalidation
import com.gromozeka.statesync.StateSyncSnapshot
import com.gromozeka.statesync.observeStateSync
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.isSuccess
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GromozekaWsClient(
    private val url: String = GromozekaRemoteDefaults.REMOTE_URL,
    encoding: RemoteProtocolEncoding = RemoteProtocolEncoding.CBOR,
    internal val httpClient: HttpClient = HttpClient {
        install(WebSockets)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val clientInstanceId: ClientInstanceId,
    private val clientPlatform: RemoteClientPlatform,
) {
    private val log = GromozekaLogging.logger("GromozekaWsClient")
    internal val serverHttpBaseUrl = url
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
        .removeSuffix("/ws")
    private val clientSessionId = ClientSessionId(uuid7())
    private val encodingState = MutableStateFlow(encoding)
    private val connectMutex = Mutex()
    private val registryMutex = Mutex()
    private val _connectionState = MutableStateFlow(RemoteConnectionState(RemoteConnectionState.Status.DISCONNECTED))
    val connectionState: StateFlow<RemoteConnectionState> = _connectionState.asStateFlow()
    private val _presentationDirectives = MutableSharedFlow<ClientPresentationDirective>(extraBufferCapacity = 16)
    val presentationDirectives: SharedFlow<ClientPresentationDirective> = _presentationDirectives.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var hasConnected = false
    private var closed = false
    private val pending = mutableMapOf<String, CompletableDeferred<ServerResponse>>()
    private val streams = mutableMapOf<String, Channel<ServerPayload>>()
    private val conversationSubscriptions = mutableMapOf<String, ConversationSubscription>()
    private val conversationEventSequences = mutableMapOf<Conversation.Id, Long>()
    private val stateSubscriptions = mutableMapOf<String, RemoteStateSubscription>()
    private val liveInterpreterSessions = mutableMapOf<String, Channel<ServerPayload>>()
    private val liveVoiceProviderVadSessions = mutableMapOf<String, Channel<ServerPayload>>()

    fun reportActivity(kind: ClientActivityKind) {
        scope.launch {
            runCatching {
                send(ReportClientActivityCommand(kind))
            }.onFailure { error ->
                if (error !is CancellationException) {
                    log.warn(error) { "Client activity report failed: kind=$kind" }
                }
            }
        }
    }

    suspend fun request(payload: ClientRequest): ServerResponse {
        val id = uuid7()
        val deferred = CompletableDeferred<ServerResponse>()
        registryMutex.withLock {
            pending[id] = deferred
        }

        try {
            log.debug { "Request id=$id type=${payload::class.simpleName}" }
            sendEnvelope(GromozekaClientEnvelope(id, payload))
            return deferred.await().also { response ->
                log.debug { "Response id=$id type=${response::class.simpleName}" }
            }
        } finally {
            registryMutex.withLock {
                pending.remove(id)
            }
        }
    }

    internal suspend fun getServerResource(path: String): String {
        val response = httpClient.get(serverResourceUrl(path))
        val body = response.bodyAsText()
        check(response.status.isSuccess()) {
            "Server request failed with HTTP ${response.status.value}: $body"
        }
        return body
    }

    internal suspend fun postServerResource(path: String): String {
        val response = httpClient.post(serverResourceUrl(path))
        val body = response.bodyAsText()
        check(response.status.isSuccess()) {
            "Server request failed with HTTP ${response.status.value}: $body"
        }
        return body
    }

    internal suspend fun uploadArtifact(
        conversationId: Conversation.Id,
        upload: ArtifactUpload,
    ): String {
        val response = httpClient.post(serverResourceUrl("/api/artifacts")) {
            parameter("conversation_id", conversationId.value)
            parameter("file_name", upload.fileName)
            parameter("purpose", upload.purpose.name)
            contentType(ContentType.parse(upload.mediaType))
            setBody(upload.content)
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) {
            "Artifact upload failed with HTTP ${response.status.value}: $body"
        }
        return body
    }

    internal suspend fun getServerResourceBytes(path: String): ByteArray {
        val response = httpClient.get(serverResourceUrl(path))
        check(response.status.isSuccess()) {
            "Server request failed with HTTP ${response.status.value}: ${response.bodyAsText()}"
        }
        return response.body()
    }

    internal suspend fun deleteServerResource(path: String) {
        val response = httpClient.delete(serverResourceUrl(path))
        check(response.status.isSuccess()) {
            "Server request failed with HTTP ${response.status.value}: ${response.bodyAsText()}"
        }
    }

    private fun serverResourceUrl(path: String): String =
        "$serverHttpBaseUrl/${path.trimStart('/')}"

    fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long? = null,
    ): Flow<ConversationRuntimeEvent> = channelFlow {
        launch {
            observeConversationEvents(conversationId, afterEventSequence).collect { send(it) }
        }
        launch {
            observeState(ConversationRuntimeStateQuery(conversationId)).collect { snapshot ->
                val payload = snapshot.value as? ConversationRuntimeStatePayload
                    ?: error("Unexpected conversation runtime state payload: ${snapshot.value::class.simpleName}")
                send(
                    ConversationRuntimeEvent.SnapshotUpdated(
                        conversationId = conversationId,
                        snapshot = payload.snapshot,
                        cursorSequence = payload.snapshot.lastEventSequence,
                    )
                )
            }
        }
    }

    private fun observeConversationEvents(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
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
            log.info(
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
                    if (cursorSequence <= previousSequence) {
                        continue
                    }
                    registryMutex.withLock {
                        conversationEventSequences[conversationId] = cursorSequence
                    }
                }
                when (event) {
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

    private fun ServerPayload.cursorSequenceOrNull(): Long? =
        when (this) {
            is MessageUpsertedEvent -> cursorSequence
            is ConversationExecutionCompletedEvent -> cursorSequence
            is ConversationExecutionFailedEvent -> cursorSequence
            else -> null
        }

    internal fun observeState(
        query: RemoteStateSyncQuery,
    ): Flow<StateSyncSnapshot<RemoteStateSyncQuery, RemoteStateSyncPayload>> = flow {
        val subscriptionId = uuid7()
        val channel = Channel<ServerPayload>(Channel.CONFLATED)
        val subscription = RemoteStateSubscription(subscriptionId, query, channel)
        registryMutex.withLock {
            stateSubscriptions[subscriptionId] = subscription
        }

        runCatching {
            val connection = ensureConnected()
            if (!connection.newlyConnected) {
                sendObserveState(connection.session, subscription)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            scheduleReconnect()
        }

        try {
            val invalidations = flow {
                for (payload in channel) {
                    when (payload) {
                        is StateSyncInvalidatedEvent -> {
                            check(payload.query == query) {
                                "State sync invalidation query ${payload.query} does not match $query"
                            }
                            emit(StateSyncInvalidation(query, payload.cursor.toStateSyncCursor()))
                        }

                        is StateSyncObservationFailedEvent -> error(
                            "State sync observation failed for $query: ${payload.message}"
                        )

                        else -> Unit
                    }
                }
            }
            observeStateSync(
                key = query,
                invalidations = invalidations,
                pull = { invalidation -> pullState(query, invalidation.cursor) },
            ).collect { emit(it) }
        } finally {
            registryMutex.withLock {
                stateSubscriptions.remove(subscriptionId)
            }
            runCatching { sendIfConnected(StopObserveStateSyncCommand(subscriptionId)) }
            channel.close()
        }
    }

    private suspend fun pullState(
        query: RemoteStateSyncQuery,
        invalidationCursor: StateSyncCursor,
    ): StateSyncSnapshot<RemoteStateSyncQuery, RemoteStateSyncPayload> {
        while (true) {
            try {
                return pullStateOnce(query, invalidationCursor)
            } catch (error: RemoteConnectionLostException) {
                scheduleReconnect()
                val state = connectionState.first {
                    it.status == RemoteConnectionState.Status.CONNECTED ||
                        it.status == RemoteConnectionState.Status.CLOSED
                }
                if (state.status == RemoteConnectionState.Status.CLOSED) {
                    throw error
                }
            }
        }
    }

    private suspend fun pullStateOnce(
        query: RemoteStateSyncQuery,
        invalidationCursor: StateSyncCursor,
    ): StateSyncSnapshot<RemoteStateSyncQuery, RemoteStateSyncPayload> {
        val response = requestTyped<PullStateSyncRequest, StateSyncSnapshotResponse>(
            PullStateSyncRequest(query, invalidationCursor.toRemoteStateSyncCursor())
        )
        check(response.query == query) {
            "State sync response query ${response.query} does not match $query"
        }
        return StateSyncSnapshot(
            key = query,
            cursor = response.cursor.toStateSyncCursor(),
            value = response.state,
        )
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

    suspend fun startLiveVoiceProviderVad(
        request: StartLiveVoiceProviderVadRequest,
    ): LiveVoiceProviderVadClientSession {
        val response = requestTyped<StartLiveVoiceProviderVadRequest, LiveVoiceProviderVadStartedResponse>(request)
        val channel = Channel<ServerPayload>(Channel.UNLIMITED)
        registryMutex.withLock {
            liveVoiceProviderVadSessions[response.sessionId] = channel
        }
        return LiveVoiceProviderVadClientSession(response.sessionId, channel)
    }

    suspend fun sendLiveVoiceProviderVadAudioChunk(
        sessionId: String,
        chunk: RemotePcmAudioChunk,
    ) {
        send(LiveVoiceProviderVadAudioChunkCommand(sessionId, chunk))
    }

    suspend fun stopLiveVoiceProviderVad(sessionId: String) {
        send(StopLiveVoiceProviderVadCommand(sessionId))
    }

    fun closeLiveVoiceProviderVadSession(sessionId: String) {
        scope.launch {
            registryMutex.withLock {
                liveVoiceProviderVadSessions.remove(sessionId)
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
        log.info { "Protocol encoding=${encoding.name}" }
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
                log.info("Connected")
                sendEnvelopeRaw(
                    newSession,
                    GromozekaClientEnvelope(
                        id = uuid7(),
                        payload = RegisterClientSessionCommand(
                            clientInstanceId = clientInstanceId,
                            clientSessionId = clientSessionId,
                            platform = clientPlatform,
                        ),
                    ),
                )
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
                log.debug { "Incoming id=${envelope.id} type=${envelope.payload::class.simpleName}" }
                when (val payload = envelope.payload) {
                    is ServerResponse -> registryMutex.withLock { pending.remove(envelope.id) }?.complete(payload)
                    is MessageUpsertedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is ConversationExecutionCompletedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is ConversationExecutionFailedEvent -> routeConversationEvent(payload.subscriptionId, payload)
                    is StateSyncInvalidatedEvent -> routeStateEvent(payload.subscriptionId, payload)
                    is StateSyncObservationFailedEvent -> routeStateEvent(payload.subscriptionId, payload)
                    is ClientPresentationDirective -> _presentationDirectives.emit(payload)
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
                    is LiveVoiceProviderVadStatusEvent ->
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                    is LiveVoiceProviderVadSpeechStartedEvent ->
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                    is LiveVoiceProviderVadSpeechStoppedEvent ->
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                    is LiveVoiceProviderVadTranscriptDeltaEvent ->
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                    is LiveVoiceProviderVadTranscriptCompletedEvent ->
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                    is LiveVoiceProviderVadStoppedEvent -> {
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                        closeLiveVoiceProviderVadSession(payload.sessionId)
                    }
                    is LiveVoiceProviderVadFailedEvent -> {
                        routeLiveVoiceProviderVadEvent(payload.sessionId, payload)
                        closeLiveVoiceProviderVadSession(payload.sessionId)
                    }
                }
            }
            failure = IllegalStateException("Gromozeka WebSocket closed")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure = error
            log.warn(error, "Read loop failed")
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
        val subscriptions = registryMutex.withLock {
            ResumableSubscriptions(
                conversations = conversationSubscriptions.values.toList(),
                states = stateSubscriptions.values.toList(),
            )
        }
        subscriptions.conversations.forEach { subscription ->
            sendObserveConversationRaw(activeSession, subscription)
        }
        subscriptions.states.forEach { subscription ->
            sendObserveStateRaw(activeSession, subscription)
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

    private suspend fun sendObserveState(
        activeSession: DefaultClientWebSocketSession,
        subscription: RemoteStateSubscription,
    ) {
        sendEnvelope(
            activeSession,
            observeStateEnvelope(subscription),
        )
    }

    private suspend fun sendObserveStateRaw(
        activeSession: DefaultClientWebSocketSession,
        subscription: RemoteStateSubscription,
    ) {
        sendEnvelopeRaw(
            activeSession,
            observeStateEnvelope(subscription),
        )
    }

    private fun observeStateEnvelope(
        subscription: RemoteStateSubscription,
    ): GromozekaClientEnvelope = GromozekaClientEnvelope(
        id = uuid7(),
        payload = ObserveStateSyncCommand(
            subscriptionId = subscription.subscriptionId,
            query = subscription.query,
        ),
    )

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
        val activeLiveVoiceProviderVadSessions: List<Channel<ServerPayload>>
        registryMutex.withLock {
            pendingRequests = pending.values.toList()
            pending.clear()
            activeStreams = streams.values.toList()
            streams.clear()
            activeInterpreterSessions = liveInterpreterSessions.values.toList()
            liveInterpreterSessions.clear()
            activeLiveVoiceProviderVadSessions = liveVoiceProviderVadSessions.values.toList()
            liveVoiceProviderVadSessions.clear()
        }
        val connectionError = RemoteConnectionLostException(error)
        pendingRequests.forEach { it.completeExceptionally(connectionError) }
        activeStreams.forEach { it.close(error) }
        activeInterpreterSessions.forEach { it.close(error) }
        activeLiveVoiceProviderVadSessions.forEach { it.close(error) }
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
            conversationSubscriptions.isNotEmpty() ||
                stateSubscriptions.isNotEmpty()
        }

    private suspend fun routeConversationEvent(subscriptionId: String, payload: ServerPayload) {
        registryMutex.withLock {
            conversationSubscriptions[subscriptionId]?.channel
        }?.send(payload)
    }

    private suspend fun routeStateEvent(subscriptionId: String, payload: ServerPayload) {
        registryMutex.withLock {
            stateSubscriptions[subscriptionId]?.channel
        }?.send(payload)
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

    private suspend fun routeLiveVoiceProviderVadEvent(sessionId: String, payload: ServerPayload) {
        registryMutex.withLock {
            liveVoiceProviderVadSessions[sessionId]
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

    private data class RemoteStateSubscription(
        val subscriptionId: String,
        val query: RemoteStateSyncQuery,
        val channel: Channel<ServerPayload>,
    )

    private data class ResumableSubscriptions(
        val conversations: List<ConversationSubscription>,
        val states: List<RemoteStateSubscription>,
    )

    private companion object {
        const val RECONNECT_INITIAL_DELAY_MILLIS = 500L
        const val RECONNECT_MAX_DELAY_MILLIS = 10_000L
    }
}

private class RemoteConnectionLostException(
    cause: Throwable,
) : Exception("Gromozeka remote connection was lost", cause)

private fun RemoteStateSyncCursor.toStateSyncCursor(): StateSyncCursor = StateSyncCursor(
    sourceEpoch = sourceEpoch,
    streamEpoch = streamEpoch,
    generation = generation,
)

private fun StateSyncCursor.toRemoteStateSyncCursor(): RemoteStateSyncCursor = RemoteStateSyncCursor(
    sourceEpoch = sourceEpoch,
    streamEpoch = streamEpoch,
    generation = generation,
)

internal class LiveInterpreterClientSession(
    val sessionId: String,
    internal val channel: Channel<ServerPayload>,
)

internal class LiveVoiceProviderVadClientSession(
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
