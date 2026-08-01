package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.pty4j.PtyProcessBuilder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ClaudeCodeVoiceTranscriptionServiceTest {
    @Test
    fun `detects recording readiness through terminal control sequences`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("\u001B[2K\r\u001B[31mREC\u001B[0m · tap to send")

        assertTrue(output.isRecordingReady())
        assertNull(output.failureMessage())
    }

    @Test
    fun `detects readiness when cursor positioning removes spaces from Claude status`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("\u001B[60C⏺\u001B[63GREC · tap\u001B[73Gto send")

        assertTrue(output.isRecordingReady())
    }

    @Test
    fun `detects interactive prompt before microphone is opened`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("Claude\u001B[19GCode v2.1.220\r\n\u001B[2C❯ Try a command")

        assertTrue(output.isInteractiveReady())
        assertFalse(output.isRecordingReady())
    }

    @Test
    fun `does not report readiness from a partial status`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("REC")

        assertFalse(output.isRecordingReady())
    }

    @Test
    fun `detects the first rendered audio callback`() {
        val output = ClaudeVoiceTerminalOutput()

        val beforeRecording = output.audioActivityCount()
        output.append("\u001B[2K\rREC · tap to send ▁")

        assertTrue(output.audioActivityCount() > beforeRecording)
    }

    @Test
    fun `recognizes known voice failures without treating processing as failure`() {
        val output = ClaudeVoiceTerminalOutput()
        output.append("Voice:\u001B[18Gprocessing…")
        assertNull(output.failureMessage())
        assertTrue(output.isTranscriptionProcessing())

        output.append("\rNo speech detected.")
        assertEquals("No speech detected", output.failureMessage())
    }

    @Test
    fun `recognizes a workspace trust prompt through cursor positioning`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("Quick\u001B[9Gsafety\u001B[16Gcheck: Is this a project you trust?")

        assertTrue(output.isWorkspaceTrustPrompt())
        assertNull(output.failureMessage())
    }

    @Test
    fun `diagnostics do not expose captured terminal text`() {
        val output = ClaudeVoiceTerminalOutput()

        output.append("Voice: processing… secret spoken transcript")

        assertTrue(output.diagnosticState().contains("processing=true"))
        assertFalse(output.diagnosticState().contains("secret spoken transcript"))
    }

    @Test
    fun `voice command keeps Claude tools disabled`() {
        val arguments = claudeVoiceArguments(Path.of("voice-settings.json"))

        assertEquals("", arguments[arguments.indexOf("--tools") + 1])
    }

    @Test
    fun `delays short transcript fallback until the normal auto-submit window closes`() {
        val fallback = ClaudeVoiceShortTranscriptSubmission(
            initialOutputRevision = 5,
            startedAtNanos = 0,
            minimumDelay = 100.nanoseconds,
            outputStability = 20.nanoseconds,
        )

        assertFalse(fallback.shouldSubmit(outputRevision = 5, processingObserved = true, nowNanos = 200))
        assertFalse(fallback.shouldSubmit(outputRevision = 6, processingObserved = false, nowNanos = 10))
        assertFalse(fallback.shouldSubmit(outputRevision = 6, processingObserved = true, nowNanos = 30))
        assertFalse(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 100))
        assertFalse(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 119))
        assertTrue(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 120))
        assertFalse(fallback.shouldSubmit(outputRevision = 8, processingObserved = true, nowNanos = 1_000))
    }

    @Test
    fun `terminates a pty process without relying on Process toHandle`() {
        val command = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            arrayOf("cmd.exe", "/D", "/S", "/C", "ping -n 60 127.0.0.1 >NUL")
        } else {
            arrayOf("/bin/sh", "-c", "sleep 60")
        }
        val process = PtyProcessBuilder(command).start()

        try {
            terminateClaudeVoiceProcessTree(process)
            assertFalse(process.isAlive)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `prewarm is consumed and replaced before the next recording`() = runBlocking {
        val factory = RecordingVoiceSessionFactory()
        val service = ClaudeCodeVoiceTranscriptionService(sessionFactory = factory)
        val connection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-voice"),
            displayName = "Claude voice",
            voiceTranscriptionEnabled = true,
            executionTarget = AiExecutionTarget.Worker("worker-1"),
        )

        try {
            service.prepareLocalMicrophone(connection, "en")
            val first = factory.sessions.single()

            val capture = service.startLocalMicrophone(connection, "en")
            withTimeout(2_000) {
                while (factory.prepareCount.get() < 2) delay(10)
            }

            assertSame(first.capture, capture)
            assertEquals(1, first.beginCount.get())
            assertEquals(2, factory.prepareCount.get())
            capture.cancel()
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `expired prewarm is refreshed and consumed before recording`() = runBlocking {
        val factory = RecordingVoiceSessionFactory()
        var nowNanos = 0L
        val service = ClaudeCodeVoiceTranscriptionService(
            sessionFactory = factory,
            warmSessionMaxAge = 10.nanoseconds,
            nanoTime = { nowNanos },
        )
        val connection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-voice"),
            displayName = "Claude voice",
            voiceTranscriptionEnabled = true,
            executionTarget = AiExecutionTarget.Worker("worker-1"),
        )

        try {
            service.prepareLocalMicrophone(connection, "en")
            val expired = factory.sessions.single()
            nowNanos = 11

            service.prepareLocalMicrophone(connection, "en")
            val refreshed = factory.sessions.last()
            val capture = service.startLocalMicrophone(connection, "en")
            withTimeout(2_000) {
                while (factory.prepareCount.get() < 3) delay(10)
            }

            assertNotSame(expired.capture, capture)
            assertSame(refreshed.capture, capture)
            assertEquals(1, expired.disposeCount.get())
            capture.cancel()
        } finally {
            service.shutdown()
        }
    }

    private class RecordingVoiceSessionFactory : ClaudeCodeVoiceSessionFactory {
        val prepareCount = AtomicInteger()
        val sessions = java.util.Collections.synchronizedList(mutableListOf<RecordingPreparedSession>())

        override suspend fun prepare(
            connection: AiConnection.ClaudeCode,
            language: String?,
            environment: Map<String, String>,
        ): ClaudeCodeVoicePreparedSession = RecordingPreparedSession().also {
            sessions += it
            prepareCount.incrementAndGet()
        }
    }

    private class RecordingPreparedSession : ClaudeCodeVoicePreparedSession {
        val beginCount = AtomicInteger()
        val disposeCount = AtomicInteger()
        val capture = RecordingCaptureSession()
        override val isAlive = true

        override suspend fun beginRecording(): ClaudeCodeVoiceCaptureSession {
            beginCount.incrementAndGet()
            return capture
        }

        override suspend fun dispose() {
            disposeCount.incrementAndGet()
        }
    }

    private class RecordingCaptureSession : ClaudeCodeVoiceCaptureSession {
        override suspend fun stop(): String = "transcript"
        override suspend fun cancel() = Unit
    }
}
