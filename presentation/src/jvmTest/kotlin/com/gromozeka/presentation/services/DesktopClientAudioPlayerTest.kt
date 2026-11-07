package com.gromozeka.presentation.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopClientAudioPlayerTest {
    @Test
    fun delayedNativeStartDoesNotCloseTheClipBeforePlayback() = runBlocking {
        val native = TestClip()
        val player = DesktopClientAudioPlayer { native.clip }
        val playback = async { player.playAudio(wav(), "audio/wav", "wav", 0f) }
        try {
            withTimeout(2_000) { native.startRequested.await() }
            assertNull(withTimeoutOrNull(200) { native.closed.await() })
            assertFalse(playback.isCompleted)
            assertTrue(native.isOpen)
            native.started()
            assertFalse(playback.isCompleted)
            native.finished()
            withTimeout(2_000) { playback.await() }
            assertFalse(native.isOpen)
            assertTrue(native.listeners.isEmpty())
        } finally {
            playback.cancelAndJoin()
        }
    }

    @Test
    fun stopBeforeTheNativeStartEventReleasesPlayback() = runBlocking {
        val native = TestClip()
        val player = DesktopClientAudioPlayer { native.clip }
        val playback = async { player.playAudio(wav(), "audio/wav", "wav", 0f) }
        try {
            withTimeout(2_000) { native.startRequested.await() }
            player.stop()
            withTimeout(2_000) { playback.await() }
            assertFalse(native.isOpen)
            assertTrue(native.listeners.isEmpty())
        } finally {
            playback.cancelAndJoin()
        }
    }

    @Test
    fun cancellationClosesTheClipAndRemovesItsListener() = runBlocking {
        val native = TestClip()
        val player = DesktopClientAudioPlayer { native.clip }
        val playback = async { player.playAudio(wav(), "audio/wav", "wav", 0f) }
        withTimeout(2_000) { native.startRequested.await() }
        native.started()

        withTimeout(2_000) { playback.cancelAndJoin() }

        assertTrue(playback.isCancelled)
        assertFalse(native.isOpen)
        assertTrue(native.listeners.isEmpty())
    }

    @Test
    fun stopDuringOpenCannotStartTheClipAfterwards() = runBlocking {
        val openGate = CountDownLatch(1)
        val native = TestClip(openGate = openGate)
        val player = DesktopClientAudioPlayer { native.clip }
        val playback = async { player.playAudio(wav(), "audio/wav", "wav", 0f) }
        try {
            withTimeout(2_000) { native.openEntered.await() }
            player.stop()
            openGate.countDown()
            withTimeout(2_000) { playback.await() }
            assertEquals(0, native.startCount.get())
            assertFalse(native.isOpen)
            assertTrue(native.listeners.isEmpty())
        } finally {
            openGate.countDown()
            playback.cancelAndJoin()
        }
    }

    @Test
    fun anUnexpectedNativeCloseIsNotReportedAsCompletedPlayback() = runBlocking {
        supervisorScope {
            val native = TestClip()
            val player = DesktopClientAudioPlayer { native.clip }
            val playback = async { player.playAudio(wav(), "audio/wav", "wav", 0f) }
            try {
                withTimeout(2_000) { native.startRequested.await() }
                native.clip.close()
                val failure = assertFailsWith<IllegalStateException> {
                    withTimeout(2_000) { playback.await() }
                }
                assertEquals("Audio clip closed before playback completed", failure.message)
                assertTrue(native.listeners.isEmpty())
            } finally {
                playback.cancelAndJoin()
            }
        }
    }

    @Test
    fun aMissingCompletionEventFailsWithoutWaitingForever() = runBlocking {
        supervisorScope {
            val native = TestClip()
            val player = DesktopClientAudioPlayer { native.clip }
            val failure = assertFailsWith<IllegalStateException> {
                withTimeout(8_000) { player.playAudio(wav(), "audio/wav", "wav", 0f) }
            }
            assertEquals("Audio clip did not finish within its playback deadline", failure.message)
            assertFalse(native.isOpen)
            assertTrue(native.listeners.isEmpty())
        }
    }

    @Test
    fun anEmptyClipDoesNotWaitForEventsThatCannotOccur() = runBlocking {
        val native = TestClip(frameLength = 0)
        val player = DesktopClientAudioPlayer { native.clip }

        withTimeout(2_000) { player.playAudio(wav(), "audio/wav", "wav", 0f) }

        assertEquals(0, native.startCount.get())
        assertFalse(native.isOpen)
        assertTrue(native.listeners.isEmpty())
    }

    private fun wav(): ByteArray = ByteArrayOutputStream().use { output ->
        AudioInputStream(ByteArrayInputStream(ByteArray(160)), AudioFormat(8_000f, 16, 1, true, false), 80).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, output)
        }
        output.toByteArray()
    }

    private class TestClip(
        private val openGate: CountDownLatch? = null,
        private val frameLength: Int = 80,
    ) {
        val listeners = CopyOnWriteArrayList<LineListener>()
        val openEntered = CompletableDeferred<Unit>()
        val startRequested = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val startCount = AtomicInteger()
        @Volatile var isOpen = false
        @Volatile private var running = false
        private var position = 0L

        val clip: Clip = Proxy.newProxyInstance(Clip::class.java.classLoader, arrayOf(Clip::class.java)) { proxy, method, args ->
            when (method.name) {
                "open" -> {
                    openEntered.complete(Unit)
                    check(openGate?.await(5, TimeUnit.SECONDS) != false) { "Test did not release clip.open" }
                    isOpen = true
                    null
                }
                "start" -> {
                    check(isOpen)
                    startCount.incrementAndGet()
                    startRequested.complete(Unit)
                    null
                }
                "isOpen" -> isOpen
                "isRunning" -> running
                "getFrameLength" -> frameLength
                "getMicrosecondLength" -> frameLength * 125L
                "isControlSupported" -> false
                "addLineListener" -> { listeners += args!![0] as LineListener; null }
                "removeLineListener" -> { listeners -= args!![0] as LineListener; null }
                "stop" -> {
                    if (running) {
                        running = false
                        event(LineEvent.Type.STOP)
                    }
                    null
                }
                "close" -> {
                    isOpen = false
                    running = false
                    event(LineEvent.Type.CLOSE)
                    closed.complete(Unit)
                    null
                }
                "toString" -> "TestClip"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> error("Unexpected Clip call: ${method.name}")
            }
        } as Clip

        fun started() {
            check(isOpen)
            running = true
            event(LineEvent.Type.START)
        }

        fun finished() {
            check(isOpen)
            position = frameLength.toLong()
            running = false
            event(LineEvent.Type.STOP)
        }

        private fun event(type: LineEvent.Type) {
            val event = LineEvent(clip, type, position)
            listeners.forEach { it.update(event) }
        }
    }
}
