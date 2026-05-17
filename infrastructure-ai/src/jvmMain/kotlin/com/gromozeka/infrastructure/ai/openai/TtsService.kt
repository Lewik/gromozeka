package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AudioController
import com.gromozeka.domain.service.SettingsProvider
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class TtsService(
    private val clientFactory: OpenAiSdkClientFactory,
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

            val runtime = textToSpeechRuntime()
            val settings = settingsProvider.userProfile.speechSettings.textToSpeech
            val client = clientFactory.createClient(runtime.connection)
            val outputFile = File.createTempFile("tts_output", ".wav")
            val response = client.audio().speech().create(
                speechParamsBuilder(text, voiceTone, runtime.modelConfiguration.providerModelId, settings.voice, settings.speed)
                    .responseFormat(SpeechCreateParams.ResponseFormat.WAV)
                    .streamFormat(SpeechCreateParams.StreamFormat.AUDIO)
                    .build()
            )

            response.use { httpResponse ->
                httpResponse.body().use { input ->
                    outputFile.outputStream().use(input::copyTo)
                }
            }

            outputFile
        } catch (e: Exception) {
            log.warn(e) { "Failed to generate speech: ${e.message}" }
            null
        }
    }

    fun streamSpeechPcm(
        text: String,
        voiceTone: String = "neutral colleague",
    ): Flow<TtsAudioChunk> = flow {
        if (text.isBlank()) return@flow

        val runtime = textToSpeechRuntime()
        val settings = settingsProvider.userProfile.speechSettings.textToSpeech
        val client = clientFactory.createClient(runtime.connection)
        val response = withContext(Dispatchers.IO) {
            client.audio().speech().create(
                speechParamsBuilder(text, voiceTone, runtime.modelConfiguration.providerModelId, settings.voice, settings.speed)
                    .responseFormat(SpeechCreateParams.ResponseFormat.PCM)
                    .streamFormat(SpeechCreateParams.StreamFormat.AUDIO)
                    .build()
            )
        }

        response.use { httpResponse ->
            httpResponse.body().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = withContext(Dispatchers.IO) { input.read(buffer) }
                    if (read < 0) break
                    if (read > 0) emit(TtsAudioChunk(buffer.copyOf(read)))
                }
            }
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

    private fun textToSpeechRuntime() =
        settingsProvider.resolveAiRuntime(
            AiRuntimeSelection(settingsProvider.userProfile.speechSettings.textToSpeech.modelConfigurationId)
        )

    private fun speechParamsBuilder(
        text: String,
        voiceTone: String,
        modelName: String,
        voice: String,
        speed: Float,
    ): SpeechCreateParams.Builder =
        SpeechCreateParams.builder()
            .input(text)
            .model(SpeechModel.of(modelName))
            .voice(voice)
            .instructions(voiceInstructions(voiceTone))
            .speed(speed.coerceIn(0.25f, 4.0f).toDouble())

    private fun voiceInstructions(voiceTone: String): String =
        buildString {
            append("Speak naturally and clearly. Preserve the language of the input text.")
            voiceTone.trim().takeIf { it.isNotBlank() }?.let {
                append(" Tone: ")
                append(it)
                append('.')
            }
        }
}

data class TtsAudioChunk(
    val data: ByteArray,
)
