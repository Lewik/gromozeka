package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.remote.protocol.RemoteAudioChunk
import com.gromozeka.remote.protocol.RemoteAudioRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ClientAudioRecorder {
    val unavailableReason: LocalizedText?
        get() = null

    val supportsStreamingAudioChunks: Boolean
        get() = false

    suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession
}

interface ClientAudioRecordingSession {
    val audioChunks: Flow<ByteArray>
        get() = emptyFlow()

    suspend fun stop(): ClientRecordedAudio
    fun cancel()
}

data class ClientRecordedAudio(
    val data: ByteArray,
    val format: SpeechAudioFormat,
) {
    val byteSize: Int
        get() = data.size

    init {
        format.requireValid(data)
    }

    fun toRemoteRecording(sessionId: String): RemoteAudioRecording =
        RemoteAudioRecording(
            sessionId = sessionId,
            format = format,
            chunks = listOf(
                RemoteAudioChunk(
                    sequenceNumber = 0,
                    data = data
                )
            )
        )
}

object NoOpClientAudioRecorder : ClientAudioRecorder {
    override val unavailableReason: LocalizedText = localizedText("voice.unavailable")

    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession =
        throw LocalizedTextException(localizedText("voice.unavailable"))
}
