package com.gromozeka.presentation.services

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
class IosClientAudioPlayer : ClientAudioPlayer {
    private var activePlayer: AVAudioPlayer? = null

    override suspend fun playAudio(
        data: ByteArray,
        mediaType: String,
        fileExtension: String,
        volume: Float,
    ) = withContext(Dispatchers.Default) {
        require(volume in 0.0f..1.0f) { "Audio volume must be between 0.0 and 1.0" }
        val normalizedExtension = fileExtension.trim().trimStart('.').ifBlank { "wav" }
        playAudioFile(data, normalizedExtension, volume)
    }

    override suspend fun playPcmStream(
        chunks: Flow<ByteArray>,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) = withContext(Dispatchers.Default) {
        val frameSizeBytes = max(1, channels * bitsPerSample / 8)
        val frameBuffer = PcmFrameBuffer(frameSizeBytes = frameSizeBytes, startPrebufferBytes = 0)
        var pcmData = ByteArray(0)

        chunks.collect { chunk ->
            val audioBytes = frameBuffer.push(chunk)
            if (audioBytes != null) {
                pcmData += audioBytes
            }
        }

        frameBuffer.finish()?.let { pcmData += it }
        if (pcmData.isEmpty()) {
            return@withContext
        }

        playAudioFile(
            data = pcmData.toPcmWav(sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample),
            fileExtension = "wav",
            volume = 1.0f,
        )
    }

    private suspend fun playAudioFile(data: ByteArray, fileExtension: String, volume: Float) {
        val fileUrl = NSURL.fileURLWithPath("${NSTemporaryDirectory()}gromozeka-tts-${kotlin.random.Random.nextLong()}.$fileExtension")
        writeFileBytes(fileUrl.path ?: error("iOS audio temp file path is missing"), data)
        var player: AVAudioPlayer? = null
        try {
            configureAudioSession()
            val createdPlayer = AVAudioPlayer(fileUrl, null)
            player = createdPlayer
            activePlayer?.stop()
            activePlayer = createdPlayer
            createdPlayer.volume = volume
            createdPlayer.prepareToPlay()
            check(createdPlayer.play()) { "Failed to start iOS audio playback" }
            while (createdPlayer.playing) {
                delay(50)
            }
        } finally {
            if (activePlayer === player) {
                activePlayer = null
                deactivateAudioSession()
            }
            runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }
        }
    }

    override fun stop() {
        activePlayer?.stop()
    }

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        check(session.setActive(active = true, error = null)) { "Failed to activate iOS playback audio session" }
    }

    private fun deactivateAudioSession() {
        AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFileBytes(path: String, data: ByteArray) {
    val file = fopen(path, "wb") ?: error("iOS audio temp file is not writable")
    try {
        data.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1u, data.size.toULong(), file).toInt()
            check(written == data.size) { "Failed to write complete iOS audio temp file" }
        }
    } finally {
        fclose(file)
    }
}
