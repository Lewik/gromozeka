package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerGatewayProtocolTest {
    @Test
    fun `gateway messages round trip through binary codec`() {
        val expected = WorkerGatewayMessage.Failure(
            code = "WORKER_SESSION_CONFLICT",
            message = "Worker already has an active session",
        )

        assertEquals(expected, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(expected)))
    }

    @Test
    fun `worker command runtime messages round trip through binary codec`() {
        val request = WorkerCommandRuntimeRequest.FindCommandTasks
        val response = WorkerCommandRuntimeResponse.CommandTasksResult(emptyList())

        assertEquals(
            request,
            WorkerCommandRuntimeGatewayCodec.decodeRequest(
                WorkerCommandRuntimeGatewayCodec.encodeRequest(request)
            ),
        )
        assertEquals(
            response,
            WorkerCommandRuntimeGatewayCodec.decodeResponse(
                WorkerCommandRuntimeGatewayCodec.encodeResponse(response)
            ),
        )
    }

    @Test
    fun `worker workspace messages round trip through binary codec`() {
        val request = WorkerWorkspaceRequest.FindProjectMounts(Project.Id("project-1"))
        val response = WorkerWorkspaceResponse.MountsResult(emptyList())

        assertEquals(
            request,
            WorkerWorkspaceGatewayCodec.decodeRequest(
                WorkerWorkspaceGatewayCodec.encodeRequest(request)
            ),
        )
        assertEquals(
            response,
            WorkerWorkspaceGatewayCodec.decodeResponse(
                WorkerWorkspaceGatewayCodec.encodeResponse(response)
            ),
        )
    }
}
