package com.gromozeka.presentation.services

internal fun ByteArray.toPcmWav(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
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
