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
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
class IosClientAudioPlayer : ClientAudioPlayer {
    override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String) = withContext(Dispatchers.Default) {
        val normalizedExtension = fileExtension.trim().trimStart('.').ifBlank { "wav" }
        playAudioFile(data, normalizedExtension)
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
            data = pcmData.toWav(sampleRate = sampleRate, channels = channels, bitsPerSample = bitsPerSample),
            fileExtension = "wav",
        )
    }

    private suspend fun playAudioFile(data: ByteArray, fileExtension: String) {
        configureAudioSession()
        val fileUrl = NSURL.fileURLWithPath("${NSTemporaryDirectory()}gromozeka-tts-${kotlin.random.Random.nextLong()}.$fileExtension")
        writeFileBytes(fileUrl.path ?: error("iOS audio temp file path is missing"), data)
        try {
            val player = AVAudioPlayer(fileUrl, null) ?: error("Failed to create iOS audio player")
            player.prepareToPlay()
            check(player.play()) { "Failed to start iOS audio playback" }
            while (player.playing) {
                delay(50)
            }
        } finally {
            runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }
        }
    }

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
    }
}

private fun ByteArray.toWav(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    require(sampleRate > 0) { "WAV sample rate must be positive" }
    require(channels > 0) { "WAV channel count must be positive" }
    require(bitsPerSample > 0) { "WAV bits per sample must be positive" }

    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteArray(44)
    header.writeAscii(0, "RIFF")
    header.writeIntLe(4, 36 + size)
    header.writeAscii(8, "WAVE")
    header.writeAscii(12, "fmt ")
    header.writeIntLe(16, 16)
    header.writeShortLe(20, 1)
    header.writeShortLe(22, channels)
    header.writeIntLe(24, sampleRate)
    header.writeIntLe(28, byteRate)
    header.writeShortLe(32, blockAlign)
    header.writeShortLe(34, bitsPerSample)
    header.writeAscii(36, "data")
    header.writeIntLe(40, size)
    return header + this
}

private fun ByteArray.writeAscii(offset: Int, value: String) {
    value.encodeToByteArray().forEachIndexed { index, byte -> this[offset + index] = byte }
}

private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
    this[offset + 2] = (value shr 16).toByte()
    this[offset + 3] = (value shr 24).toByte()
}

private fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
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
