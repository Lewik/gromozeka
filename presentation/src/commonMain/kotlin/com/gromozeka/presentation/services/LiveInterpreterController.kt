package com.gromozeka.presentation.services

import com.gromozeka.client.LiveInterpreterClient
import com.gromozeka.client.LiveInterpreterRemoteSession
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterNativeTranslationEvent
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal fun parseLiveLanguageHints(text: String): List<String> =
    text.split(Regex("[,;\\s]+"))
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .distinct()
        .also { languages ->
            require(languages.all { it.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")) }) {
                "Expected language codes such as he, en, ru"
            }
        }

private fun normalizeLiveLanguage(language: String): String = language.trim().lowercase().also {
    require(it.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
        "Expected a language code such as he, en, or ru"
    }
}

enum class LiveInterpreterPhase {
    IDLE, STARTING, LISTENING, RECONNECTING, STOPPING, PAUSED, STOPPED;

    val isActive: Boolean get() = this in setOf(STARTING, LISTENING, RECONNECTING, STOPPING)
}

data class LiveNativeTranslation(
    val text: String,
    val isFinal: Boolean,
    val targetLanguage: String,
    val sourceLanguage: String? = null,
    val speakerId: String? = null,
)

sealed interface LiveTranscriptEntry {
    val id: String

    data class Segment(
        override val id: String,
        val epoch: Int,
        val sequenceNumber: Int?,
        val text: String,
        val isFinal: Boolean,
        val speakerId: String? = null,
        val language: String? = null,
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val interrupted: Boolean = false,
    ) : LiveTranscriptEntry

    data class Gap(override val id: String, val message: LocalizedText) : LiveTranscriptEntry
}

data class LiveInterpreterState(
    val phase: LiveInterpreterPhase = LiveInterpreterPhase.IDLE,
    val languageHintsText: String = "he, en, ru",
    val selectedModelId: AiModelConfiguration.Id? = null,
    val manualTurnControl: Boolean = false,
    val commitPending: Boolean = false,
    val nativeTranslationLanguage: String? = null,
    val nativeTranslationLanguageExplicit: Boolean = false,
    val activeNativeTranslationLanguage: String? = null,
    val nativeTranslations: Map<String, LiveNativeTranslation> = emptyMap(),
    val entries: List<LiveTranscriptEntry> = emptyList(),
    val translationSessionId: String? = null,
    val status: LocalizedText = localizedText("interpreter.idle"),
)

class LiveInterpreterController(
    private val client: LiveInterpreterClient,
    private val audioStreamer: ClientLiveAudioStreamer,
    private val scope: CoroutineScope,
    private val reconnectDelaysMillis: List<Long> = listOf(1_500, 3_000, 6_000),
    initialTranslationLanguages: List<String> = emptyList(),
    initialNativeTranslationLanguage: String? = null,
) {
    private val log = KLoggers.logger(this)
    internal val translations = LiveTranslationController(client, scope, initialTranslationLanguages)
    private val mutableState = MutableStateFlow(LiveInterpreterState(
        nativeTranslationLanguage = initialNativeTranslationLanguage?.let(::normalizeLiveLanguage),
        translationSessionId = translations.state.value.sessionId,
    ))
    val state: StateFlow<LiveInterpreterState> = mutableState.asStateFlow()
    val unavailableReason: LocalizedText? get() = audioStreamer.unavailableReason
    private var task: Job? = null
    private var stopRequested = CompletableDeferred<Unit>()
    private var epoch = 0
    private val commitRequests = Channel<Unit>(Channel.RENDEZVOUS)

    fun setLanguages(text: String) {
        check(!state.value.phase.isActive) { "Cannot change languages during Live capture" }
        mutableState.update { it.copy(languageHintsText = text) }
    }

    fun selectModel(id: AiModelConfiguration.Id) {
        check(!state.value.phase.isActive) { "Cannot change the model during Live capture" }
        mutableState.update { it.copy(selectedModelId = id) }
    }

    fun setManualTurnControl(enabled: Boolean) {
        check(!state.value.phase.isActive) { "Cannot change Live boundaries during capture" }
        mutableState.update { it.copy(manualTurnControl = enabled) }
    }

    fun setNativeTranslationLanguage(language: String?) {
        check(!state.value.phase.isActive && state.value.phase != LiveInterpreterPhase.PAUSED) {
            "Cannot change Soniox translation for an active or resumable Live session"
        }
        mutableState.update { current -> current.copy(
            nativeTranslationLanguage = language?.let(::normalizeLiveLanguage),
            nativeTranslationLanguageExplicit = true,
        ) }
    }

    fun useInterfaceNativeTranslationLanguage(language: String) {
        val normalized = normalizeLiveLanguage(language)
        mutableState.update { current ->
            if (current.nativeTranslationLanguageExplicit) current else current.copy(nativeTranslationLanguage = normalized)
        }
    }

    fun commit() {
        while (true) {
            val current = state.value
            if (current.phase != LiveInterpreterPhase.LISTENING || !current.manualTurnControl || current.commitPending) return
            if (mutableState.compareAndSet(current, current.copy(commitPending = true))) break
        }
        if (commitRequests.trySend(Unit).isFailure) mutableState.update { it.copy(commitPending = false) }
    }

    fun start(selection: AiRuntimeSelection, nativeTranslationLanguage: String? = null) {
        check(!state.value.phase.isActive && task?.isActive != true) { "Live capture is already active" }
        val languages = parseLiveLanguageHints(state.value.languageHintsText)
        val manualTurnControl = state.value.manualTurnControl
        val nativeLanguage = nativeTranslationLanguage?.let(::normalizeLiveLanguage)
        val preserveHistory = state.value.phase == LiveInterpreterPhase.PAUSED
        if (preserveHistory) {
            check(state.value.activeNativeTranslationLanguage == nativeLanguage) {
                "Cannot change Soniox translation while resuming a Live session"
            }
        } else {
            translations.reset()
        }
        translations.setNativeLanguage(nativeLanguage)
        stopRequested = CompletableDeferred()
        mutableState.update { it.copy(
            phase = LiveInterpreterPhase.STARTING,
            selectedModelId = selection.modelConfigurationId,
            commitPending = false,
            activeNativeTranslationLanguage = nativeLanguage,
            nativeTranslations = if (preserveHistory) it.nativeTranslations else emptyMap(),
            entries = if (preserveHistory) it.entries else emptyList(),
            translationSessionId = translations.state.value.sessionId,
            status = localizedText("interpreter.starting"),
        ) }
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var attempts = 0
                while (isActive && !stopRequested.isCompleted) {
                    epoch += 1
                    try {
                        unavailableReason?.let { throw LocalizedTextException(it) }
                        runPipeline(selection, languages, manualTurnControl, nativeLanguage, epoch) { attempts = 0 }
                        return@launch
                    } catch (error: Exception) {
                        if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                        if (stopRequested.isCompleted) throw error
                        recordFailure(error)
                        log.warn { "Live stage stopped: type=${error::class.simpleName}" }
                        val retryable = (error as? LiveTranscriptionException)?.retryable == true || error is TimeoutCancellationException
                        if (!retryable || attempts >= reconnectDelaysMillis.size) {
                            mutableState.update { it.copy(phase = LiveInterpreterPhase.PAUSED) }
                            return@launch
                        }
                        val delay = reconnectDelaysMillis[attempts++]
                        mutableState.update { it.copy(phase = LiveInterpreterPhase.RECONNECTING,
                            status = localizedText("interpreter.reconnecting", "attempt" to attempts)) }
                        if (withTimeoutOrNull(delay) { stopRequested.await() } != null) return@launch
                    }
                }
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                recordFailure(error, pause = true)
            } catch (error: Exception) {
                recordFailure(error, pause = true)
            } finally {
                if (stopRequested.isCompleted && state.value.phase != LiveInterpreterPhase.PAUSED) {
                    translations.finish()
                    mutableState.update { it.copy(phase = LiveInterpreterPhase.STOPPED, status = localizedText("interpreter.stopped")) }
                }
            }
        }
        task?.start()
    }

    fun cancel() {
        translations.cancel()
        commitRequests.close()
        stopRequested.complete(Unit)
        task?.cancel()
        if (!state.value.phase.isActive) mutableState.update {
            it.copy(phase = LiveInterpreterPhase.STOPPED, status = localizedText("interpreter.stopped"))
        }
    }

    fun stop() {
        if (state.value.phase == LiveInterpreterPhase.PAUSED) {
            translations.finish()
            mutableState.update { it.copy(phase = LiveInterpreterPhase.STOPPED, status = localizedText("interpreter.stopped")) }
            return
        }
        if (!state.value.phase.isActive) return
        val cancelStarting = state.value.phase != LiveInterpreterPhase.LISTENING
        mutableState.update { it.copy(phase = LiveInterpreterPhase.STOPPING, status = localizedText("interpreter.flushingAudio")) }
        stopRequested.complete(Unit)
        if (cancelStarting) task?.cancel()
    }

    private suspend fun runPipeline(
        selection: AiRuntimeSelection,
        languages: List<String>,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
        epoch: Int,
        onFinalTranscript: () -> Unit,
    ) {
        var remote: LiveInterpreterRemoteSession? = null
        var audio: ClientLiveAudioStreamingSession? = null
        try {
            coroutineScope {
                translations.beginEpoch(epoch)
                val connected = withTimeout(25_000) {
                    client.start(languages, selection, manualTurnControl, nativeTranslationLanguage)
                }
                remote = connected
                val ready = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                val commitAcknowledgement = MutableStateFlow<CompletableDeferred<Unit>?>(null)
                val reader = launch {
                    connected.events.collect { event ->
                        when (event) {
                            is LiveInterpreterStatusEvent -> {
                                check(event.sessionId == connected.sessionId) { "Live status session mismatch" }
                                if (event.messageKey == "interpreter.status.listening") ready.complete(Unit)
                                else if (event.messageKey in setOf("interpreter.commitSent", "interpreter.nothingToCommit")) {
                                    val acknowledgement = commitAcknowledgement.value
                                    check(acknowledgement != null || stopRequested.isCompleted) { "Unrequested Live commit status" }
                                    acknowledgement?.complete(Unit)
                                    mutableState.update { current ->
                                        if (current.phase == LiveInterpreterPhase.LISTENING && !stopRequested.isCompleted)
                                            current.copy(status = localizedText(event.messageKey)) else current
                                    }
                                }
                            }
                            is LiveInterpreterTranscriptEvent -> {
                                check(event.sessionId == connected.sessionId) { "Live transcript session mismatch" }
                                acceptTranscript(connected.sessionId, epoch, event)
                                if (event.isFinal) {
                                    translations.acceptFinal(epoch, requireNotNull(event.sequenceNumber),
                                        "${connected.sessionId}/${event.segmentId}", event.text, event.speakerId)
                                    onFinalTranscript()
                                }
                            }
                            is LiveInterpreterNativeTranslationEvent -> {
                                check(event.sessionId == connected.sessionId) { "Live native translation session mismatch" }
                                acceptNativeTranslation(connected.sessionId, event)
                            }
                            is LiveInterpreterFailedEvent -> {
                                check(event.sessionId == connected.sessionId) { "Live failure session mismatch" }
                                throw LiveTranscriptionException(event.message, event.retryable)
                            }
                            is LiveInterpreterStoppedEvent -> {
                                check(event.sessionId == connected.sessionId) { "Live stop session mismatch" }
                                if (!stopRequested.isCompleted) throw LiveTranscriptionException("Live stream ended unexpectedly", retryable = true)
                                stopped.complete(Unit)
                            }
                            else -> error("Unexpected Live event: ${event::class.simpleName}")
                        }
                    }
                    if (!stopped.isCompleted) throw LiveTranscriptionException("Live connection ended without a stop event", retryable = true)
                }
                val canCapture = withTimeout(25_000) {
                    select<Boolean> {
                        ready.onAwait { true }
                        stopRequested.onAwait { false }
                    }
                }
                if (canCapture && !stopRequested.isCompleted) {
                    val controls = if (manualTurnControl) launch(start = CoroutineStart.UNDISPATCHED) {
                        for (request in commitRequests) {
                            val acknowledged = CompletableDeferred<Unit>()
                            commitAcknowledgement.value = acknowledged
                            try {
                                withTimeout(15_000) {
                                    connected.commit()
                                    acknowledged.await()
                                }
                            } catch (error: TimeoutCancellationException) {
                                if (!currentCoroutineContext().isActive) throw error
                                throw LiveTranscriptionException("Live commit acknowledgement timed out", retryable = true, cause = error)
                            } finally {
                                commitAcknowledgement.value = null
                                mutableState.update { it.copy(commitPending = false) }
                            }
                        }
                    } else null
                    try {
                        audio = audioStreamer.start(this) { connected.sendAudioChunk(it) }
                        mutableState.update { it.copy(phase = LiveInterpreterPhase.LISTENING, status = localizedText("interpreter.status.listening")) }
                        stopRequested.await()
                    } finally {
                        controls?.cancelAndJoin()
                        mutableState.update { it.copy(commitPending = false) }
                    }
                }
                withTimeout(20_000) {
                    audio?.stop()
                    connected.stop()
                    stopped.await()
                    reader.join()
                    translations.finish()
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    audio?.cancel()
                } finally {
                    withTimeout(3_000) { remote?.close() }
                }
            }
        }
    }

    private fun recordFailure(error: Exception, pause: Boolean = false) {
        val message = localizedText("interpreter.processingFailed", "error" to error.localizedText())
        val gap = LiveTranscriptEntry.Gap(uuid7(), message)
        translations.gap(gap.id, message)
        mutableState.update { previous -> previous.copy(
            phase = if (pause) LiveInterpreterPhase.PAUSED else previous.phase,
            entries = previous.entries.map { entry ->
                if (entry is LiveTranscriptEntry.Segment && !entry.isFinal) entry.copy(interrupted = true) else entry
            } + gap,
            status = message,
        ) }
    }

    private fun acceptNativeTranslation(remoteId: String, event: LiveInterpreterNativeTranslationEvent) {
        val id = "$remoteId/${event.segmentId}"
        mutableState.update { previous ->
            check(previous.activeNativeTranslationLanguage == event.targetLanguage) {
                "Live native translation target does not match the active target"
            }
            val source = previous.entries.filterIsInstance<LiveTranscriptEntry.Segment>().firstOrNull { it.id == id }
            check(source == null || source.sequenceNumber == event.sequenceNumber) {
                "Live native translation position does not match its source"
            }
            val existing = previous.nativeTranslations[id]
            check(existing?.isFinal != true) { "A completed Live native translation was modified" }
            previous.copy(nativeTranslations = previous.nativeTranslations + (id to LiveNativeTranslation(
                text = event.text,
                isFinal = event.isFinal,
                targetLanguage = event.targetLanguage,
                sourceLanguage = event.sourceLanguage,
                speakerId = event.speakerId,
            )))
        }
    }

    private fun acceptTranscript(remoteId: String, epoch: Int, event: LiveInterpreterTranscriptEvent) {
        val id = "$remoteId/${event.segmentId}"
        mutableState.update { previous ->
            val existing = previous.entries.filterIsInstance<LiveTranscriptEntry.Segment>().firstOrNull { it.id == id }
            check(existing?.isFinal != true) { "A completed Live transcript was modified" }
            if (event.isFinal && event.text.isBlank()) return@update previous.copy(entries = previous.entries.filterNot { it.id == id })
            val segment = LiveTranscriptEntry.Segment(
                id = id,
                epoch = epoch,
                sequenceNumber = event.sequenceNumber,
                text = event.text,
                isFinal = event.isFinal,
                speakerId = event.speakerId,
                language = event.language,
                startMillis = event.startMillis,
                endMillis = event.endMillis,
            )
            val prefix = previous.entries.filter { it !is LiveTranscriptEntry.Segment || it.epoch != epoch }
            val current = previous.entries.filterIsInstance<LiveTranscriptEntry.Segment>()
                .filter { it.epoch == epoch && it.id != id } + segment
            val ordered = current.sortedWith { a, b ->
                when {
                    a.sequenceNumber == b.sequenceNumber -> 0
                    a.sequenceNumber == null -> 1
                    b.sequenceNumber == null -> -1
                    else -> a.sequenceNumber.compareTo(b.sequenceNumber)
                }
            }
            previous.copy(entries = prefix + ordered)
        }
    }
}
