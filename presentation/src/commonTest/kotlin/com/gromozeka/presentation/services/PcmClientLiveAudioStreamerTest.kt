package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.RemotePcmByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PcmClientLiveAudioStreamerTest {
    @Test
    fun `microphone PCM is delivered once in order without a retained recording`() = runTest {
        val capture = FakeCapture()
        var streamingStarts = 0
        val recorder = object : ClientAudioRecorder {
            override val supportsStreamingAudioChunks = true
            override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession = error("Must not retain a Live recording")
            override suspend fun startStreaming(scope: CoroutineScope): ClientAudioCaptureSession {
                streamingStarts += 1
                return capture
            }
        }
        val received = mutableListOf<RemotePcmAudioChunk>()
        val session = PcmClientLiveAudioStreamer(recorder).start(backgroundScope) { received += it }
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6)
        capture.chunks.send(first)
        capture.chunks.send(second)
        runCurrent()
        assertEquals(1, streamingStarts)
        assertEquals(listOf(0, 1), received.map { it.sequenceNumber })
        assertContentEquals(first, received[0].data)
        assertContentEquals(second, received[1].data)
        assertEquals(RemotePcmByteOrder.BIG_ENDIAN, received[0].byteOrder)
        assertEquals(16_000, received[0].sampleRate)
        session.stop()
        assertTrue(capture.stopped)
        assertEquals(2, received.size)
    }

    @Test
    fun `stop waits until the last queued PCM callback has finished`() = runTest {
        val capture = FakeCapture()
        val recorder = object : ClientAudioRecorder {
            override val supportsStreamingAudioChunks = true
            override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession = error("Must not retain Live audio")
            override suspend fun startStreaming(scope: CoroutineScope): ClientAudioCaptureSession = capture
        }
        val entered = CompletableDeferred<Unit>()
        val allowSend = CompletableDeferred<Unit>()
        var delivered = false
        val session = PcmClientLiveAudioStreamer(recorder).start(backgroundScope) {
            entered.complete(Unit)
            allowSend.await()
            delivered = true
        }
        capture.chunks.send(byteArrayOf(1, 2))
        runCurrent()
        assertTrue(entered.isCompleted)
        val stop = backgroundScope.launch { session.stop() }
        runCurrent()
        assertTrue(!stop.isCompleted)
        allowSend.complete(Unit)
        runCurrent()
        assertTrue(delivered)
        assertTrue(stop.isCompleted)
    }

    @Test
    fun `unsupported capture does not fall back to segmented recording`() = runTest {
        val recorder = object : ClientAudioRecorder {
            override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession = error("Segmented fallback must not run")
        }
        val streamer = PcmClientLiveAudioStreamer(recorder)
        assertTrue(streamer.unavailableReason != null)
        assertFailsWith<LocalizedTextException> { streamer.start(backgroundScope) {} }
    }

    private class FakeCapture : ClientAudioCaptureSession {
        val chunks = Channel<ByteArray>(32)
        override val audioChunks = chunks.receiveAsFlow()
        var stopped = false
        override suspend fun stop() { stopped = true; chunks.close() }
        override fun cancel() { stopped = true; chunks.cancel() }
    }
}
