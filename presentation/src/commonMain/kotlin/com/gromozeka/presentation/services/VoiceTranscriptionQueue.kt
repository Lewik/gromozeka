package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.remote.protocol.RemoteAudioRecording
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceTranscriptionQueue(
    private val transcriptionService: AudioTranscriptionService,
    private val delivery: VoiceInputDelivery,
    private val settingsService: SettingsService,
    private val uiFeedbackController: UiFeedbackController,
    private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val queues = mutableMapOf<QueueKey, ArrayDeque<PendingTranscription>>()
    private val running = mutableSetOf<QueueKey>()
    private var activeMode: QueueMode? = null

    suspend fun submit(recording: RemoteAudioRecording, target: VoiceInputTarget) {
        val (key, start) = mutex.withLock {
            val mode = activeMode ?: configuredMode().also { activeMode = it }
            val key = mode.key(target)
            queues.getOrPut(key, ::ArrayDeque).addLast(PendingTranscription(recording, target))
            key to running.add(key)
        }
        delivery.transcriptionStarted(recording.sessionId, target)
        if (start) scope.launch { drain(key) }
    }

    private suspend fun drain(key: QueueKey) {
        try {
            while (true) {
                val pending = mutex.withLock { queues[key]?.removeFirstOrNull() } ?: return
                process(pending)
            }
        } finally {
            val restart = mutex.withLock {
                check(running.remove(key)) { "Voice transcription queue was not running" }
                val queue = queues[key]
                when {
                    queue.isNullOrEmpty() -> {
                        queues.remove(key)
                        resetModeIfIdle()
                        false
                    }
                    !scope.isActive -> {
                        queues.remove(key)
                        resetModeIfIdle()
                        false
                    }
                    else -> {
                        running.add(key)
                        true
                    }
                }
            }
            if (restart) scope.launch { drain(key) }
        }
    }

    private suspend fun process(pending: PendingTranscription) {
        var failure: LocalizedText? = null
        try {
            val text = transcriptionService.transcribe(pending.recording).trim()
            if (text.isBlank()) {
                failure = localizedText("voice.emptyTranscript")
                log.info { "Voice transcription returned blank text: session=${pending.recording.sessionId}" }
            } else if (!delivery.deliver(pending.target, text)) {
                failure = localizedText("voice.noConversation")
                log.warn {
                    "Voice transcription target is unavailable: " +
                        "session=${pending.recording.sessionId} textChars=${text.length}"
                }
            } else {
                log.info {
                    "Voice transcription delivered: session=${pending.recording.sessionId} textChars=${text.length}"
                }
            }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            failure = localizedText("voice.recognitionFailed", "error" to error.localizedText())
            log.warn(error) {
                "Voice transcription was cancelled by its request: " +
                    "session=${pending.recording.sessionId} error=${error.message}"
            }
        } catch (error: Throwable) {
            failure = localizedText("voice.recognitionFailed", "error" to error.localizedText())
            log.warn(error) {
                "Voice transcription failed: session=${pending.recording.sessionId} error=${error.message}"
            }
        } finally {
            delivery.transcriptionFinished(pending.recording.sessionId, failure)
        }
        if (failure != null) uiFeedbackController.notifyError()
    }

    private fun configuredMode(): QueueMode =
        if (settingsService.userDeviceSettings.voiceInputSettings.allowIndependentTranscriptionAcrossTabs) {
            QueueMode.INDEPENDENT_TABS
        } else {
            QueueMode.GLOBAL
        }

    private fun resetModeIfIdle() {
        if (queues.isEmpty() && running.isEmpty()) activeMode = null
    }

    private data class PendingTranscription(
        val recording: RemoteAudioRecording,
        val target: VoiceInputTarget,
    )

    private enum class QueueMode {
        GLOBAL,
        INDEPENDENT_TABS;

        fun key(target: VoiceInputTarget): QueueKey = when (this) {
            GLOBAL -> QueueKey.Global
            INDEPENDENT_TABS -> QueueKey.Tab(target.tabId)
        }
    }

    private sealed interface QueueKey {
        data object Global : QueueKey
        data class Tab(val id: Tab.Id) : QueueKey
    }
}
