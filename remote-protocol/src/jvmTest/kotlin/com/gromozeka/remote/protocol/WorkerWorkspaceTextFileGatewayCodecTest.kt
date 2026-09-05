package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerWorkspaceTextFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import com.gromozeka.domain.service.WorkspaceTextFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerWorkspaceTextFileGatewayCodecTest {
    @Test
    fun `codec preserves workspace reference and permits the same Worker after process restart`() = runBlocking {
        val identity = workerIdentity("session-1")
        val reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), "docs/memory.md")
        val request = WorkerWorkspaceTextFileReadRequest(
            target = identity,
            reference = reference,
            workspaceRootPath = "/workspace",
            maxBytes = 1024,
        )
        val handler = object : WorkerWorkspaceTextFileHandler {
            override suspend fun read(decoded: WorkerWorkspaceTextFileReadRequest): WorkspaceTextFile {
                assertEquals(request, decoded)
                return WorkspaceTextFile(
                    reference = reference,
                    resolvedPath = "/workspace/docs/memory.md",
                    fileName = "memory.md",
                    content = "# Memory",
                    sizeBytes = 8,
                )
            }
        }

        val response = WorkerWorkspaceTextFileGatewayCodec.execute(
            payload = WorkerWorkspaceTextFileGatewayCodec.encodeRequest(request),
            identity = workerIdentity("replacement-process"),
            handler = handler,
        )

        assertEquals("# Memory", WorkerWorkspaceTextFileGatewayCodec.decodeResult(response).content)
    }

    @Test
    fun `codec rejects a request for another Worker`() = runBlocking {
        val request = WorkerWorkspaceTextFileReadRequest(
            target = workerIdentity("session-1"),
            reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), "memory.md"),
            workspaceRootPath = "/workspace",
            maxBytes = 1024,
        )

        assertFailsWith<IllegalArgumentException> {
            WorkerWorkspaceTextFileGatewayCodec.execute(
                payload = WorkerWorkspaceTextFileGatewayCodec.encodeRequest(request),
                identity = workerIdentity("session-2").copy(workerId = ConversationRuntimeWorkerId("another-worker")),
                handler = object : WorkerWorkspaceTextFileHandler {
                    override suspend fun read(
                        request: WorkerWorkspaceTextFileReadRequest,
                    ): WorkspaceTextFile = error("Must not execute")
                },
            )
        }
        Unit
    }

    private fun workerIdentity(sessionId: String) = ConversationRuntimeWorkerIdentity(
        workerId = ConversationRuntimeWorkerId("worker-1"),
        sessionId = ConversationRuntimeWorkerSessionId(sessionId),
    )
}
