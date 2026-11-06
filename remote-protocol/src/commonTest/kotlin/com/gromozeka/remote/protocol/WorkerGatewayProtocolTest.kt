package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AgentDefinition
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
    fun `Worker welcome catalog retains explicit model behavior in strict CBOR`() {
        val connection = AiConnection.OpenAiApi(AiConnection.Id("synthetic"), "Synthetic")
        val model = AiModelConfiguration(AiModelConfiguration.Id("synthetic"), connection.id, "synthetic-model", "Synthetic")
        val spec = AiModelSpec("synthetic-model", AiProvider.OPENAI, AiModelCapability.entries.toSet(),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000), AiModelSpec.Limits.Embeddings(dimensions = 2)),
            behavior = AiModelBehavior(chatCompletionsTokenLimit = AiModelBehavior.ChatCompletionsTokenLimit.MAX_TOKENS,
                chatCompletionsReasoning = AiModelBehavior.ChatCompletionsReasoning.ANTHROPIC_THINKING,
                defaultReasoningMode = AiReasoningMode.ADAPTIVE,
                minimumToolReasoningEffort = AiReasoningEffort.LOW,
                maximumDisabledReasoningEffort = AiReasoningEffort.HIGH,
                supportsForcedToolChoice = false, anthropicThinkingPrefixDrop = true))
        val catalog = AiCatalog(listOf(connection), listOf(spec), listOf(model),
            AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map {
                AiRuntimeAssignment(it, AiRuntimeSelection(model.id))
            }, AgentDefinition.Id("synthetic-agent"))
        val welcome = WorkerGatewayMessage.Welcome(30, AiCatalogSnapshot(catalog, 1), emptyList())
        assertEquals(welcome, WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(welcome)))
    }

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
