package com.gromozeka.infrastructure.ai.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WhisperWavNormalizerTest {

    @Test
    fun normalizesStereoWavToWhisperInput() {
        val input = wav16BitStereo(sampleRate = 48_000, frames = 48_000)

        val output = WhisperWavNormalizer.normalize(
            audioData = input,
            mediaType = "audio/wav",
            fileExtension = "wav",
        )

        assertEquals("RIFF", output.ascii(0, 4))
        assertEquals("WAVE", output.ascii(8, 4))
        assertEquals(1, output.readLittleEndianShort(22))
        assertEquals(16_000, output.readLittleEndianInt(24))
        assertEquals(16, output.readLittleEndianShort(34))
        assertEquals(32_000, output.readLittleEndianInt(40))
        assertEquals(2_000, output.readLittleEndianShort(44).toShort().toInt())
    }

    @Test
    fun rejectsCompressedAudio() {
        assertFailsWith<IllegalArgumentException> {
            WhisperWavNormalizer.normalize(
                audioData = ByteArray(256),
                mediaType = "audio/mp4",
                fileExtension = "m4a",
            )
        }
    }

    private fun wav16BitStereo(sampleRate: Int, frames: Int): ByteArray {
        val channels = 2
        val dataSize = frames * channels * 2
        val output = ByteArray(44 + dataSize)
        output.writeAscii(0, "RIFF")
        output.writeLittleEndianInt(4, 36 + dataSize)
        output.writeAscii(8, "WAVE")
        output.writeAscii(12, "fmt ")
        output.writeLittleEndianInt(16, 16)
        output.writeLittleEndianShort(20, 1)
        output.writeLittleEndianShort(22, channels)
        output.writeLittleEndianInt(24, sampleRate)
        output.writeLittleEndianInt(28, sampleRate * channels * 2)
        output.writeLittleEndianShort(32, channels * 2)
        output.writeLittleEndianShort(34, 16)
        output.writeAscii(36, "data")
        output.writeLittleEndianInt(40, dataSize)

        var offset = 44
        repeat(frames) {
            output.writeLittleEndianShort(offset, 1_000)
            output.writeLittleEndianShort(offset + 2, 3_000)
            offset += 4
        }

        return output
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

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
