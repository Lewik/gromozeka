package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteSpeechSynthesisEvent
import com.gromozeka.client.RemoteSpeechSynthesisService
import com.gromozeka.domain.model.TtsTask
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteTtsQueue(
    private val speechSynthesisService: RemoteSpeechSynthesisService,
    private val audioPlayer: ClientAudioPlayer,
) : TtsQueue {
    private companion object {
        const val AUDIO_CHUNK_BUFFER_CAPACITY = 2
    }

    private val log = KLoggers.logger(this)
    private val queueMutex = Mutex()
    private val activeMutex = Mutex()
    private val _isPlaying = MutableStateFlow(false)
    private var activeJob: Job? = null
    private var playbackGeneration = 0L
    private var playbackBlocked = false

    override val isPlaying: StateFlow<Boolean> = _isPlaying

    override fun start() = Unit

    override suspend fun enqueue(task: TtsTask) {
        if (task.text.isBlank()) return

        val requestedGeneration = activeMutex.withLock {
            if (playbackBlocked) null else playbackGeneration
        } ?: run {
            log.info("Remote TTS skipped while playback is blocked")
            return
        }

        queueMutex.withLock {
            coroutineScope {
                val job = launch(start = CoroutineStart.LAZY) { playTask(task) }
                val accepted = activeMutex.withLock {
                    if (playbackBlocked || playbackGeneration != requestedGeneration) {
                        false
                    } else {
                        activeJob = job
                        _isPlaying.value = true
                        true
                    }
                }
                if (!accepted) {
                    job.cancel()
                    log.info("Remote TTS discarded after the playback queue was cleared")
                    return@coroutineScope
                }

                job.start()
                try {
                    job.join()
                } finally {
                    activeMutex.withLock {
                        if (activeJob === job) {
                            activeJob = null
                        }
                        if (activeJob == null) {
                            _isPlaying.value = false
                        }
                    }
                }
            }
        }
    }

    override suspend fun stopAndClear() {
        stopAndInvalidate(blockPlayback = false)
    }

    override suspend fun blockAndClear() {
        stopAndInvalidate(blockPlayback = true)
    }

    override suspend fun allowPlayback() {
        activeMutex.withLock {
            playbackBlocked = false
        }
        log.info("Remote TTS playback allowed")
    }

    private suspend fun stopAndInvalidate(blockPlayback: Boolean) {
        val job = activeMutex.withLock {
            playbackGeneration++
            if (blockPlayback) {
                playbackBlocked = true
            }
            val current = activeJob
            activeJob = null
            _isPlaying.value = false
            current
        }
        job?.cancel()
        audioPlayer.stop()
        if (job == null) {
            log.info("Remote TTS stop requested: nothing is playing")
            return
        }

        log.info("Remote TTS stop requested")
    }

    override fun shutdown() {
        playbackBlocked = true
        playbackGeneration++
        audioPlayer.stop()
        activeJob?.cancel()
        activeJob = null
        _isPlaying.value = false
    }

    private suspend fun playTask(task: TtsTask) {
        log.info("Remote TTS enqueue: textChars=${task.text.length} toneChars=${task.tone.length}")
        try {
            coroutineScope {
                val chunks = Channel<ByteArray>(AUDIO_CHUNK_BUFFER_CAPACITY)
                var playback: Job? = null
                var chunkCount = 0
                var byteCount = 0
                var completedNormally = false
                try {
                    speechSynthesisService.synthesizeStream(task).collect { event ->
                        when (event) {
                            is RemoteSpeechSynthesisEvent.Started -> {
                                log.info(
                                    "Remote TTS stream started: sampleRate=${event.sampleRate} " +
                                        "channels=${event.channels} bitsPerSample=${event.bitsPerSample}"
                                )
                                playback = launch {
                                    audioPlayer.playPcmStream(
                                        chunks.consumeAsFlow(),
                                        event.sampleRate,
                                        event.channels,
                                        event.bitsPerSample,
                                    )
                                }
                            }

                            is RemoteSpeechSynthesisEvent.Chunk -> {
                                chunkCount++
                                byteCount += event.data.size
                                chunks.send(event.data)
                            }

                            RemoteSpeechSynthesisEvent.Completed -> {
                                completedNormally = true
                                log.info("Remote TTS stream completed: chunks=$chunkCount bytes=$byteCount")
                                chunks.close()
                            }

                            is RemoteSpeechSynthesisEvent.Failed -> {
                                log.warn("Remote TTS stream failed: ${event.message}")
                                error(event.message)
                            }
                        }
                    }
                } finally {
                    chunks.close()
                    if (completedNormally) {
                        playback?.join()
                    } else {
                        playback?.cancelAndJoin()
                    }
                }
            }
        } catch (e: CancellationException) {
            log.info("Remote TTS cancelled")
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Remote TTS failed: ${e.message}" }
        }
    }
}
