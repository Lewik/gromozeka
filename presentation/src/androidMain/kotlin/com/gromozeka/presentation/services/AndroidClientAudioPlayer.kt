package com.gromozeka.presentation.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidClientAudioPlayer(context: Context) : ClientAudioPlayer {
    private val applicationContext = context.applicationContext
    private val cacheDirectory = applicationContext.cacheDir
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
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
    ) = withContext(Dispatchers.IO) {
        require(sampleRate > 0) { "PCM sample rate must be positive" }
        require(channels == 1 || channels == 2) { "Android PCM playback supports mono or stereo audio" }
        require(bitsPerSample == 16) { "Android PCM playback supports only PCM16 audio" }

        val frameSizeBytes = channels * bitsPerSample / 8
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) {
            "Android audio output does not support sampleRate=$sampleRate channels=$channels PCM16"
        }
        val trackBufferSize = max(
            minBufferSize,
            PcmFrameBuffer.prebufferBytes(sampleRate, frameSizeBytes, PCM_TRACK_BUFFER_MILLIS),
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(speechAudioAttributes())
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Android PCM audio output could not be initialized")
        }

        val ownerJob = requireNotNull(currentCoroutineContext()[Job])
        var focusPlayback: AudioTrackPlayback? = null
        val focus = try {
            requestAudioFocus { focusPlayback?.stop() }
        } catch (error: Throwable) {
            track.release()
            throw error
        }
        val playback = AudioTrackPlayback(track, focus, ownerJob)
        focusPlayback = playback
        replacePlayback(playback)
        val frameBuffer = PcmFrameBuffer(
            frameSizeBytes = frameSizeBytes,
            startPrebufferBytes = PcmFrameBuffer.prebufferBytes(
                sampleRate,
                frameSizeBytes,
                PCM_START_PREBUFFER_MILLIS,
            ),
        )
        var started = false
        var writtenFrames = 0L
        try {
            chunks.collect { chunk ->
                val audioBytes = frameBuffer.push(chunk) ?: return@collect
                if (!started) {
                    track.play()
                    started = true
                }
                writtenFrames += playback.write(audioBytes, frameSizeBytes)
            }
            frameBuffer.finish()?.let { remainingAudioBytes ->
                if (!started) {
                    track.play()
                    started = true
                }
                writtenFrames += playback.write(remainingAudioBytes, frameSizeBytes)
            }
            if (started) playback.awaitPlayedFrames(writtenFrames)
        } finally {
            clearPlayback(playback)
            playback.finish()
        }
    }

    override fun stop() {
        val playback = synchronized(playbackLock) {
            activePlayback.also { activePlayback = null }
        }
        playback?.stop()
    }

    private suspend fun playAudioFile(data: ByteArray, fileExtension: String, volume: Float) =
        withContext(Dispatchers.IO) {
            val audioFile = File.createTempFile("gromozeka-audio-", ".$fileExtension", cacheDirectory)
            audioFile.writeBytes(data)
            try {
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer()
                    var focusPlayback: MediaPlayback? = null
                    val focus = runCatching {
                        requestAudioFocus { focusPlayback?.let(::completeMediaPlayback) }
                    }.getOrElse { error ->
                        player.release()
                        continuation.resumeWithException(error)
                        return@suspendCancellableCoroutine
                    }
                    val playback = MediaPlayback(player, focus, continuation)
                    focusPlayback = playback
                    replacePlayback(playback)
                    player.setOnCompletionListener { completeMediaPlayback(playback) }
                    player.setOnErrorListener { _, what, extra ->
                        completeMediaPlayback(
                            playback,
                            IllegalStateException("Android audio playback failed: what=$what extra=$extra"),
                        )
                        true
                    }
                    continuation.invokeOnCancellation {
                        completeMediaPlayback(playback, resumeContinuation = false)
                    }

                    runCatching {
                        player.setAudioAttributes(speechAudioAttributes())
                        player.setDataSource(audioFile.absolutePath)
                        player.setVolume(volume, volume)
                        player.prepare()
                        player.start()
                    }.onFailure { error ->
                        completeMediaPlayback(playback, error)
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
        previous?.stop()
    }

    private fun clearPlayback(playback: ActivePlayback) {
        synchronized(playbackLock) {
            if (activePlayback === playback) activePlayback = null
        }
    }

    private fun completeMediaPlayback(
        playback: MediaPlayback,
        error: Throwable? = null,
        resumeContinuation: Boolean = true,
    ) {
        clearPlayback(playback)
        playback.finish(error, resumeContinuation)
    }

    private fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_MEDIA
            }
        )
        .build()

    private fun requestAudioFocus(onLost: () -> Unit): AudioFocusHandle {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onLost()
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes())
                .setOnAudioFocusChangeListener(listener)
                .build()
            check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Android audio focus request was denied"
            }
            AudioFocusHandle { audioManager.abandonAudioFocusRequest(request) }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
            check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Android audio focus request was denied"
            }
            AudioFocusHandle {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
    }

    private interface ActivePlayback {
        fun stop()
    }

    private class MediaPlayback(
        private val player: MediaPlayer,
        private val focus: AudioFocusHandle,
        private val continuation: CancellableContinuation<Unit>,
    ) : ActivePlayback {
        private val completed = AtomicBoolean(false)

        override fun stop() {
            finish()
        }

        fun finish(error: Throwable? = null, resumeContinuation: Boolean = true) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
            focus.abandon()
            if (!resumeContinuation || !continuation.isActive) return
            if (error == null) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(error)
            }
        }
    }

    private class AudioTrackPlayback(
        private val track: AudioTrack,
        private val focus: AudioFocusHandle,
        private val ownerJob: Job,
    ) : ActivePlayback {
        private val completed = AtomicBoolean(false)

        override fun stop() {
            if (!completed.compareAndSet(false, true)) return
            releaseTrack()
            ownerJob.cancel(CancellationException("Android audio playback stopped"))
        }

        fun finish() {
            if (!completed.compareAndSet(false, true)) return
            releaseTrack()
        }

        fun write(data: ByteArray, frameSizeBytes: Int): Long {
            var offset = 0
            while (offset < data.size && !completed.get()) {
                val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (completed.get()) return offset.toLong() / frameSizeBytes
                    error("Android PCM audio write failed: code=$written")
                }
                check(written > 0) { "Android PCM audio write made no progress" }
                offset += written
            }
            return offset.toLong() / frameSizeBytes
        }

        suspend fun awaitPlayedFrames(writtenFrames: Long) {
            val timeoutMillis = writtenFrames * 1_000L / track.sampleRate + PLAYBACK_DRAIN_GRACE_MILLIS
            withTimeoutOrNull(timeoutMillis) {
                while (!completed.get() && currentCoroutineContext().isActive) {
                    val playedFrames = track.playbackHeadPosition.toLong() and 0xffffffffL
                    if (playedFrames >= writtenFrames) return@withTimeoutOrNull
                    delay(PLAYBACK_DRAIN_POLL_MILLIS)
                }
            }
        }

        private fun releaseTrack() {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            focus.abandon()
        }

        private companion object {
            const val PLAYBACK_DRAIN_POLL_MILLIS = 10L
            const val PLAYBACK_DRAIN_GRACE_MILLIS = 2_000L
        }
    }

    private fun interface AudioFocusHandle {
        fun abandon()
    }

    private companion object {
        const val PCM_START_PREBUFFER_MILLIS = 500
        const val PCM_TRACK_BUFFER_MILLIS = 1_000
    }
}
