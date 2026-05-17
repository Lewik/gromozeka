package com.gromozeka.presentation.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

class DesktopClientAudioPlayer : ClientAudioPlayer {
    private companion object {
        const val PCM_START_PREBUFFER_MS = 500
        const val PCM_LINE_BUFFER_MS = 1_000
    }

    override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String) = withContext(Dispatchers.IO) {
        val normalizedExtension = fileExtension.trim().trimStart('.').ifBlank { "mp3" }
        val audioFile = File.createTempFile("gromozeka-tts", ".$normalizedExtension")
        try {
            audioFile.writeBytes(data)
            playAudioFile(audioFile)
        } finally {
            audioFile.delete()
        }
    }

    override suspend fun playPcmStream(
        chunks: Flow<ByteArray>,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) = withContext(Dispatchers.IO) {
        val format = AudioFormat(sampleRate.toFloat(), bitsPerSample, channels, true, false)
        val frameSize = format.frameSize
        require(frameSize > 0) { "Invalid PCM frame size: $frameSize" }
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, PcmFrameBuffer.prebufferBytes(sampleRate, frameSize, PCM_LINE_BUFFER_MS))
        val buffer = PcmFrameBuffer(
            frameSizeBytes = frameSize,
            startPrebufferBytes = PcmFrameBuffer.prebufferBytes(sampleRate, frameSize, PCM_START_PREBUFFER_MS),
        )
        var started = false
        try {
            chunks.collect { chunk ->
                val audioBytes = buffer.push(chunk) ?: return@collect
                if (!started) {
                    line.start()
                    started = true
                }
                line.write(audioBytes, 0, audioBytes.size)
            }
            val remainingAudioBytes = buffer.finish()
            if (remainingAudioBytes != null && !started) {
                line.start()
                started = true
            }
            if (remainingAudioBytes != null) {
                line.write(remainingAudioBytes, 0, remainingAudioBytes.size)
            }
            if (started) line.drain()
        } finally {
            if (started) line.stop()
            line.close()
        }
    }

    private fun playAudioFile(audioFile: File) {
        val osName = System.getProperty("os.name").lowercase()
        val command = when {
            osName.contains("mac") -> listOf("afplay", audioFile.absolutePath)
            osName.contains("windows") -> listOf("cmd", "/c", "start", "/wait", "", audioFile.absolutePath)
            else -> listOf("xdg-open", audioFile.absolutePath)
        }
        val process = ProcessBuilder(command).start()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "Audio player exited with code $exitCode" }
    }
}
