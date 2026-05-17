package com.gromozeka.client

import com.gromozeka.domain.model.TtsTask
import com.gromozeka.remote.protocol.SpeechSynthesisResponse
import com.gromozeka.remote.protocol.SpeechSynthesisChunkEvent
import com.gromozeka.remote.protocol.SpeechSynthesisCompletedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisFailedEvent
import com.gromozeka.remote.protocol.SpeechSynthesisStartedEvent
import com.gromozeka.remote.protocol.SynthesizeSpeechRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RemoteSpeechSynthesisService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun synthesize(task: TtsTask): RemoteSpeechAudio {
        val response = client.requestTyped<SynthesizeSpeechRequest, SpeechSynthesisResponse>(
            SynthesizeSpeechRequest(
                text = task.text,
                tone = task.tone,
            )
        )
        return RemoteSpeechAudio(
            data = response.audioData,
            mediaType = response.mediaType,
            fileExtension = response.fileExtension,
        )
    }

    fun synthesizeStream(task: TtsTask): Flow<RemoteSpeechSynthesisEvent> =
        client.synthesizeSpeech(task.text, task.tone).map { payload ->
            when (payload) {
                is SpeechSynthesisStartedEvent -> RemoteSpeechSynthesisEvent.Started(
                    mediaType = payload.mediaType,
                    fileExtension = payload.fileExtension,
                    sampleRate = payload.sampleRate,
                    channels = payload.channels,
                    bitsPerSample = payload.bitsPerSample,
                )

                is SpeechSynthesisChunkEvent -> RemoteSpeechSynthesisEvent.Chunk(
                    sequenceNumber = payload.sequenceNumber,
                    data = payload.data,
                )

                is SpeechSynthesisCompletedEvent -> RemoteSpeechSynthesisEvent.Completed
                is SpeechSynthesisFailedEvent -> RemoteSpeechSynthesisEvent.Failed(payload.message)
                else -> error("Unexpected speech synthesis event: $payload")
            }
        }
}

data class RemoteSpeechAudio(
    val data: ByteArray,
    val mediaType: String,
    val fileExtension: String,
)

sealed interface RemoteSpeechSynthesisEvent {
    data class Started(
        val mediaType: String,
        val fileExtension: String,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) : RemoteSpeechSynthesisEvent

    data class Chunk(
        val sequenceNumber: Int,
        val data: ByteArray,
    ) : RemoteSpeechSynthesisEvent

    data object Completed : RemoteSpeechSynthesisEvent

    data class Failed(
        val message: String,
    ) : RemoteSpeechSynthesisEvent
}
