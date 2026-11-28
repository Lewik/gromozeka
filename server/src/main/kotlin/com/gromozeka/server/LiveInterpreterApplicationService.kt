package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionProvider
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.remote.protocol.LiveInterpreterAudioChunkCommand
import com.gromozeka.remote.protocol.CommitLiveInterpreterCommand
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterNativeTranslationEvent
import com.gromozeka.remote.protocol.LiveInterpreterStartedResponse
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.RemotePcmByteOrder
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

internal class LiveInterpreterSessionOwner(val userId: User.Id, val connectionId: String) {
    internal val lock = Any()
    internal var closed = false
    internal val canceledStarts = mutableSetOf<String>()

    init {
        require(connectionId.isNotBlank()) { "Live connection id must not be blank" }
    }
}

private sealed interface LiveInterpreterInput {
    data class Audio(val chunk: RemotePcmAudioChunk) : LiveInterpreterInput
    data object Commit : LiveInterpreterInput
}

@Service
class LiveInterpreterApplicationService(
    private val transcriptionProvider: LiveTranscriptionProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val sessions = ConcurrentHashMap<String, Session>()

    internal fun start(
        owner: LiveInterpreterSessionOwner,
        request: StartLiveInterpreterRequest,
        eventSink: suspend (ServerPayload) -> Unit,
    ): LiveInterpreterStartedResponse = synchronized(owner.lock) {
        check(!owner.closed) { "Live connection owner is closed" }
        check(request.sessionId !in owner.canceledStarts) { "Live start was canceled" }
        val session = Session(owner, request, eventSink)
        check(sessions.putIfAbsent(request.sessionId, session) == null) { "Live session id is already in use" }
        try {
            check(session.start()) { "Live processing scope is no longer active" }
        } catch (error: Throwable) {
            sessions.remove(request.sessionId, session)
            throw error
        }
        LiveInterpreterStartedResponse(request.sessionId)
    }

    internal fun append(owner: LiveInterpreterSessionOwner, command: LiveInterpreterAudioChunkCommand): Boolean = synchronized(owner.lock) {
        if (owner.closed) return@synchronized false
        sessions[command.sessionId]?.takeIf { it.owner === owner }?.append(command.chunk) ?: false
    }

    internal fun commit(owner: LiveInterpreterSessionOwner, command: CommitLiveInterpreterCommand): Boolean = synchronized(owner.lock) {
        if (owner.closed) return@synchronized false
        sessions[command.sessionId]?.takeIf { it.owner === owner }?.commit() ?: false
    }

    internal fun stop(owner: LiveInterpreterSessionOwner, command: StopLiveInterpreterCommand): Boolean = synchronized(owner.lock) {
        if (owner.closed) return@synchronized false
        // Stop may overtake its Start RPC while that RPC is still being authorized.
        if (owner.canceledStarts.size >= 1_024 && command.sessionId !in owner.canceledStarts) {
            owner.closed = true
            owner.canceledStarts.clear()
            sessions.values.filter { it.owner === owner }.forEach {
                it.abort(LiveTranscriptionException("Live cancellation limit reached; reconnect the client to start again", retryable = false))
            }
            return@synchronized false
        }
        owner.canceledStarts += command.sessionId
        sessions[command.sessionId]?.takeIf { it.owner === owner }?.stop() ?: false
    }

    internal suspend fun stopOwnedBy(owner: LiveInterpreterSessionOwner): Int {
        val owned = synchronized(owner.lock) {
            owner.closed = true
            owner.canceledStarts.clear()
            sessions.values.filter { it.owner === owner }.filter { sessions.remove(it.id, it) }
        }
        owned.forEach { it.cancel() }
        return owned.size
    }

    private inner class Session(
        val owner: LiveInterpreterSessionOwner,
        val request: StartLiveInterpreterRequest,
        private val eventSink: suspend (ServerPayload) -> Unit,
    ) {
        val id = request.sessionId
        private val input = Channel<LiveInterpreterInput>(64)
        private val accepting = AtomicBoolean(true)
        private val listening = AtomicBoolean(false)
        private val inputFailure = AtomicReference<Exception?>(null)
        private val emitMutex = Mutex()
        private lateinit var job: Job

        fun start(): Boolean {
            job = scope.launch(start = CoroutineStart.LAZY) { run() }
            return job.start()
        }

        fun abort(error: Exception) {
            if (inputFailure.compareAndSet(null, error)) {
                accepting.set(false)
                job.cancel()
            }
        }

        fun append(chunk: RemotePcmAudioChunk): Boolean {
            if (!accepting.get()) return false
            if (chunk.data.size > 32_000) {
                abort(IllegalArgumentException("Live microphone chunks must not exceed one second of PCM audio"))
                return false
            }
            return enqueue(LiveInterpreterInput.Audio(chunk))
        }

        fun commit(): Boolean = enqueue(LiveInterpreterInput.Commit)

        private fun enqueue(value: LiveInterpreterInput): Boolean {
            if (!accepting.get()) return false
            val result = input.trySend(value)
            if (result.isSuccess) return true
            if (!result.isClosed) abort(LiveTranscriptionException("Live audio buffer overflow; some audio was lost", retryable = true))
            return false
        }

        fun stop(): Boolean {
            if (!accepting.compareAndSet(true, false)) return false
            input.close()
            if (!listening.get()) {
                sessions.remove(id, this)
                job.cancel()
            }
            return true
        }

        suspend fun cancel() {
            accepting.set(false)
            input.cancel()
            job.cancelAndJoin()
        }

        private suspend fun emit(payload: ServerPayload) = emitMutex.withLock { eventSink(payload) }

        private suspend fun run() {
            var upstream: LiveTranscriptionSession? = null
            var outcome = AiAdmissionOutcome.CANCELLED
            var failure: Exception? = null
            try {
                val selection = request.transcriptionRuntimeSelection
                    ?: aiConfigurationProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION)
                val connection = transcriptionProvider.open(owner.userId.value, selection, request.languages,
                    request.manualTurnControl, request.nativeTranslationLanguage)
                upstream = connection
                check(!request.manualTurnControl || connection.turnDetection == LiveTranscriptionTurnDetection.CLIENT) {
                    "Manual Live input requires client-owned turn boundaries"
                }
                listening.set(true)
                if (!accepting.get()) return
                emit(LiveInterpreterStatusEvent(id, "interpreter.status.listening"))
                coroutineScope {
                    val timeline = LiveTranscriptTimeline(id)
                    val collector = launch {
                        connection.events.collect { event -> timeline.accept(event).forEach { emit(it) } }
                        if (accepting.get()) throw LiveTranscriptionException("Live transcription stream ended unexpectedly", retryable = true)
                    }
                    val detector = if (!request.manualTurnControl && connection.turnDetection == LiveTranscriptionTurnDetection.CLIENT)
                        LiveAudioTurnDetector() else null
                    var nextSequence = 0
                    var hasUncommittedAudio = false
                    for (value in input) {
                        when (value) {
                            is LiveInterpreterInput.Audio -> {
                                val chunk = value.chunk
                                require(chunk.sequenceNumber == nextSequence) { "Live audio chunk sequence is not contiguous" }
                                require(chunk.sampleRate == 16_000 && chunk.channels == 1 && chunk.byteOrder == RemotePcmByteOrder.BIG_ENDIAN) {
                                    "Live requires mono 16 kHz big-endian PCM16 microphone audio"
                                }
                                nextSequence += 1
                                connection.appendAudio(chunk.data)
                                if (chunk.data.isNotEmpty()) hasUncommittedAudio = true
                                if (detector?.accept(chunk.data) == true) {
                                    connection.commit()
                                    hasUncommittedAudio = false
                                }
                            }
                            LiveInterpreterInput.Commit -> {
                                check(request.manualTurnControl) { "Manual commit is not enabled for this Live session" }
                                if (hasUncommittedAudio) {
                                    connection.commit()
                                    hasUncommittedAudio = false
                                    emit(LiveInterpreterStatusEvent(id, "interpreter.commitSent"))
                                } else emit(LiveInterpreterStatusEvent(id, "interpreter.nothingToCommit"))
                            }
                        }
                    }
                    connection.finishInput()
                    collector.join()
                    timeline.requireDrained()
                }
                outcome = AiAdmissionOutcome.SUCCEEDED
            } catch (error: CancellationException) {
                failure = inputFailure.get()
                if (failure != null) {
                    outcome = AiAdmissionOutcome.FAILED
                    throw error
                }
                if (!currentCoroutineContext().isActive) throw error
                failure = LiveTranscriptionException("Live transcription timed out", retryable = true, cause = error)
                outcome = AiAdmissionOutcome.FAILED
            } catch (error: Exception) {
                failure = error
                outcome = AiAdmissionOutcome.FAILED
            } finally {
                accepting.set(false)
                input.cancel()
                withContext(NonCancellable) {
                    var terminalFailure = failure
                    try {
                        upstream?.close(outcome)
                    } catch (error: Exception) {
                        terminalFailure = terminalFailure ?: error
                    } finally {
                        sessions.remove(id, this@Session)
                    }
                    terminalFailure?.let { error ->
                        log.warn { "Live transcription stage failed: session=$id type=${error::class.simpleName}" }
                        emitTerminalSafely(LiveInterpreterFailedEvent(
                            sessionId = id,
                            message = error.message ?: "Live transcription failed",
                            retryable = (error as? LiveTranscriptionException)?.retryable == true,
                        ))
                    }
                    emitTerminalSafely(LiveInterpreterStoppedEvent(id))
                }
            }
        }

        private suspend fun emitTerminalSafely(payload: ServerPayload) {
            try {
                withTimeout(2_000) { emit(payload) }
            } catch (error: Exception) {
                log.warn { "Live terminal event could not be delivered: session=$id type=${error::class.simpleName}" }
            }
        }
    }
}

