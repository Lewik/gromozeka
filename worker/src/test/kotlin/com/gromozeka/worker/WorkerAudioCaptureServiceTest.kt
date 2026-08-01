package com.gromozeka.worker

import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class WorkerAudioCaptureServiceTest {
    @Test
    fun `cancel while audio device is opening closes the eventual handle`() = runBlocking {
        val factory = ControlledHandleFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = WorkerAudioCaptureService(factory, scope)

        try {
            val start = async { runCatching { service.handle(request(Start)) } }
            factory.opening.await()

            val cancel = service.handle(request(WorkerAudioCaptureRequest.Command.Cancel("capture-1")))
            factory.allowOpen.complete(Unit)
            val startResult = start.await()

            assertEquals(WorkerAudioCaptureResult.Status.CANCELLED, cancel.status)
            assertTrue(startResult.isFailure)
            assertTrue(startResult.exceptionOrNull()?.message.orEmpty().contains("cancelled while starting"))
            assertTrue(factory.handle.cancelled.await())
        } finally {
            service.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `cancel arriving before start prevents the device from opening`() = runBlocking {
        val factory = ControlledHandleFactory().apply { allowOpen.complete(Unit) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = WorkerAudioCaptureService(factory, scope)

        try {
            val cancel = service.handle(request(WorkerAudioCaptureRequest.Command.Cancel("capture-1")))
            val start = runCatching { service.handle(request(Start)) }

            assertEquals(WorkerAudioCaptureResult.Status.CANCELLED, cancel.status)
            assertTrue(start.isFailure)
            assertTrue(start.exceptionOrNull()?.message.orEmpty().contains("cancelled before start"))
            assertEquals(0, factory.openCount)
        } finally {
            service.shutdown()
            scope.cancel()
        }
    }

    private class ControlledHandleFactory : WorkerAudioCaptureHandleFactory {
        val opening = CompletableDeferred<Unit>()
        val allowOpen = CompletableDeferred<Unit>()
        val handle = RecordingHandle()
        var openCount = 0

        override suspend fun prepare(
            command: WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone,
        ) = Unit

        override suspend fun open(request: WorkerAudioCaptureRequest): WorkerAudioCaptureHandle {
            openCount += 1
            opening.complete(Unit)
            allowOpen.await()
            return handle
        }
    }

    private class RecordingHandle : WorkerAudioCaptureHandle {
        val cancelled = CompletableDeferred<Boolean>()

        override suspend fun stop(): WorkerAudioCaptureResult = WorkerAudioCaptureResult(
            status = WorkerAudioCaptureResult.Status.TRANSCRIBED,
            transcript = "test",
        )

        override suspend fun cancel() {
            cancelled.complete(true)
        }
    }

    private companion object {
        val Target = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("worker-session-1"),
        )
        val Start = WorkerAudioCaptureRequest.Command.StartAudio(
            sessionId = "capture-1",
            inputId = WorkerAudioInput.SystemDefault.id,
        )

        fun request(command: WorkerAudioCaptureRequest.Command) = WorkerAudioCaptureRequest(
            target = Target,
            command = command,
        )
    }
}
