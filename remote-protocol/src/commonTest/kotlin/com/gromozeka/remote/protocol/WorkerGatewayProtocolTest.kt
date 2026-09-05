package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
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
    fun `request cancellation round trips through binary codec`() {
        val expected = WorkerGatewayMessage.CancelRequest("request-1")

        assertEquals(expected, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(expected)))
    }

    @Test
    fun `durable request metadata and result acknowledgement round trip`() {
        val request = WorkerGatewayMessage.Request(
            "request-1", WorkerGatewayOperation.TOOL_EXECUTION, byteArrayOf(1, 2),
            com.gromozeka.domain.service.WorkerRequestDelivery(kotlin.time.Instant.parse("2026-09-06T00:00:00Z"), 60_000, true),
        )
        assertEquals(request, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(request)))
        val acknowledged = WorkerGatewayMessage.ResponseAcknowledged(request.id)
        assertEquals(acknowledged, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(acknowledged)))
    }

    @Test
    fun `tool loading policy round trips through worker catalog`() {
        val expected = WorkerGatewayMessage.Ready(
            tools = listOf(
                AiToolDescriptor(
                    definition = AiToolDefinition(
                        name = "preloaded_worker_tool",
                        description = "Preloaded worker tool",
                        inputSchema = """{"type":"object"}""",
                    ),
                    metadata = AiToolMetadata(
                        executionScope = AiToolExecutionScope.WORKER,
                        loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
                    ),
                )
            )
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
