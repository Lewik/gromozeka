package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerAudioCaptureHandler
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerAudioCaptureGatewayCodecTest {
    @Test
    fun `codec preserves exact Worker identity and audio command`() = runBlocking {
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("worker-session-1"),
        )
        val request = WorkerAudioCaptureRequest(
            target = identity,
            command = WorkerAudioCaptureRequest.Command.StartAudio(
                sessionId = "capture-1",
                inputId = WorkerAudioInput.SystemDefault.id,
            ),
        )
        val handler = object : WorkerAudioCaptureHandler {
            override suspend fun handle(decoded: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
                assertEquals(identity, decoded.target)
                val command = assertIs<WorkerAudioCaptureRequest.Command.StartAudio>(decoded.command)
                assertEquals("capture-1", command.sessionId)
                return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
            }
        }

        val response = WorkerAudioCaptureGatewayCodec.execute(
            payload = WorkerAudioCaptureGatewayCodec.encodeRequest(request),
            identity = identity,
            handler = handler,
        )

        assertEquals(
            WorkerAudioCaptureResult.Status.STARTED,
            WorkerAudioCaptureGatewayCodec.decodeResult(response).status,
        )
    }

    @Test
    fun `codec preserves Claude Code connection for direct microphone capture`() = runBlocking {
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("worker-session-1"),
        )
        val connection = AiConnection.ClaudeCode(
            id = AiConnection.Id("claude-voice"),
            displayName = "Claude voice",
            executablePath = "claude-custom",
            voiceTranscriptionEnabled = true,
            executionTarget = AiExecutionTarget.Worker(identity.workerId.value),
        )
        val request = WorkerAudioCaptureRequest(
            target = identity,
            command = WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone(
                sessionId = "capture-2",
                connection = connection,
                language = "en",
            ),
        )
        val handler = object : WorkerAudioCaptureHandler {
            override suspend fun handle(decoded: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
                val command = assertIs<WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone>(
                    decoded.command
                )
                assertEquals("capture-2", command.sessionId)
                assertEquals(connection, command.connection)
                assertEquals("en", command.language)
                return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
            }
        }

        val response = WorkerAudioCaptureGatewayCodec.execute(
            payload = WorkerAudioCaptureGatewayCodec.encodeRequest(request),
            identity = identity,
            handler = handler,
        )

        assertEquals(
            WorkerAudioCaptureResult.Status.STARTED,
            WorkerAudioCaptureGatewayCodec.decodeResult(response).status,
        )
    }
}
