package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiSpeechToTextProvider
import com.gromozeka.domain.service.SettingsProvider
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
import org.springframework.stereotype.Service

@Service
class SttService(
    private val clientFactory: OpenAiSdkClientFactory,
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val localWhisperTranscriptionService: LocalWhisperTranscriptionService,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
) : DirectAiSpeechToTextProvider {
    private val log = KLoggers.logger(this)

    suspend fun transcribe(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String? = null,
        prompt: String? = null,
    ): String {
        val configured = configuredRequest(audioData, format, language, prompt)
        return when (val target = configured.target) {
            AiExecutionTarget.Server -> transcribe(configured.request)
            is AiExecutionTarget.Worker -> remoteClient().transcribe(
                target = workerTargetResolver.requireOnline(
                    ConversationRuntimeWorkerId(target.workerId),
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
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
        return transcribe(configured.request)
    }

    override suspend fun transcribe(request: AiSpeechTranscriptionRequest): String =
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
                        settings = settingsProvider.userProfile.speechSettings.speechToText.localWhisper,
                    )

                UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API ->
                    transcribeWithOpenAi(request)
            }
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

    private fun transcribeWithOpenAi(request: AiSpeechTranscriptionRequest): String {
        val selection = requireNotNull(request.selection) {
            "OpenAI speech transcription selection is missing"
        }
        val runtime = aiConfigurationProvider.resolveAiRuntime(selection)
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

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted speech transcription requires Rabbit runtime transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )

    private data class ConfiguredSpeechTranscription(
        val target: AiExecutionTarget,
        val request: AiSpeechTranscriptionRequest,
    )
}
