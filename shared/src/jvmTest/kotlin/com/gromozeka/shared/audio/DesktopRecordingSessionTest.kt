package com.gromozeka.shared.audio

import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopRecordingSessionTest {
    @Test
    fun `streaming buffer retains two minutes of default PCM audio`() {
        val config = AudioConfig(
            sampleRate = 16_000,
            channels = 1,
            bitDepth = 16,
            chunkSizeBytes = 2_048,
            retainRecording = false,
        )

        val capacity = desktopStreamingAudioBufferCapacity(config)

        assertEquals(1_875, capacity)
        assertEquals(
            config.sampleRate * config.channels * (config.bitDepth / 8) * DESKTOP_STREAMING_AUDIO_BUFFER_SECONDS,
            capacity * config.chunkSizeBytes,
        )
    }

    @Test
    fun `streaming capture reports overflow without blocking or replacing queued audio`() {
        val channel = Channel<ByteArray>(capacity = 1)
        val retained = byteArrayOf(1, 2)
        publishDesktopAudioChunk(channel, retained, retainRecording = false)

        val error = assertFailsWith<IllegalStateException> {
            publishDesktopAudioChunk(channel, byteArrayOf(3, 4), retainRecording = false)
        }

        assertEquals("Live microphone audio buffer overflow", error.message)
        assertContentEquals(retained, channel.tryReceive().getOrThrow())
    }
}