internal class LiveTranscriptTimeline(private val sessionId: String) {
    private val positions = mutableMapOf<String, Int>()
    private val latest = mutableMapOf<String, LiveTranscriptionEvent.Transcript>()
    private val latestTranslations = mutableMapOf<String, LiveTranscriptionEvent.Translation>()
    private var predecessor: String? = null

    fun accept(event: LiveTranscriptionEvent): List<ServerPayload> = when (event) {
        is LiveTranscriptionEvent.Committed -> {
            check(event.itemId !in positions) { "Live turn was committed twice" }
            check(event.previousItemId == predecessor) { "Live committed turn predecessor is inconsistent" }
            positions[event.itemId] = positions.size
            predecessor = event.itemId
            buildList {
                latest[event.itemId]?.let { add(project(it)) }
                latestTranslations[event.itemId]?.let { add(project(it)) }
            }
        }
        is LiveTranscriptionEvent.Transcript -> {
            val previous = latest[event.itemId]
            check(previous?.isFinal != true) { "A completed Live transcript was modified" }
            latest[event.itemId] = event
            if (event.isFinal && event.itemId !in positions) emptyList() else listOf(project(event))
        }
        is LiveTranscriptionEvent.Translation -> {
            val previous = latestTranslations[event.itemId]
            check(previous?.isFinal != true) { "A completed Live native translation was modified" }
            latestTranslations[event.itemId] = event
            if (event.itemId !in positions) emptyList() else listOf(project(event))
        }
    }

