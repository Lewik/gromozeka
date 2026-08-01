package com.gromozeka.presentation.services

import android.content.Context
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AndroidClientAudioPlayer(context: Context) : ClientAudioPlayer {
    private val cacheDirectory = context.applicationContext.cacheDir
    private val playbackLock = Any()
    private var activePlayback: ActivePlayback? = null

    override suspend fun playAudio(
        data: ByteArray,
        mediaType: String,
        fileExtension: String,
        volume: Float,
    ) {
        if (data.isEmpty()) return
        require(volume in 0.0f..1.0f) { "Audio volume must be between 0.0 and 1.0" }

        val normalizedExtension = fileExtension
            .trim()
            .trimStart('.')
            .filter(Char::isLetterOrDigit)
            .ifBlank { "audio" }
        playAudioFile(data, normalizedExtension, volume)
    }

    override suspend fun playPcmStream(
        chunks: Flow<ByteArray>,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        val frameSizeBytes = max(1, channels * bitsPerSample / 8)
        val frameBuffer = PcmFrameBuffer(frameSizeBytes = frameSizeBytes, startPrebufferBytes = 0)
        val pcmData = ByteArrayOutputStream()
        chunks.collect { chunk -> frameBuffer.push(chunk)?.let(pcmData::write) }
        frameBuffer.finish()?.let(pcmData::write)
        if (pcmData.size() == 0) return

        playAudioFile(
            data = pcmData.toByteArray().toPcmWav(sampleRate, channels, bitsPerSample),
            fileExtension = "wav",
            volume = 1.0f,
        )
    }

    override fun stop() {
        val playback = synchronized(playbackLock) {
            activePlayback.also { activePlayback = null }
        }
        playback?.complete()
    }

    private suspend fun playAudioFile(data: ByteArray, fileExtension: String, volume: Float) =
        withContext(Dispatchers.IO) {
            val audioFile = File.createTempFile("gromozeka-audio-", ".$fileExtension", cacheDirectory)
            audioFile.writeBytes(data)
            try {
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer()
                    val playback = ActivePlayback(player, continuation)
                    replacePlayback(playback)
                    player.setOnCompletionListener { completePlayback(playback) }
                    player.setOnErrorListener { _, what, extra ->
                        completePlayback(
                            playback,
                            IllegalStateException("Android audio playback failed: what=$what extra=$extra"),
                        )
                        true
                    }
                    continuation.invokeOnCancellation { completePlayback(playback, resumeContinuation = false) }

                    runCatching {
                        player.setDataSource(audioFile.absolutePath)
                        player.setVolume(volume, volume)
                        player.prepare()
                        player.start()
                    }.onFailure { error ->
                        completePlayback(playback, error)
                    }
                }
            } finally {
                audioFile.delete()
            }
        }

    private fun replacePlayback(playback: ActivePlayback) {
        val previous = synchronized(playbackLock) {
            activePlayback.also { activePlayback = playback }
        }
        previous?.complete()
    }

    private fun completePlayback(
        playback: ActivePlayback,
        error: Throwable? = null,
        resumeContinuation: Boolean = true,
    ) {
        synchronized(playbackLock) {
            if (activePlayback === playback) activePlayback = null
        }
        playback.complete(error, resumeContinuation)
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private inner class ActivePlayback(
        private val player: MediaPlayer,
        private val continuation: CancellableContinuation<Unit>,
    ) {
        private val completed = AtomicBoolean(false)

        fun complete(error: Throwable? = null, resumeContinuation: Boolean = true) {
            if (!completed.compareAndSet(false, true)) return
            releasePlayer(player)
            if (!resumeContinuation || !continuation.isActive) return
            if (error == null) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(error)
            }
        }
    }
}
