package com.gromozeka.infrastructure.ai.springai

import klog.KLoggers

import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioOutputFormat
import com.gromozeka.shared.audio.getAudioDuration
import com.gromozeka.shared.audio.isAudioLongEnough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions
import org.springframework.ai.openai.api.OpenAiAudioApi.TranscriptResponseFormat
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Service
import com.gromozeka.domain.service.SettingsProvider
import java.io.File

@Service
class SttService(
    private val openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel,
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)


    suspend fun transcribe(
        audioData: ByteArray,
        fileExtension: String = "wav",
        mediaType: String = "audio/wav",
    ): String = withContext(Dispatchers.IO) {
        log.debug("Transcribing audio data (${audioData.size} bytes, mediaType=$mediaType)")

        val isWav = mediaType.contains("wav", ignoreCase = true) ||
            fileExtension.equals("wav", ignoreCase = true)

        if (isWav) {
            val audioConfig = AudioConfig(sampleRate = 16000, channels = 1, bitDepth = 16)
            val audioDurationSeconds = audioData.getAudioDuration(AudioOutputFormat.WAV, audioConfig)

            log.debug("Audio duration: ${audioDurationSeconds}s")

            if (!audioData.isAudioLongEnough(AudioOutputFormat.WAV, audioConfig, minSeconds = 0.1)) {
                log.debug("Audio too short (${audioDurationSeconds}s < 0.1s), skipping transcription")
                return@withContext ""
            }
        } else if (audioData.size < 256) {
            log.debug("Audio too small (${audioData.size} bytes), skipping transcription")
            return@withContext ""
        }

        val normalizedExtension = fileExtension.trim().trimStart('.').ifBlank { "webm" }
        val tempFile = File.createTempFile("recorded", ".$normalizedExtension")
        val text = try {
            tempFile.writeBytes(audioData)

            val transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
                .responseFormat(TranscriptResponseFormat.TEXT)
                .temperature(0f)
                .language(settingsProvider.sttMainLanguage)
                .build()

            val transcriptionRequest = AudioTranscriptionPrompt(FileSystemResource(tempFile), transcriptionOptions)
            val result = openAiAudioTranscriptionModel.call(transcriptionRequest).result.output

            result
        } catch (e: Exception) {
            log.error("Transcription error: ${e.message}")
            ""
        } finally {
            tempFile.delete()
        }
        return@withContext text
    }
}
