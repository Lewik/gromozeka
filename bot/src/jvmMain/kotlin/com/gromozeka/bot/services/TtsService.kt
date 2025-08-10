package com.gromozeka.bot.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioSpeechOptions
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.ai.openai.audio.speech.SpeechPrompt
import java.io.File

class TtsService(
    private val openAiAudioSpeechModel: OpenAiAudioSpeechModel,
    private val settingsService: SettingsService
) {

    suspend fun generateSpeech(
        text: String,
        voiceTone: String = "neutral colleague",
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) return@withContext null

            val outputFile = File.createTempFile("tts_output", ".mp3")

            val response = openAiAudioSpeechModel.call(
                SpeechPrompt(
                    voiceTone, OpenAiAudioSpeechOptions.builder()
                        .input(text)
                        .model(settingsService.settings.ttsModel)
                        .voice(OpenAiAudioApi.SpeechRequest.Voice.valueOf(
                            settingsService.settings.ttsVoice.uppercase()
                        ))
                        .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                        .speed(settingsService.settings.ttsSpeed)
                        .build()
                )
            ).result.output
            outputFile.writeBytes(response)

            return@withContext outputFile

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun playAudio(audioFile: File) = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("afplay", audioFile.absolutePath)
                .start()

            // Cancellation-aware waiting
            while (process.isAlive) {
                ensureActive() // Check cancellation
                Thread.sleep(50) // Short intervals for quick response
            }

        } catch (e: CancellationException) {
            println("[TTS] Audio playback cancelled")
            process?.destroyForcibly() // Kill afplay process
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    suspend fun generateAndPlay(task: TTSQueueService.Task) {
        val audioFile = generateSpeech(task.text, task.tone)
        audioFile?.let {
            playAudio(it)
            it.delete() // cleanup temp file
        }
    }
}