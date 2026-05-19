package com.gromozeka.presentation.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class DesktopClientAudioPlayer : ClientAudioPlayer {
    private companion object {
        const val PCM_START_PREBUFFER_MS = 500
        const val PCM_LINE_BUFFER_MS = 1_000
    }

    private val lock = Any()
    private var activeLine: SourceDataLine? = null
    private var activeProcess: Process? = null

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
        synchronized(lock) {
            activeLine = line
        }
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
            synchronized(lock) {
                if (activeLine === line) {
                    activeLine = null
                }
            }
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
        }
    }

    override fun stop() {
        val line: SourceDataLine?
        val process: Process?
        synchronized(lock) {
            line = activeLine
            activeLine = null
            process = activeProcess
            activeProcess = null
        }

        runCatching { line?.stop() }
        runCatching { line?.flush() }
        runCatching { line?.close() }
        runCatching { process?.destroy() }
    }

    private fun playAudioFile(audioFile: File) {
        val osName = System.getProperty("os.name").lowercase()
        val command = when {
            osName.contains("mac") -> listOf("afplay", audioFile.absolutePath)
            osName.contains("windows") -> listOf("cmd", "/c", "start", "/wait", "", audioFile.absolutePath)
            else -> listOf("xdg-open", audioFile.absolutePath)
        }
        val process = ProcessBuilder(command).start()
        synchronized(lock) {
            activeProcess = process
        }
        try {
            val exitCode = process.waitFor()
            require(exitCode == 0) { "Audio player exited with code $exitCode" }
        } finally {
            synchronized(lock) {
                if (activeProcess === process) {
                    activeProcess = null
                }
            }
        }
    }
}
