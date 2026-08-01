package com.gromozeka.domain.service

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.ai.AiConnection
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
        @SerialName("prepare_claude_code_microphone")
        data class PrepareClaudeCodeMicrophone(
            val connection: AiConnection.ClaudeCode,
            val language: String?,
        ) : Command

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
        @SerialName("start_claude_code_microphone")
        data class StartClaudeCodeMicrophone(
            val sessionId: String,
            val connection: AiConnection.ClaudeCode,
            val language: String?,
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
    val transcript: String? = null,
) {
    init {
        when (status) {
            Status.PREPARED,
            Status.STARTED,
            Status.CANCELLED,
            -> require(audioData == null && format == null && transcript == null) {
                "$status Worker audio result cannot contain a payload"
            }
            Status.AUDIO_CAPTURED -> require(audioData?.isNotEmpty() == true && format != null && transcript == null) {
                "Captured Worker audio result requires audio bytes and format"
            }
            Status.TRANSCRIBED -> require(audioData == null && format == null && transcript != null) {
                "Transcribed Worker audio result requires transcript"
            }
        }
    }

    @Serializable
    enum class Status {
        PREPARED,
        STARTED,
        AUDIO_CAPTURED,
        TRANSCRIBED,
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
