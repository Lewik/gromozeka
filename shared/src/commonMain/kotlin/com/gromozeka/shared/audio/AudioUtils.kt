package com.gromozeka.shared.audio

object AudioUtils {

    /**
     * Calculate audio duration in seconds using mathematical approach from raw PCM data size
     */
    fun calculateDurationFromRawPcm(rawPcmSizeBytes: Int, config: AudioConfig): Double {
        val bytesPerSecond = config.sampleRate * config.channels * (config.bitDepth / 8)
        return rawPcmSizeBytes.toDouble() / bytesPerSecond
    }

    /**
     * Parse WAV file header to extract duration
     * Returns null if parsing fails
     */
    fun parseWavDuration(audioData: ByteArray): Double? {
        if (audioData.size < 44) return null

        try {
            // Check RIFF header
            if (audioData.sliceArray(0..3).decodeToString() != "RIFF") return null
            if (audioData.sliceArray(8..11).decodeToString() != "WAVE") return null

            // Find data chunk
            var offset = 12
            while (offset + 8 < audioData.size) {
                val chunkId = audioData.sliceArray(offset..offset + 3).decodeToString()
                val chunkSize = readLittleEndianInt(audioData, offset + 4)

                if (chunkId == "data") {
                    // Found data chunk - extract format info
                    val sampleRate = readLittleEndianInt(audioData, 24)
                    val channels = readLittleEndianShort(audioData, 22)
                    val bitDepth = readLittleEndianShort(audioData, 34)

                    val bytesPerSecond = sampleRate * channels * (bitDepth / 8)
                    return chunkSize.toDouble() / bytesPerSecond
                }

                offset += 8 + chunkSize
            }
        } catch (e: Exception) {
            // Parsing failed
        }

        return null
    }

    /**
     * Parse AU file header to extract duration
     * Returns null if parsing fails
     */
    fun parseAuDuration(audioData: ByteArray): Double? {
        if (audioData.size < 24) return null

        try {
            // Check AU magic number
            val magic = readBigEndianInt(audioData, 0)
            if (magic != 0x2e736e64) return null // ".snd"

            val dataSize = readBigEndianInt(audioData, 8)
            val sampleRate = readBigEndianInt(audioData, 16)
            val channels = readBigEndianInt(audioData, 20)

            // Assume 16-bit for calculation
            val bytesPerSecond = sampleRate * channels * 2
            return dataSize.toDouble() / bytesPerSecond
        } catch (e: Exception) {
            // Parsing failed
        }

        return null
    }

    /**
     * Parse AIFF file header to extract duration
     * Returns null if parsing fails
     */
    fun parseAiffDuration(audioData: ByteArray): Double? {
        if (audioData.size < 54) return null

        try {
            // Check FORM header
            if (audioData.sliceArray(0..3).decodeToString() != "FORM") return null
            if (audioData.sliceArray(8..11).decodeToString() != "AIFF") return null

            // Find COMM chunk for format info
            var offset = 12
            var sampleRate = 0
            var channels = 0
            var bitDepth = 0

            while (offset + 8 < audioData.size) {
                val chunkId = audioData.sliceArray(offset..offset + 3).decodeToString()
                val chunkSize = readBigEndianInt(audioData, offset + 4)

                if (chunkId == "COMM") {
                    channels = readBigEndianShort(audioData, offset + 8)
                    bitDepth = readBigEndianShort(audioData, offset + 14)
                    // Sample rate is in IEEE 754 extended precision format - simplified extraction
                    sampleRate = readBigEndianInt(audioData, offset + 18) shr 16
                } else if (chunkId == "SSND" && sampleRate > 0) {
                    // Found sound data chunk
                    val dataSize = chunkSize - 8 // Subtract SSND header
                    val bytesPerSecond = sampleRate * channels * (bitDepth / 8)
                    return dataSize.toDouble() / bytesPerSecond
                }

                offset += 8 + chunkSize
                if (chunkSize % 2 != 0) offset++ // AIFF chunks are padded to even byte boundaries
            }
        } catch (e: Exception) {
            // Parsing failed
        }

        return null
    }

    /**
     * Universal audio duration calculation with format-specific parsing and fallback
     */
    fun getAudioDuration(audioData: ByteArray, format: AudioOutputFormat, config: AudioConfig): Double {
        return when (format) {
            AudioOutputFormat.RAW_PCM -> calculateDurationFromRawPcm(audioData.size, config)

            AudioOutputFormat.WAV -> {
                parseWavDuration(audioData)
                    ?: calculateDurationFromRawPcm(maxOf(0, audioData.size - 44), config)
            }

            AudioOutputFormat.AU -> {
                parseAuDuration(audioData)
                    ?: calculateDurationFromRawPcm(maxOf(0, audioData.size - 24), config)
            }

            AudioOutputFormat.AIFF -> {
                parseAiffDuration(audioData)
                    ?: calculateDurationFromRawPcm(maxOf(0, audioData.size - 54), config)
            }
        }
    }

    // Helper functions for byte order conversion
    private fun readLittleEndianInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLittleEndianShort(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun readBigEndianShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }
}

/**
 * Extension function for convenient duration calculation
 */
fun ByteArray.getAudioDuration(format: AudioOutputFormat, config: AudioConfig): Double {
    return AudioUtils.getAudioDuration(this, format, config)
}

/**
 * Check if audio meets minimum duration requirements
 */
fun ByteArray.isAudioLongEnough(format: AudioOutputFormat, config: AudioConfig, minSeconds: Double = 0.1): Boolean {
    return this.getAudioDuration(format, config) >= minSeconds
}