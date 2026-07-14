package com.gromozeka.shared.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeechPcmWavTest {
    @Test
    fun encodesCanonicalSpeechWav() {
        val pcm = byteArrayOf(0x34, 0x12, 0xCC.toByte(), 0xED.toByte())

        val wav = SpeechPcmWav.encode(pcm)

        SpeechPcmWav.requireValid(wav)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals(1, wav.readLittleEndianShort(22))
        assertEquals(16_000, wav.readLittleEndianInt(24))
        assertEquals(16, wav.readLittleEndianShort(34))
        assertEquals(pcm.size, wav.readLittleEndianInt(40))
        assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun rejectsNonCanonicalSampleRate() {
        val wav = SpeechPcmWav.encode(byteArrayOf(0, 0))
        wav.writeLittleEndianInt(24, 48_000)

        assertFailsWith<IllegalArgumentException> {
            SpeechPcmWav.requireValid(wav)
        }
    }

    @Test
    fun rejectsUnalignedPcm() {
        assertFailsWith<IllegalArgumentException> {
            SpeechPcmWav.encode(byteArrayOf(0))
        }
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

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
