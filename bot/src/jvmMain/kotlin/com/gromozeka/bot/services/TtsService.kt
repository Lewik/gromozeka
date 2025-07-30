package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioSpeechOptions
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.ai.openai.audio.speech.SpeechPrompt
import java.io.File

class TtsService(private val openAiAudioSpeechModel: OpenAiAudioSpeechModel) {

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
                        .model("gpt-4o-mini-tts")
//                        .model(OpenAiAudioApi.TtsModel.TTS_1.value)
                        .voice(OpenAiAudioApi.SpeechRequest.Voice.ALLOY)
                        .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                        .speed(1.0f)
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
        try {
            val process = ProcessBuilder("afplay", audioFile.absolutePath)
                .start()

            process.waitFor()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun generateAndPlay(text: String, voiceTone: String = "neutral colleague") {
        val audioFile = generateSpeech(text, voiceTone)
        audioFile?.let {
            playAudio(it)
            it.delete() // cleanup temp file
        }
    }
}