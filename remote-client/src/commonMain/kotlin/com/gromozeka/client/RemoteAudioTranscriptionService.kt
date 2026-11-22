package com.gromozeka.client

import com.gromozeka.domain.model.SpeechAvailabilityFailure

import com.gromozeka.remote.protocol.AudioTranscriptionResponse
import com.gromozeka.remote.protocol.CancelSpeechCaptureRequest
import com.gromozeka.remote.protocol.GetSpeechCaptureAvailabilityRequest
import com.gromozeka.remote.protocol.OperationResultResponse
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.TranscribeAudioRequest
import com.gromozeka.remote.protocol.StartSpeechCaptureRequest
import com.gromozeka.remote.protocol.SpeechCaptureStartedResponse
import com.gromozeka.remote.protocol.SpeechCaptureAvailabilityResponse
import com.gromozeka.remote.protocol.SpeechCaptureStoppedResponse
import com.gromozeka.remote.protocol.StopSpeechCaptureRequest

interface AudioTranscriptionService {
    suspend fun transcribe(recording: RemoteAudioRecording): String
    suspend fun captureUnavailableReason(): SpeechAvailabilityFailure?
    suspend fun startCapture(sessionId: String)
    suspend fun stopCapture(sessionId: String): RemoteAudioRecording
    suspend fun cancelCapture(sessionId: String)
}

class RemoteAudioTranscriptionService internal constructor(
    private val client: GromozekaWsClient,
) : AudioTranscriptionService {
    override suspend fun transcribe(recording: RemoteAudioRecording): String =
        client.requestTyped<TranscribeAudioRequest, AudioTranscriptionResponse>(
            TranscribeAudioRequest(recording)
        ).text

    override suspend fun captureUnavailableReason(): SpeechAvailabilityFailure? =
        client.requestTyped<GetSpeechCaptureAvailabilityRequest, SpeechCaptureAvailabilityResponse>(
            GetSpeechCaptureAvailabilityRequest
        ).unavailableReason

    override suspend fun startCapture(sessionId: String) {
        client.requestTyped<StartSpeechCaptureRequest, SpeechCaptureStartedResponse>(
            StartSpeechCaptureRequest(sessionId)
        )
    }

    override suspend fun stopCapture(sessionId: String): RemoteAudioRecording =
        client.requestTyped<StopSpeechCaptureRequest, SpeechCaptureStoppedResponse>(
            StopSpeechCaptureRequest(sessionId)
        ).recording

    override suspend fun cancelCapture(sessionId: String) {
        client.requestTyped<CancelSpeechCaptureRequest, OperationResultResponse>(
            CancelSpeechCaptureRequest(sessionId)
        )
    }
}
