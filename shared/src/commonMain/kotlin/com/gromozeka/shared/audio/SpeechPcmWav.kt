package com.gromozeka.shared.audio

object SpeechPcmWav {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val MEDIA_TYPE = "audio/wav"
    const val FILE_EXTENSION = "wav"

    private const val PCM_FORMAT = 1
    private const val HEADER_SIZE = 44
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val BLOCK_ALIGN = CHANNELS * BYTES_PER_SAMPLE
    private const val BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN

    fun encode(pcmLittleEndian: ByteArray): ByteArray {
        require(pcmLittleEndian.size % BLOCK_ALIGN == 0) {
            "PCM16 input size must be aligned to $BLOCK_ALIGN bytes: ${pcmLittleEndian.size}"
        }

        val output = ByteArray(HEADER_SIZE + pcmLittleEndian.size)
        output.writeAscii(0, "RIFF")
        output.writeLittleEndianInt(4, 36 + pcmLittleEndian.size)
        output.writeAscii(8, "WAVE")
        output.writeAscii(12, "fmt ")
        output.writeLittleEndianInt(16, 16)
        output.writeLittleEndianShort(20, PCM_FORMAT)
        output.writeLittleEndianShort(22, CHANNELS)
        output.writeLittleEndianInt(24, SAMPLE_RATE)
        output.writeLittleEndianInt(28, BYTE_RATE)
        output.writeLittleEndianShort(32, BLOCK_ALIGN)
        output.writeLittleEndianShort(34, BITS_PER_SAMPLE)
        output.writeAscii(36, "data")
        output.writeLittleEndianInt(40, pcmLittleEndian.size)
        pcmLittleEndian.copyInto(output, destinationOffset = HEADER_SIZE)
        return output
    }

    fun requireValid(audioData: ByteArray) {
        require(audioData.size >= HEADER_SIZE) { "WAV input is too small: ${audioData.size} bytes" }
        require(audioData.ascii(0, 4) == "RIFF") { "WAV input must start with RIFF" }
        require(audioData.ascii(8, 4) == "WAVE") { "WAV input must contain WAVE header" }

        var offset = 12
        var formatFound = false
        var dataFound = false

        while (offset + 8 <= audioData.size) {
            val chunkId = audioData.ascii(offset, 4)
            val chunkSize = audioData.readLittleEndianUInt(offset + 4)
            require(chunkSize <= Int.MAX_VALUE) { "WAV chunk '$chunkId' is too large: $chunkSize bytes" }

            val chunkDataOffset = offset + 8
            val chunkSizeInt = chunkSize.toInt()
            val chunkEnd = chunkDataOffset.toLong() + chunkSize
            require(chunkEnd <= audioData.size) {
                "Invalid WAV chunk '$chunkId' size=$chunkSize at offset=$offset"
            }

            when (chunkId) {
                "fmt " -> {
                    require(chunkSizeInt >= 16) { "WAV fmt chunk is too small: $chunkSizeInt bytes" }
                    require(audioData.readLittleEndianShort(chunkDataOffset) == PCM_FORMAT) {
                        "Speech WAV must contain uncompressed PCM"
                    }
                    require(audioData.readLittleEndianShort(chunkDataOffset + 2) == CHANNELS) {
                        "Speech WAV must be mono"
                    }
                    require(audioData.readLittleEndianInt(chunkDataOffset + 4) == SAMPLE_RATE) {
                        "Speech WAV sample rate must be $SAMPLE_RATE Hz"
                    }
                    require(audioData.readLittleEndianInt(chunkDataOffset + 8) == BYTE_RATE) {
                        "Speech WAV byte rate must be $BYTE_RATE"
                    }
                    require(audioData.readLittleEndianShort(chunkDataOffset + 12) == BLOCK_ALIGN) {
                        "Speech WAV block alignment must be $BLOCK_ALIGN bytes"
                    }
                    require(audioData.readLittleEndianShort(chunkDataOffset + 14) == BITS_PER_SAMPLE) {
                        "Speech WAV must use $BITS_PER_SAMPLE-bit samples"
                    }
                    formatFound = true
                }

                "data" -> {
                    require(chunkSizeInt % BLOCK_ALIGN == 0) {
                        "Speech WAV data size must be aligned to $BLOCK_ALIGN bytes: $chunkSizeInt"
                    }
                    dataFound = true
                }
            }

            offset = chunkDataOffset + chunkSizeInt + (chunkSizeInt and 1)
        }

        require(formatFound) { "WAV input is missing fmt chunk" }
        require(dataFound) { "WAV input is missing data chunk" }
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

    private fun ByteArray.readLittleEndianUInt(offset: Int): Long =
        readLittleEndianInt(offset).toLong() and 0xffffffffL

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.readLittleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.encodeToByteArray().copyInto(this, destinationOffset = offset)
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}
