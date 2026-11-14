package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerWorkspaceFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import com.gromozeka.domain.service.WorkspaceFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerWorkspaceFileGatewayCodecTest {
    @Test
    fun `codec preserves workspace reference and permits the same Worker after process restart`() = runBlocking {
        val identity = workerIdentity("session-1")
        val reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), "docs/memory.md")
        val request = WorkerWorkspaceFileReadRequest(
            target = identity,
            reference = reference,
            workspaceRootPath = "/workspace",
            maxBytes = 1024,
        )
        val handler = object : WorkerWorkspaceFileHandler {
            override suspend fun read(decoded: WorkerWorkspaceFileReadRequest): WorkspaceFile {
                assertEquals(request, decoded)
                return WorkspaceFile(
                    reference = reference,
                    resolvedPath = "/workspace/docs/memory.md",
                    fileName = "memory.md",
                    content = com.gromozeka.domain.model.BinaryContent.fromText("# Memory"),
                    sizeBytes = 8,
                )
            }
        }

        val response = WorkerWorkspaceFileGatewayCodec.execute(
            payload = WorkerWorkspaceFileGatewayCodec.encodeRequest(request),
            identity = workerIdentity("replacement-process"),
            handler = handler,
        )

        assertEquals("# Memory", WorkerWorkspaceFileGatewayCodec.decodeResult(response).content.utf8TextOrNull())
    }

    @Test
    fun `codec rejects a request for another Worker`() = runBlocking {
        val request = WorkerWorkspaceFileReadRequest(
            target = workerIdentity("session-1"),
            reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), "memory.md"),
            workspaceRootPath = "/workspace",
            maxBytes = 1024,
        )

        assertFailsWith<IllegalArgumentException> {
            WorkerWorkspaceFileGatewayCodec.execute(
                payload = WorkerWorkspaceFileGatewayCodec.encodeRequest(request),
                identity = workerIdentity("session-2").copy(workerId = ConversationRuntimeWorkerId("another-worker")),
                handler = object : WorkerWorkspaceFileHandler {
                    override suspend fun read(
                        request: WorkerWorkspaceFileReadRequest,
                    ): WorkspaceFile = error("Must not execute")
                },
            )
        }
        Unit
    }

    @Test fun `codec preserves nontext bytes without converting them to strings`() = runBlocking {
        val request = WorkerWorkspaceFileReadRequest(workerIdentity("session"),
            WorkspacePathReference(WorkspaceMount.Id("mount"), "image.png"), "/workspace", 5)
        val bytes = byteArrayOf(0, -1, -2, 32, 10)
        val payload = WorkerWorkspaceFileGatewayCodec.execute(WorkerWorkspaceFileGatewayCodec.encodeRequest(request), request.target,
            object : WorkerWorkspaceFileHandler {
                override suspend fun read(request: WorkerWorkspaceFileReadRequest) = WorkspaceFile(request.reference,
                    "/workspace/image.png", "image.png", com.gromozeka.domain.model.BinaryContent.fromBytes(bytes), bytes.size.toLong())
            })
        kotlin.test.assertContentEquals(bytes, WorkerWorkspaceFileGatewayCodec.decodeResult(payload).content.bytes())
    }

    private fun workerIdentity(sessionId: String) = ConversationRuntimeWorkerIdentity(
        workerId = ConversationRuntimeWorkerId("worker-1"),
        sessionId = ConversationRuntimeWorkerSessionId(sessionId),
    )
}
