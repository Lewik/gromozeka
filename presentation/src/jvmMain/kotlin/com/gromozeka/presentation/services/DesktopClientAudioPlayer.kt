package com.gromozeka.presentation.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

class DesktopClientAudioPlayer(
    private val clipFactory: () -> Clip = AudioSystem::getClip,
) : ClientAudioPlayer {
    private companion object {
        const val CLIP_COMPLETION_GRACE_MS = 5_000L
        const val PCM_START_PREBUFFER_MS = 500
        const val PCM_LINE_BUFFER_MS = 1_000
    }

    private val lock = Any()
    private var activeLine: SourceDataLine? = null
    private var activeProcess: Process? = null
    private var activeClip: Clip? = null

    override suspend fun playAudio(
        data: ByteArray,
        mediaType: String,
        fileExtension: String,
        volume: Float,
    ) = withContext(Dispatchers.IO) {
        require(volume in 0.0f..1.0f) { "Audio volume must be between 0.0 and 1.0" }
        val normalizedExtension = fileExtension.trim().trimStart('.').ifBlank { "mp3" }
        stop()
        if (normalizedExtension.equals("wav", ignoreCase = true) || mediaType.equals("audio/wav", ignoreCase = true)) {
            playWav(data, volume)
            return@withContext
        }
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
        val clip: Clip?
        synchronized(lock) {
            line = activeLine
            activeLine = null
            process = activeProcess
            activeProcess = null
            clip = activeClip
            activeClip = null
        }

        runCatching { line?.stop() }
        runCatching { line?.flush() }
        runCatching { line?.close() }
        runCatching { process?.destroy() }
        runCatching { clip?.stop() }
        runCatching { clip?.close() }
    }

    private suspend fun playWav(data: ByteArray, volume: Float) {
        val clip = clipFactory()
        val completed = CompletableDeferred<Unit>()
        val listener = LineListener { event ->
            when (event.type) {
                LineEvent.Type.STOP -> completed.complete(Unit)
                LineEvent.Type.CLOSE -> {
                    if (synchronized(lock) { activeClip === clip }) {
                        completed.completeExceptionally(IllegalStateException("Audio clip closed before playback completed"))
                    } else {
                        completed.complete(Unit)
                    }
                }
            }
        }
        synchronized(lock) {
            activeClip = clip
        }
        try {
            clip.addLineListener(listener)
            AudioSystem.getAudioInputStream(ByteArrayInputStream(data)).use { stream ->
                clip.open(stream)
            }
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                gain.value = if (volume == 0.0f) {
                    gain.minimum
                } else {
                    (20.0 * log10(volume.toDouble())).toFloat().coerceIn(gain.minimum, gain.maximum)
                }
            }
            currentCoroutineContext().ensureActive()
            val timeoutMillis = synchronized(lock) {
                if (activeClip !== clip) return
                if (clip.frameLength == 0) return
                val durationMicros = clip.microsecondLength
                check(durationMicros >= 0) { "Audio clip has no known duration" }
                clip.start()
                durationMicros / 1_000 + CLIP_COMPLETION_GRACE_MS
            }
            // Clip.start() can return before isRunning becomes true. STOP is the completion boundary.
            val finished = withTimeoutOrNull(timeoutMillis) {
                completed.await()
                true
            }
            check(finished == true) { "Audio clip did not finish within its playback deadline" }
        } finally {
            clip.removeLineListener(listener)
            synchronized(lock) {
                if (activeClip === clip) {
                    activeClip = null
                }
            }
            runCatching { clip.stop() }
            runCatching { clip.close() }
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
