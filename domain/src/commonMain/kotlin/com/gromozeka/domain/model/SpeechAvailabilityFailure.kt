package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SpeechAvailabilityFailure(
    val code: Code,
    val arguments: Map<String, String> = emptyMap(),
    val diagnostic: String? = null,
) {
    @Serializable
    enum class Code {
        UNAVAILABLE,
        PROVIDER_VAD_UNAVAILABLE,
        SPEECH_TO_TEXT_DISABLED,
        PROVIDER_VAD_REQUIRES_OPENAI,
        PROVIDER_VAD_REQUIRES_CLIENT_MICROPHONE,
        REALTIME_REQUIRES_SERVER,
        CONNECTION_DISABLED,
        WORKER_AUDIO_SOURCE_REQUIRED,
        SOURCE_WORKER_NOT_FOUND,
        AUDIO_INPUT_UNAVAILABLE,
        CLAUDE_CONNECTION_NOT_CONFIGURED,
        CLAUDE_CONNECTION_NOT_FOUND,
        CLAUDE_VOICE_DISABLED,
        TRANSCRIPTION_WORKER_NOT_FOUND,
        CLAUDE_DIRECT_UNSUPPORTED_PLATFORM,
        CLAUDE_REQUIRES_WORKER,
        CLAUDE_FORWARDING_REQUIRES_LINUX,
        EXECUTABLE_UNAVAILABLE,
        CLIENT_AUDIO_UPLOAD_UNAVAILABLE,
    }
}

class SpeechAvailabilityException(
    val failure: SpeechAvailabilityFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.code.name, cause)

fun failSpeechAvailability(code: SpeechAvailabilityFailure.Code, vararg arguments: Pair<String, Any?>): Nothing =
    throw SpeechAvailabilityException(SpeechAvailabilityFailure(
        code,
        arguments.associate { (name, value) -> name to value.toString() },
    ))
