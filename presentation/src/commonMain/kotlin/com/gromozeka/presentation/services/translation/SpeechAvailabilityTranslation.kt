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
        Code.CLAUDE_CONNECTION_NOT_CONFIGURED -> "voice.claudeConnectionNotConfigured"
        Code.CLAUDE_CONNECTION_NOT_FOUND -> "voice.claudeConnectionMissing"
        Code.CLAUDE_VOICE_DISABLED -> "voice.claudeVoiceDisabled"
        Code.TRANSCRIPTION_WORKER_NOT_FOUND -> "voice.transcriptionWorkerMissing"
        Code.CLAUDE_DIRECT_UNSUPPORTED_PLATFORM -> "voice.claudeDirectUnsupportedPlatform"
        Code.CLAUDE_REQUIRES_WORKER -> "voice.claudeRequiresWorker"
        Code.CLAUDE_FORWARDING_REQUIRES_LINUX -> "voice.claudeForwardingRequiresLinux"
        Code.EXECUTABLE_UNAVAILABLE -> "voice.executableUnavailable"
        Code.CLIENT_AUDIO_UPLOAD_UNAVAILABLE -> "voice.clientUploadUnavailable"
    }
    val reason = LocalizedText.Resource(key, arguments.mapValues { LocalizedText.Literal(it.value) })
    return diagnostic?.takeIf { it.isNotBlank() }?.let {
        LocalizedText.Joined(listOf(reason, LocalizedText.Literal(it)), "\n")
    } ?: reason
}