    private fun project(event: LiveTranscriptionEvent.Transcript) = LiveInterpreterTranscriptEvent(
        sessionId = sessionId,
        segmentId = event.itemId,
        sequenceNumber = positions[event.itemId],
        text = event.text,
        isFinal = event.isFinal,
        speakerId = event.speakerId,
        language = event.language,
        startMillis = event.startMillis,
        endMillis = event.endMillis,
    )

    private fun project(event: LiveTranscriptionEvent.Translation) = LiveInterpreterNativeTranslationEvent(
        sessionId = sessionId,
        segmentId = event.itemId,
        sequenceNumber = checkNotNull(positions[event.itemId]) { "Live native translation has no committed source" },
        text = event.text,
        isFinal = event.isFinal,
        targetLanguage = event.targetLanguage,
        sourceLanguage = event.sourceLanguage,
        speakerId = event.speakerId,
    )

    fun requireDrained() {
        check(latest.values.none { !it.isFinal }) { "Live stream ended with an incomplete transcript" }
        check(latest.keys.all { it in positions }) { "Live stream ended with an uncommitted transcript" }
        check(latestTranslations.values.none { !it.isFinal }) { "Live stream ended with an incomplete native translation" }
        check(latestTranslations.keys.all { it in positions }) { "Live stream ended with an uncommitted native translation" }
    }
}

internal class LiveAudioTurnDetector(
    private val silenceMillis: Long = 1_500,
    private val maximumTurnMillis: Long = 20_000,
    private val speechThreshold: Double = 0.008,
) {
    private var samples = 0L
    private var quietSamples = 0L
    private var heardSpeech = false

    fun accept(pcm: ByteArray): Boolean {
        require(pcm.size % 2 == 0) { "Live PCM16 frames must be complete" }
        if (pcm.isEmpty()) return false
        val count = pcm.size / 2
        var sum = 0.0
        for (index in pcm.indices step 2) {
            val sample = (((pcm[index].toInt() and 0xff) shl 8) or (pcm[index + 1].toInt() and 0xff)).toShort()
            val normalized = sample.toDouble() / 32768.0
            sum += normalized * normalized
        }
        val speech = sqrt(sum / count) >= speechThreshold
        samples += count
        if (speech) {
            heardSpeech = true
            quietSamples = 0
        } else {
            quietSamples += count
        }
        val boundary = samples * 1000 / 16_000 >= maximumTurnMillis ||
            (heardSpeech && quietSamples * 1000 / 16_000 >= silenceMillis)
        if (boundary) {
            samples = 0
            quietSamples = 0
            heardSpeech = false
        }
        return boundary
    }
}
