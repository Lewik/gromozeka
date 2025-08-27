package com.gromozeka.bot.services

import com.gromozeka.bot.platform.AudioPlayerController
import klog.KLoggers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioSpeechOptions
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.ai.openai.audio.speech.SpeechPrompt
import java.io.File

class TtsService(
    private val openAiAudioSpeechModel: OpenAiAudioSpeechModel,
    private val settingsService: SettingsService,
    private val audioPlayerController: AudioPlayerController,
) {
    private val log = KLoggers.logger(this)

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
                        .voice(
                            OpenAiAudioApi.SpeechRequest.Voice.valueOf(
                                settingsService.settings.ttsVoice.uppercase()
                            )
                        )
                        .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                        .speed(settingsService.settings.ttsSpeed)
                        .build()
                )
            ).result.output
            outputFile.writeBytes(response)

            return@withContext outputFile

        } catch (e: Exception) {
            log.warn(e, "Failed to generate speech: ${e.message}")
            return@withContext null
        }
    }

    suspend fun playAudio(audioFile: File) {
        audioPlayerController.playAudioFile(audioFile)
    }

    suspend fun stopPlayback() {
        audioPlayerController.stopPlayback()
    }

    suspend fun generateAndPlay(task: TTSQueueService.Task) {
        val audioFile = generateSpeech(task.text, task.tone)
        audioFile?.let {
            playAudio(it)
            it.delete() // cleanup temp file
        }
    }
}