package com.gromozeka.presentation.services.translation

import com.gromozeka.domain.model.SpeechAvailabilityFailure
import com.gromozeka.domain.model.SpeechAvailabilityFailure.Code

fun SpeechAvailabilityFailure.localizedText(): LocalizedText {
    val key = when (code) {
        Code.UNAVAILABLE -> "voice.captureUnavailable"
        Code.PROVIDER_VAD_UNAVAILABLE -> "voice.providerUnavailable"
        Code.SPEECH_TO_TEXT_DISABLED -> "voice.sttDisabled"
        Code.PROVIDER_VAD_REQUIRES_OPENAI -> "voice.providerRequiresOpenAi"
        Code.PROVIDER_VAD_REQUIRES_CLIENT_MICROPHONE -> "voice.liveNeedsClientMicrophone"
        Code.REALTIME_REQUIRES_SERVER -> "voice.realtimeRequiresServer"
        Code.CONNECTION_DISABLED -> "voice.connectionDisabled"
        Code.WORKER_AUDIO_SOURCE_REQUIRED -> "voice.workerAudioRequired"
        Code.SOURCE_WORKER_NOT_FOUND -> "voice.sourceWorkerMissing"
        Code.AUDIO_INPUT_UNAVAILABLE -> "voice.workerInputUnavailable"
        Code.TRANSCRIPTION_WORKER_NOT_FOUND -> "voice.transcriptionWorkerMissing"
    }
    val reason = LocalizedText.Resource(key, arguments.mapValues { LocalizedText.Literal(it.value) })
    return diagnostic?.takeIf { it.isNotBlank() }?.let {
        LocalizedText.Joined(listOf(reason, LocalizedText.Literal(it)), "\n")
    } ?: reason
}
