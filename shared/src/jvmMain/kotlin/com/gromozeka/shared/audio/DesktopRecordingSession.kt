package com.gromozeka.shared.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.coroutines.coroutineContext

class DesktopRecordingSession(
    private val config: AudioConfig,
    private val scope: CoroutineScope,
) : RecordingSession {

    private val audioChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val audioBuffer = ByteArrayOutputStream()

    private var targetDataLine: TargetDataLine? = null
    private var recordingJob: Job? = null

    override val audioChunks: Flow<ByteArray> = audioChannel.consumeAsFlow()

    fun start() {
        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                setupAudioLine()
                recordingLoop()
            } catch (e: Exception) {
                audioChannel.close(e)
                throw e
            } finally {
                cleanup()
            }
        }
    }

    private suspend fun setupAudioLine() {
        val format = AudioFormat(
            config.sampleRate.toFloat(),
            config.bitDepth,
            config.channels,
            true,  // signed
            true   // bigEndian
        )

        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw UnsupportedOperationException("Audio line not supported: $format")
        }

        targetDataLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format)
            start()
        }
    }

    private suspend fun recordingLoop() {
        val chunkBuffer = ByteArray(config.chunkSizeBytes)

        while (coroutineContext.isActive) {
            val bytesRead = targetDataLine?.read(chunkBuffer, 0, chunkBuffer.size) ?: 0

            if (bytesRead > 0) {
                val chunk = chunkBuffer.copyOfRange(0, bytesRead)

                audioBuffer.write(chunk)
                audioChannel.send(chunk)
            }

            yield()
        }

        audioChannel.close()
    }

    override suspend fun stop(): ByteArray {
        recordingJob?.cancel()
        recordingJob?.join()

        val rawAudio = audioBuffer.toByteArray()

        return when (config.outputFormat) {
            AudioOutputFormat.WAV -> createWavFile(rawAudio)
            AudioOutputFormat.AU -> createAuFile(rawAudio)
            AudioOutputFormat.AIFF -> createAiffFile(rawAudio)
            AudioOutputFormat.RAW_PCM -> rawAudio
        }
    }

    override fun cancel() {
        recordingJob?.cancel()
        audioChannel.close()
        cleanup()
    }

    private fun cleanup() {
        targetDataLine?.apply {
            stop()
            close()
        }
        targetDataLine = null
    }

    private fun createWavFile(rawAudio: ByteArray): ByteArray {
        val format = AudioFormat(
            config.sampleRate.toFloat(),
            config.bitDepth,
            config.channels,
            true,
            true
        )

        return ByteArrayOutputStream().use { output ->
            val audioInputStream = AudioInputStream(
                rawAudio.inputStream(),
                format,
                rawAudio.size.toLong() / format.frameSize
            )

            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, output)
            output.toByteArray()
        }
    }

    private fun createAuFile(rawAudio: ByteArray): ByteArray {
        val format = AudioFormat(
            config.sampleRate.toFloat(),
            config.bitDepth,
            config.channels,
            true,
            true
        )

        return ByteArrayOutputStream().use { output ->
            val audioInputStream = AudioInputStream(
                rawAudio.inputStream(),
                format,
                rawAudio.size.toLong() / format.frameSize
            )

            AudioSystem.write(audioInputStream, AudioFileFormat.Type.AU, output)
            output.toByteArray()
        }
    }

    private fun createAiffFile(rawAudio: ByteArray): ByteArray {
        val format = AudioFormat(
            config.sampleRate.toFloat(),
            config.bitDepth,
            config.channels,
            true,
            true
        )

        return ByteArrayOutputStream().use { output ->
            val audioInputStream = AudioInputStream(
                rawAudio.inputStream(),
                format,
                rawAudio.size.toLong() / format.frameSize
            )

            AudioSystem.write(audioInputStream, AudioFileFormat.Type.AIFF, output)
            output.toByteArray()
        }
    }
}