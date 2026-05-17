package com.gromozeka.client

import com.gromozeka.remote.protocol.AudioTranscriptionResponse
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.TranscribeAudioRequest

interface AudioTranscriptionService {
    suspend fun transcribe(recording: RemoteAudioRecording): String
}

class RemoteAudioTranscriptionService internal constructor(
    private val client: GromozekaWsClient,
) : AudioTranscriptionService {
    override suspend fun transcribe(recording: RemoteAudioRecording): String =
        client.requestTyped<TranscribeAudioRequest, AudioTranscriptionResponse>(
            TranscribeAudioRequest(recording)
        ).text
}
