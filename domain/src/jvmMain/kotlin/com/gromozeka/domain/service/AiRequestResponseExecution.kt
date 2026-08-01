package com.gromozeka.domain.service

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiModelSpec

interface DirectAiEmbeddingProvider {
    suspend fun embed(
        runtime: ResolvedAiRuntime,
        modelSpec: AiModelSpec,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse
}

data class AiSpeechTranscriptionRequest(
    val audioData: ByteArray,
    val format: SpeechAudioFormat,
    val engine: UserProfile.SpeechSettings.SpeechToText.Engine,
    val selection: AiRuntimeSelection?,
    val claudeCodeConnection: AiConnection.ClaudeCode? = null,
    val language: String?,
    val prompt: String?,
) {
    init {
        require(audioData.isNotEmpty()) { "Speech transcription audio must not be empty" }
        when (engine) {
            UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API -> require(
                selection != null && claudeCodeConnection == null
            ) {
                "OpenAI speech transcription requires a runtime selection"
            }
            UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> require(
                selection == null && claudeCodeConnection == null
            ) {
                "Local Whisper speech transcription cannot provide an AI connection"
            }
            UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE -> require(
                selection == null && claudeCodeConnection != null
            ) {
                "Claude Code speech transcription requires a Claude Code connection"
            }
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
    suspend fun transcribe(
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String
}

interface DirectAiTextToSpeechProvider {
    suspend fun synthesize(
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse
}

interface AiRequestResponseExecutionClient {
    suspend fun call(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse

    suspend fun embed(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        modelSpec: AiModelSpec,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse

    suspend fun transcribe(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String

    suspend fun synthesize(
        target: ConversationRuntimeWorkerIdentity,
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse
}

interface AiRequestResponseExecutionHandler {
    suspend fun call(
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse

    suspend fun embed(
        runtime: ResolvedAiRuntime,
        modelSpec: AiModelSpec,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse

    suspend fun transcribe(
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String

    suspend fun synthesize(
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse
}
