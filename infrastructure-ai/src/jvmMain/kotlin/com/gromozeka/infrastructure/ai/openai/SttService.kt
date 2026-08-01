package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiSpeechToTextProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeVoiceTranscriptionService
import com.gromozeka.infrastructure.ai.speech.LocalWhisperTranscriptionService
import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioOutputFormat
import com.gromozeka.shared.audio.getAudioDuration
import com.gromozeka.shared.audio.isAudioLongEnough
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import java.io.File
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class SttService(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val directProvider: DirectAiSpeechToTextProvider,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
) {

    suspend fun transcribe(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String? = null,
        prompt: String? = null,
    ): String {
        val configured = configuredRequest(audioData, format, language, prompt)
        return when (val target = configured.target) {
            AiExecutionTarget.Server -> directProvider.transcribe(
                configured.runtime,
                configured.localWhisperSettings,
                configured.request,
            )
            is AiExecutionTarget.Worker -> remoteClient().transcribe(
                target = workerTargetResolver.requireOnline(
                    ConversationRuntimeWorkerId(target.workerId),
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                runtime = configured.runtime,
                localWhisperSettings = configured.localWhisperSettings,
                request = configured.request,
            )
        }
    }

    suspend fun transcribeServerOnly(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String? = null,
        prompt: String? = null,
    ): String {
        val configured = configuredRequest(audioData, format, language, prompt)
        require(configured.target == AiExecutionTarget.Server) {
            "Live speech transcription requires a Server-targeted runtime"
        }
        return directProvider.transcribe(
            configured.runtime,
            configured.localWhisperSettings,
            configured.request,
        )
    }

    private fun configuredRequest(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String?,
        prompt: String?,
    ): ConfiguredSpeechTranscription {
        val settings = settingsProvider.userProfile.speechSettings.speechToText
        val requestedLanguage = language?.trim()?.takeIf { it.isNotBlank() }
            ?: settings.mainLanguageCode
        return when (settings.engine) {
            UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER ->
                ConfiguredSpeechTranscription(
                    target = settings.localWhisper.executionTarget,
                    runtime = null,
                    localWhisperSettings = settings.localWhisper,
                    request = AiSpeechTranscriptionRequest(
                        audioData = audioData,
                        format = format,
                        engine = settings.engine,
                        selection = null,
                        language = requestedLanguage,
                        prompt = prompt,
                    ),
                )

            UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API -> {
                val selection = aiConfigurationProvider.runtimeSelectionFor(
                    AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT
                )
                val resolved = aiConfigurationProvider.resolveAiRuntime(selection)
                ConfiguredSpeechTranscription(
                    target = resolved.connection.executionTarget,
                    runtime = resolved,
                    localWhisperSettings = null,
                    request = AiSpeechTranscriptionRequest(
                        audioData = audioData,
                        format = format,
                        engine = settings.engine,
                        selection = selection,
                        language = requestedLanguage,
                        prompt = prompt,
                    ),
                )
            }

            UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE -> {
                val connectionId = requireNotNull(settings.claudeCodeConnectionId) {
                    "Claude Code speech transcription connection is not configured"
                }
                val connection = aiConfigurationProvider.catalog.connections
                    .singleOrNull { it.id == connectionId }
                    as? AiConnection.ClaudeCode
                    ?: error("Claude Code speech transcription connection not found: ${connectionId.value}")
                require(connection.enabled) {
                    "Claude Code speech transcription connection is disabled: ${connection.id.value}"
                }
                require(connection.voiceTranscriptionEnabled) {
                    "Claude Code voice transcription is disabled for connection ${connection.id.value}"
                }
                ConfiguredSpeechTranscription(
                    target = connection.executionTarget,
                    runtime = null,
                    localWhisperSettings = null,
                    request = AiSpeechTranscriptionRequest(
                        audioData = audioData,
                        format = format,
                        engine = settings.engine,
                        selection = null,
                        claudeCodeConnection = connection,
                        language = requestedLanguage,
                        prompt = prompt,
                    ),
                )
            }
        }
    }

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted speech transcription requires Worker Gateway transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )

    private data class ConfiguredSpeechTranscription(
        val target: AiExecutionTarget,
        val runtime: ResolvedAiRuntime?,
        val localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        val request: AiSpeechTranscriptionRequest,
    )
}

@Service
class OpenAiSpeechTranscriptionExecutor(
    private val clientFactory: OpenAiSdkClientFactory,
    private val localWhisperTranscriptionService: LocalWhisperTranscriptionService,
    private val claudeCodeVoiceTranscriptionService: ClaudeCodeVoiceTranscriptionService,
) : DirectAiSpeechToTextProvider {
    private val log = KLoggers.logger(this)

    override suspend fun transcribe(
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): String =
        withContext(Dispatchers.IO) {
            log.debug {
                "Transcribing audio data (${request.audioData.size} bytes, format=${request.format}, engine=${request.engine})"
            }
            if (!validateAudio(request.audioData, request.format)) {
                return@withContext ""
            }

            when (request.engine) {
                UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER ->
                    localWhisperTranscriptionService.transcribe(
                        audioData = request.audioData,
                        language = requireNotNull(request.language) {
                            "Local Whisper language is missing"
                        },
                        prompt = request.prompt,
                        settings = requireNotNull(localWhisperSettings) {
                            "Local Whisper execution settings are missing"
                        },
                    )

                UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API ->
                    transcribeWithOpenAi(
                        requireNotNull(runtime) {
                            "OpenAI speech transcription runtime is missing"
                        },
                        request,
                    )

                UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE ->
                    claudeCodeVoiceTranscriptionService.transcribeAudio(
                        connection = requireNotNull(request.claudeCodeConnection),
                        audioData = request.audioData,
                        format = request.format,
                        language = request.language,
                    )
            }
        }

    private fun validateAudio(
        audioData: ByteArray,
        format: SpeechAudioFormat,
    ): Boolean {
        when (format) {
            SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ -> {
                format.requireValid(audioData)
                val audioConfig = AudioConfig(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    bitDepth = format.bitDepth,
                )
                val durationSeconds = audioData.getAudioDuration(AudioOutputFormat.WAV, audioConfig)
                log.debug { "Audio duration: ${durationSeconds}s" }
                if (!audioData.isAudioLongEnough(AudioOutputFormat.WAV, audioConfig, minSeconds = 0.1)) {
                    log.debug { "Audio too short (${durationSeconds}s < 0.1s), skipping transcription" }
                    return false
                }
            }
        }
        return true
    }

    private fun transcribeWithOpenAi(
        runtime: ResolvedAiRuntime,
        request: AiSpeechTranscriptionRequest,
    ): String {
        val client = clientFactory.createClient(runtime.connection)
        val tempFile = File.createTempFile("gromozeka-stt", ".${request.format.fileExtension}")

        try {
            tempFile.writeBytes(request.audioData)
            val params = TranscriptionCreateParams.builder()
                .file(tempFile.toPath())
                .model(runtime.modelConfiguration.providerModelId)
                .responseFormat(AudioResponseFormat.JSON)
                .temperature(0.0)
                .apply {
                    request.language
                        ?.takeUnless { it.equals("auto", ignoreCase = true) }
                        ?.let(::language)
                    request.prompt?.trim()?.takeIf { it.isNotBlank() }?.let(::prompt)
                }
                .build()
            return client.audio().transcriptions().create(params).asTranscription().text()
        } catch (error: Exception) {
            throw IllegalStateException("Speech-to-text request failed: ${error.message}", error)
        } finally {
            tempFile.delete()
        }
    }

}
