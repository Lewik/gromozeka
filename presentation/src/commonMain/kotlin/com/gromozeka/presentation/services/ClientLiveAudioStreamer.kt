package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

interface ClientLiveAudioStreamer {
    val unavailableReason: LocalizedText?
    suspend fun start(scope: CoroutineScope, onChunk: suspend (RemotePcmAudioChunk) -> Unit): ClientLiveAudioStreamingSession
}

interface ClientLiveAudioStreamingSession {
    suspend fun stop()
    fun cancel()
}

class PcmClientLiveAudioStreamer(private val recorder: ClientAudioRecorder) : ClientLiveAudioStreamer {
    override val unavailableReason: LocalizedText? get() = recorder.streamingUnavailableReason

    override suspend fun start(
        scope: CoroutineScope,
        onChunk: suspend (RemotePcmAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession {
        unavailableReason?.let { throw LocalizedTextException(it) }
        val capture = recorder.startStreaming(scope)
        val stopping = CompletableDeferred<Unit>()
        val sender = scope.launch {
            var sequence = 0
            capture.audioChunks.collect { pcm ->
                if (pcm.isNotEmpty()) onChunk(RemotePcmAudioChunk(sequenceNumber = sequence++, data = pcm))
            }
            check(stopping.isCompleted) { "Live microphone stream ended unexpectedly" }
        }
        return object : ClientLiveAudioStreamingSession {
            override suspend fun stop() {
                stopping.complete(Unit)
                capture.stop()
                sender.join()
            }

            override fun cancel() {
                stopping.complete(Unit)
                try {
                    capture.cancel()
                } finally {
                    sender.cancel()
                }
            }
        }
    }
}
