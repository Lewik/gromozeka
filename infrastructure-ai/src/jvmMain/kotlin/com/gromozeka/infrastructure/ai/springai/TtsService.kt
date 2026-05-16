package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.service.AudioController
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import klog.KLoggers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioSpeechOptions
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.ai.audio.tts.TextToSpeechPrompt
import java.io.File

class TtsService(
    private val openAiAudioSpeechModel: OpenAiAudioSpeechModel,
    private val settingsProvider: SettingsProvider,
    private val audioController: AudioController,
) {
    private val log = KLoggers.logger(this)

    suspend fun generateSpeech(
        text: String,
        voiceTone: String = "neutral colleague",
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (text.isBlank()) return@withContext null

            val outputFile = File.createTempFile("tts_output", ".mp3")
            val textToSpeechSettings = settingsProvider.userProfile.speechSettings.textToSpeech
            val modelName = settingsProvider.resolveAiRuntime(
                AiRuntimeSelection(textToSpeechSettings.modelConfigurationId)
            ).modelConfiguration.providerModelId

            val response = openAiAudioSpeechModel.call(
                TextToSpeechPrompt(
                    text, OpenAiAudioSpeechOptions.builder()
                        .model(modelName)
                        .voice(
                            OpenAiAudioApi.SpeechRequest.Voice.valueOf(
                                textToSpeechSettings.voice.uppercase()
                            )
                        )
                        .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                        .speed(textToSpeechSettings.speed.toDouble())
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
        audioController.playAudioFile(audioFile.absolutePath)
    }

    suspend fun stopPlayback() {
        audioController.stopPlayback()
    }

    suspend fun generateAndPlay(task: TtsTask) {
        val audioFile = generateSpeech(task.text, task.tone)
        audioFile?.let {
            playAudio(it)
            it.delete()
        }
    }

}
