package com.gromozeka.bot.services

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
import java.io.File

@Service
class SttService(
    private val openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel,
    private val settingsService: SettingsService,
) {


    suspend fun transcribe(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        println("[STT] Transcribing audio data (${audioData.size} bytes)")

        // Check audio duration before sending to OpenAI
        val audioConfig = AudioConfig(sampleRate = 16000, channels = 1, bitDepth = 16)
        val audioDurationSeconds = audioData.getAudioDuration(AudioOutputFormat.WAV, audioConfig)

        println("[STT] Audio duration: ${audioDurationSeconds}s")

        // OpenAI requires minimum 0.1 seconds of audio
        if (!audioData.isAudioLongEnough(AudioOutputFormat.WAV, audioConfig, minSeconds = 0.1)) {
            println("[STT] Audio too short (${audioDurationSeconds}s < 0.1s), skipping transcription")
            return@withContext ""
        }

        val text = try {
            val tempFile = File.createTempFile("recorded", ".wav")
            tempFile.writeBytes(audioData)

            val transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
                .responseFormat(TranscriptResponseFormat.TEXT)
                .temperature(0f)
                .language(settingsService.settings.sttMainLanguage)
                .build()

            val transcriptionRequest = AudioTranscriptionPrompt(FileSystemResource(tempFile), transcriptionOptions)
            val result = openAiAudioTranscriptionModel.call(transcriptionRequest).result.output

            tempFile.delete()
            result
        } catch (e: Exception) {
            println("[STT] Transcription error: ${e.message}")
            ""
        }
        return@withContext text
    }
}