package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PcmFrameBufferTest {
    @Test
    fun waitsForStartPrebufferBeforeEmittingAudio() {
        val buffer = PcmFrameBuffer(frameSizeBytes = 2, startPrebufferBytes = 6)

        assertNull(buffer.push(byteArrayOf(1, 2)))
        assertNull(buffer.push(byteArrayOf(3, 4, 5)))

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            buffer.push(byteArrayOf(6)),
        )
    }

    @Test
    fun preservesIncompleteFrameBetweenChunks() {
        val buffer = PcmFrameBuffer(frameSizeBytes = 2, startPrebufferBytes = 0)

        assertNull(buffer.push(byteArrayOf(1)))
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            buffer.push(byteArrayOf(2, 3, 4, 5)),
        )
        assertContentEquals(
            byteArrayOf(5, 6),
            buffer.push(byteArrayOf(6)),
        )
    }

    @Test
    fun finishFlushesOnlyWholeFramesForShortStream() {
        val buffer = PcmFrameBuffer(frameSizeBytes = 2, startPrebufferBytes = 10)

        assertNull(buffer.push(byteArrayOf(1, 2, 3)))

        assertContentEquals(byteArrayOf(1, 2), buffer.finish())
        assertNull(buffer.finish())
    }

    @Test
    fun calculatesPrebufferBytesFromDuration() {
        assertEquals(
            8640,
            PcmFrameBuffer.prebufferBytes(sampleRate = 24_000, frameSizeBytes = 2, prebufferMs = 180),
        )
    }
}
