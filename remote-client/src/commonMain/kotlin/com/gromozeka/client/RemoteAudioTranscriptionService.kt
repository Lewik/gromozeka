package com.gromozeka.client

import com.gromozeka.remote.protocol.AudioTranscriptionResponse
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.TranscribeAudioRequest

class RemoteAudioTranscriptionService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun transcribe(recording: RemoteAudioRecording): String =
        client.requestTyped<TranscribeAudioRequest, AudioTranscriptionResponse>(
            TranscribeAudioRequest(recording)
        ).text
}
