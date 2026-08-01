package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
    fun `short transcript fallback submits once after processing output becomes stable`() {
        val fallback = ClaudeVoiceShortTranscriptSubmission(
            initialOutputRevision = 5,
            graceNanos = 100,
        )

        assertFalse(fallback.shouldSubmit(outputRevision = 5, processingObserved = false, nowNanos = 0))
        assertFalse(fallback.shouldSubmit(outputRevision = 6, processingObserved = false, nowNanos = 5))
        assertFalse(fallback.shouldSubmit(outputRevision = 6, processingObserved = true, nowNanos = 10))
        assertFalse(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 50))
        assertFalse(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 149))
        assertTrue(fallback.shouldSubmit(outputRevision = 7, processingObserved = true, nowNanos = 150))
        assertFalse(fallback.shouldSubmit(outputRevision = 8, processingObserved = true, nowNanos = 1_000))
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
        val capture = RecordingCaptureSession()
        override val isAlive = true

        override suspend fun beginRecording(): ClaudeCodeVoiceCaptureSession {
            beginCount.incrementAndGet()
            return capture
        }

        override suspend fun dispose() = Unit
    }

    private class RecordingCaptureSession : ClaudeCodeVoiceCaptureSession {
        override suspend fun stop(): String = "transcript"
        override suspend fun cancel() = Unit
    }
}
