package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.speech.LocalWhisperTranscriptionService
import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioOutputFormat
import com.gromozeka.shared.audio.getAudioDuration
import com.gromozeka.shared.audio.isAudioLongEnough
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class SttService(
    private val clientFactory: OpenAiSdkClientFactory,
    private val settingsProvider: SettingsProvider,
    private val localWhisperTranscriptionService: LocalWhisperTranscriptionService,
) {
    private val log = KLoggers.logger(this)

    suspend fun transcribe(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String? = null,
        prompt: String? = null,
    ): String = withContext(Dispatchers.IO) {
        log.debug("Transcribing audio data (${audioData.size} bytes, format=$format)")

        when (format) {
            SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ -> {
                format.requireValid(audioData)
                val audioConfig = AudioConfig(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    bitDepth = format.bitDepth,
                )
                val audioDurationSeconds = audioData.getAudioDuration(AudioOutputFormat.WAV, audioConfig)

                log.debug("Audio duration: ${audioDurationSeconds}s")

                if (!audioData.isAudioLongEnough(AudioOutputFormat.WAV, audioConfig, minSeconds = 0.1)) {
                    log.debug("Audio too short (${audioDurationSeconds}s < 0.1s), skipping transcription")
                    return@withContext ""
                }
            }
        }

        val sttSettings = settingsProvider.userProfile.speechSettings.speechToText
        val requestedLanguage = language?.trim()?.takeIf { it.isNotBlank() } ?: sttSettings.mainLanguageCode
        if (sttSettings.engine == UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER) {
            return@withContext localWhisperTranscriptionService.transcribe(
                audioData = audioData,
                language = requestedLanguage,
                prompt = prompt,
                settings = sttSettings.localWhisper,
            )
        }

        val runtime = settingsProvider.resolveAiRuntime(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT)
        val client = clientFactory.createClient(runtime.connection)
        val tempFile = File.createTempFile("gromozeka-stt", ".${format.fileExtension}")

        try {
            tempFile.writeBytes(audioData)

            val params = TranscriptionCreateParams.builder()
                .file(tempFile.toPath())
                .model(runtime.modelConfiguration.providerModelId)
                .responseFormat(AudioResponseFormat.JSON)
                .temperature(0.0)
                .apply {
                    if (!requestedLanguage.equals("auto", ignoreCase = true)) {
                        language(requestedLanguage)
                    }
                    prompt?.trim()?.takeIf { it.isNotBlank() }?.let(::prompt)
                }
                .build()

            client.audio().transcriptions().create(params).asTranscription().text()
        } catch (e: Exception) {
            throw IllegalStateException("Speech-to-text request failed: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }
}
