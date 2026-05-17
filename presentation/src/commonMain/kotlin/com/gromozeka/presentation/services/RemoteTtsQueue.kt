package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteSpeechSynthesisEvent
import com.gromozeka.client.RemoteSpeechSynthesisService
import com.gromozeka.domain.model.TtsTask
import klog.KLoggers
import kotlinx.coroutines.Job
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
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val _isPlaying = MutableStateFlow(false)

    override val isPlaying: StateFlow<Boolean> = _isPlaying

    override fun start() = Unit

    override suspend fun enqueue(task: TtsTask) {
        mutex.withLock {
            if (task.text.isBlank()) return
            _isPlaying.value = true
            log.info("Remote TTS enqueue: textChars=${task.text.length} toneChars=${task.tone.length}")
            try {
                coroutineScope {
                    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                    var playback: Job? = null
                    var chunkCount = 0
                    var byteCount = 0
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
                        playback?.join()
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "Remote TTS failed: ${e.message}" }
            } finally {
                _isPlaying.value = false
            }
        }
    }

    override fun stopAndClear() = Unit

    override fun shutdown() {
        _isPlaying.value = false
    }
}
