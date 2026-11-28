package com.gromozeka.domain.service

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.WorkerAudioInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class WorkerAudioCaptureRequest(
    val target: ConversationRuntimeWorkerIdentity,
    val command: Command,
) {
    @Serializable
    @JsonClassDiscriminator("commandKind")
    sealed interface Command {
        @Serializable
        @SerialName("start_audio")
        data class StartAudio(
            val sessionId: String,
            val inputId: WorkerAudioInput.Id,
        ) : Command {
            init {
                validateSessionId(sessionId)
            }
        }

        @Serializable
        @SerialName("stop")
        data class Stop(
            val sessionId: String,
        ) : Command {
            init {
                validateSessionId(sessionId)
            }
        }

        @Serializable
        @SerialName("cancel")
        data class Cancel(
            val sessionId: String,
        ) : Command {
            init {
                validateSessionId(sessionId)
            }
        }
    }
}

@Serializable
data class WorkerAudioCaptureResult(
    val status: Status,
    val audioData: ByteArray? = null,
    val format: SpeechAudioFormat? = null,
) {
    init {
        when (status) {
            Status.STARTED,
            Status.CANCELLED,
            -> require(audioData == null && format == null) {
                "$status Worker audio result cannot contain a payload"
            }
            Status.AUDIO_CAPTURED -> require(audioData?.isNotEmpty() == true && format != null) {
                "Captured Worker audio result requires audio bytes and format"
            }
        }
    }

    @Serializable
    enum class Status {
        STARTED,
        AUDIO_CAPTURED,
        CANCELLED,
    }
}

interface WorkerAudioCaptureClient {
    suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult
}

interface WorkerAudioCaptureHandler {
    suspend fun handle(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult
}

private fun validateSessionId(sessionId: String) {
    require(sessionId.isNotBlank()) { "Worker audio capture session id must not be blank" }
    require(sessionId.length <= MAX_AUDIO_CAPTURE_SESSION_ID_LENGTH) {
        "Worker audio capture session id is too long"
    }
}

private const val MAX_AUDIO_CAPTURE_SESSION_ID_LENGTH = 128
