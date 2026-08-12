package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.infrastructure.ai.openai.OpenAiRealtimePcmByteOrder
import com.gromozeka.infrastructure.ai.openai.OpenAiRealtimeTranscriptionEvent
import com.gromozeka.infrastructure.ai.openai.OpenAiRealtimeTranscriptionService
import com.gromozeka.infrastructure.ai.openai.OpenAiRealtimeTranscriptionSession
import com.gromozeka.remote.protocol.LiveVoiceProviderVadAudioChunkCommand
import com.gromozeka.remote.protocol.LiveVoiceProviderVadAvailabilityResponse
import com.gromozeka.remote.protocol.LiveVoiceProviderVadFailedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStartedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadSpeechStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStartedResponse
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStatusEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadStoppedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptCompletedEvent
import com.gromozeka.remote.protocol.LiveVoiceProviderVadTranscriptDeltaEvent
import com.gromozeka.remote.protocol.RemotePcmByteOrder
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveVoiceProviderVadRequest
import com.gromozeka.remote.protocol.StopLiveVoiceProviderVadCommand
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal data class LiveVoiceProviderVadSessionOwner(
    val userId: User.Id,
    val connectionId: String,
) {
    init {
        require(connectionId.isNotBlank()) { "Live voice provider VAD connection id must not be blank" }
    }
}

