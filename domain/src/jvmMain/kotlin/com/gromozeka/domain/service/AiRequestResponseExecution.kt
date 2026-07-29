package com.gromozeka.domain.service

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection

interface DirectAiEmbeddingProvider : AiEmbeddingProvider

data class AiSpeechTranscriptionRequest(
    val audioData: ByteArray,
    val format: SpeechAudioFormat,
    val engine: UserProfile.SpeechSettings.SpeechToText.Engine,
    val selection: AiRuntimeSelection?,
    val language: String?,
    val prompt: String?,
) {
    init {
        require(audioData.isNotEmpty()) { "Speech transcription audio must not be empty" }
        require(
            (engine == UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API) == (selection != null)
        ) {
            "OpenAI speech transcription requires a runtime selection and local Whisper must not provide one"
        }
    }
}

data class AiSpeechSynthesisRequest(
    val selection: AiRuntimeSelection,
    val text: String,
    val voiceTone: String,
    val voice: String,
    val speed: Float,
) {
    init {
        require(text.isNotBlank()) { "Speech synthesis text must not be blank" }
        require(voice.isNotBlank()) { "Speech synthesis voice must not be blank" }
        require(speed > 0.0f) { "Speech synthesis speed must be positive" }
    }
}

data class AiSpeechSynthesisResponse(
    val audioData: ByteArray,
    val mediaType: String,
    val fileExtension: String,
) {
    init {
        require(audioData.isNotEmpty()) { "Speech synthesis audio must not be empty" }
        require(mediaType.isNotBlank()) { "Speech synthesis media type must not be blank" }
        require(fileExtension.isNotBlank()) { "Speech synthesis file extension must not be blank" }
    }
}

interface DirectAiSpeechToTextProvider {
    suspend fun transcribe(request: AiSpeechTranscriptionRequest): String
}

interface DirectAiTextToSpeechProvider {
    suspend fun synthesize(request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse
}

interface AiRequestResponseExecutionClient {
    suspend fun call(
        target: ConversationRuntimeWorkerIdentity,
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse

    suspend fun embed(
        target: ConversationRuntimeWorkerIdentity,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse

    suspend fun transcribe(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechTranscriptionRequest,
    ): String

    suspend fun synthesize(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse
}

interface AiRequestResponseExecutionHandler {
    suspend fun call(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse

    suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse

    suspend fun transcribe(request: AiSpeechTranscriptionRequest): String

    suspend fun synthesize(request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse
}
