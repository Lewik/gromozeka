package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.WorkerAudioInput
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
}