@Service
class LiveVoiceProviderVadApplicationService(
    private val openAiRealtimeTranscriptionService: OpenAiRealtimeTranscriptionService,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val sessions = ConcurrentHashMap<String, LiveVoiceProviderVadSession>()

    internal fun availability(): LiveVoiceProviderVadAvailabilityResponse =
        LiveVoiceProviderVadAvailabilityResponse(
            unavailableReason = openAiRealtimeTranscriptionService.availabilityFailure(),
        )

    internal suspend fun start(
        owner: LiveVoiceProviderVadSessionOwner,
        user: User,
        request: StartLiveVoiceProviderVadRequest,
        eventSink: suspend (ServerPayload) -> Unit,
    ): LiveVoiceProviderVadStartedResponse {
        require(owner.userId == user.id) { "Live voice provider VAD owner does not match authenticated user" }
        val sessionId = uuid7()
        val openAiSession = openAiRealtimeTranscriptionService.start(
            userStableId = user.id.value,
            languageCode = request.languageCode,
            prompt = request.prompt,
        )
        val session = LiveVoiceProviderVadSession(
            sessionId = sessionId,
            owner = owner,
            openAiSession = openAiSession,
            eventSink = eventSink,
        )
        sessions[sessionId] = session
        session.start()
        log.info { "Live voice provider VAD started: session=$sessionId user=${user.id.value}" }
        return LiveVoiceProviderVadStartedResponse(sessionId)
    }

    internal suspend fun append(
        owner: LiveVoiceProviderVadSessionOwner,
        command: LiveVoiceProviderVadAudioChunkCommand,
    ): Boolean {
        val session = sessions[command.sessionId]
        if (session == null || session.owner != owner) {
            log.warn { "Live voice provider VAD chunk ignored for inaccessible session=${command.sessionId}" }
            return false
        }
        return session.append(command)
    }

    internal suspend fun stop(
        owner: LiveVoiceProviderVadSessionOwner,
        command: StopLiveVoiceProviderVadCommand,
    ): Boolean {
        val session = sessions[command.sessionId]
        if (session == null || session.owner != owner) {
            log.warn { "Live voice provider VAD stop ignored for inaccessible session=${command.sessionId}" }
            return false
        }
        return session.stop()
    }

    internal suspend fun stopOwnedBy(owner: LiveVoiceProviderVadSessionOwner): Int {
        val ownedSessions = sessions.values
            .filter { it.owner == owner }
            .filter { sessions.remove(it.sessionId, it) }
        ownedSessions.forEach { it.cancel() }
        return ownedSessions.size
    }

    private inner class LiveVoiceProviderVadSession(
        val sessionId: String,
        val owner: LiveVoiceProviderVadSessionOwner,
        private val openAiSession: OpenAiRealtimeTranscriptionSession,
        private val eventSink: suspend (ServerPayload) -> Unit,
    ) {
        private val emitMutex = Mutex()
        private val acceptingInput = AtomicBoolean(true)
        private var terminalEventEmitted = false
        private lateinit var job: Job

        fun start() {
            job = scope.launch {
                try {
                    openAiSession.events.collect { event ->
                        val payload = event.toRemoteEvent()
                        if (payload is LiveVoiceProviderVadStoppedEvent || payload is LiveVoiceProviderVadFailedEvent) {
                            terminalEventEmitted = true
                        }
                        emit(payload)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) {
                        "Live voice provider VAD failed: session=$sessionId error=${error.message}"
                    }
                    emit(
                        LiveVoiceProviderVadFailedEvent(
                            sessionId = sessionId,
                            message = error.message ?: "Provider VAD failed",
                        )
                    )
                } finally {
                    acceptingInput.set(false)
                    sessions.remove(sessionId, this@LiveVoiceProviderVadSession)
                    if (coroutineContext[Job]?.isActive == true && !terminalEventEmitted) {
                        emit(LiveVoiceProviderVadStoppedEvent(sessionId))
                    }
                }
            }
        }

        suspend fun append(command: LiveVoiceProviderVadAudioChunkCommand): Boolean {
            if (!acceptingInput.get()) return false
            val chunk = command.chunk
            openAiSession.appendPcm16(
                pcm = chunk.data,
                sampleRate = chunk.sampleRate,
                channels = chunk.channels,
                byteOrder = when (chunk.byteOrder) {
                    RemotePcmByteOrder.BIG_ENDIAN -> OpenAiRealtimePcmByteOrder.BIG_ENDIAN
                    RemotePcmByteOrder.LITTLE_ENDIAN -> OpenAiRealtimePcmByteOrder.LITTLE_ENDIAN
                },
            )
            return true
        }

        suspend fun stop(): Boolean {
            if (!acceptingInput.compareAndSet(true, false)) return false
            openAiSession.stop()
            job.cancelAndJoin()
            sessions.remove(sessionId, this)
            return true
        }

        suspend fun cancel() {
            acceptingInput.set(false)
            runCatching { openAiSession.stop() }
            job.cancelAndJoin()
        }

        private fun OpenAiRealtimeTranscriptionEvent.toRemoteEvent(): ServerPayload =
            when (this) {
                is OpenAiRealtimeTranscriptionEvent.Status ->
                    LiveVoiceProviderVadStatusEvent(sessionId, message)
                OpenAiRealtimeTranscriptionEvent.SpeechStarted ->
                    LiveVoiceProviderVadSpeechStartedEvent(sessionId)
                OpenAiRealtimeTranscriptionEvent.SpeechStopped ->
                    LiveVoiceProviderVadSpeechStoppedEvent(sessionId)
                is OpenAiRealtimeTranscriptionEvent.TranscriptDelta ->
                    LiveVoiceProviderVadTranscriptDeltaEvent(sessionId, itemId, delta)
                is OpenAiRealtimeTranscriptionEvent.TranscriptCompleted ->
                    LiveVoiceProviderVadTranscriptCompletedEvent(sessionId, itemId, text)
                is OpenAiRealtimeTranscriptionEvent.Failed ->
                    LiveVoiceProviderVadFailedEvent(sessionId, message)
                OpenAiRealtimeTranscriptionEvent.Stopped ->
                    LiveVoiceProviderVadStoppedEvent(sessionId)
            }

        private suspend fun emit(payload: ServerPayload) {
            try {
                emitMutex.withLock {
                    eventSink(payload)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn(error) {
                    "Live voice provider VAD event send failed: session=$sessionId error=${error.message}"
                }
            }
        }
    }
}
