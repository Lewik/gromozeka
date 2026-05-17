package com.gromozeka.presentation.services

class PcmFrameBuffer(
    private val frameSizeBytes: Int,
    private val startPrebufferBytes: Int,
) {
    init {
        require(frameSizeBytes > 0) { "PCM frame size must be positive" }
        require(startPrebufferBytes >= 0) { "PCM start prebuffer must not be negative" }
    }

    private var started = false
    private var startupBytes = ByteArray(0)
    private var frameRemainder = ByteArray(0)

    fun push(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null

        if (!started) {
            startupBytes += chunk
            if (startupBytes.size < startPrebufferBytes) return null

            started = true
            val output = wholeFrames(startupBytes)
            startupBytes = ByteArray(0)
            return output
        }

        return wholeFrames(chunk)
    }

    fun finish(): ByteArray? {
        if (!started) {
            started = true
            val output = wholeFrames(startupBytes)
            startupBytes = ByteArray(0)
            return output
        }

        frameRemainder = ByteArray(0)
        return null
    }

    private fun wholeFrames(bytes: ByteArray): ByteArray? {
        val data = if (frameRemainder.isEmpty()) bytes else frameRemainder + bytes
        val writableSize = data.size - (data.size % frameSizeBytes)
        frameRemainder = data.copyOfRange(writableSize, data.size)
        if (writableSize == 0) return null
        return data.copyOfRange(0, writableSize)
    }

    companion object {
        fun prebufferBytes(sampleRate: Int, frameSizeBytes: Int, prebufferMs: Int): Int {
            require(sampleRate > 0) { "PCM sample rate must be positive" }
            require(frameSizeBytes > 0) { "PCM frame size must be positive" }
            require(prebufferMs >= 0) { "PCM prebuffer duration must not be negative" }
            return sampleRate * frameSizeBytes * prebufferMs / 1000
        }
    }
}
