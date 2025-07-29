package com.gromozeka.bot.services


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions
import org.springframework.ai.openai.api.OpenAiAudioApi.TranscriptResponseFormat
import org.springframework.core.io.FileSystemResource
import java.io.File
import javax.sound.sampled.*


class SttService(private val openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel) {
    private lateinit var outputFile: File
    private var line: TargetDataLine? = null

    suspend fun startRecording() = withContext(Dispatchers.IO) {
        outputFile = File.createTempFile("recorded", ".wav")

        val format = AudioFormat(16000f, 16, 1, true, true)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            error("Line not supported")
        }

        line = AudioSystem.getLine(info) as TargetDataLine
        line?.open(format)
        line?.start()

        AudioSystem.write(AudioInputStream(line), AudioFileFormat.Type.WAVE, outputFile)
    }

    suspend fun stopAndTranscribe(): String = withContext(Dispatchers.IO) {
        line?.stop()
        line?.close()

        val text = try {
            val transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
                .responseFormat(TranscriptResponseFormat.TEXT)
                .temperature(0f)
                .build()


            val transcriptionRequest =
                AudioTranscriptionPrompt(FileSystemResource(this@SttService.outputFile), transcriptionOptions)
            openAiAudioTranscriptionModel.call(transcriptionRequest).result.output

        } catch (e: Exception) {
            println(e.toString())
            ""
        }
        return@withContext text
    }
}