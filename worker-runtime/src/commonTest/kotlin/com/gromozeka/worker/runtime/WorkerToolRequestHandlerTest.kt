package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class WorkerToolRequestHandlerTest {
    private val worker = ConversationRuntimeWorkerId("android")
    private var inspections = 0
    private val tool = WorkerDeviceStatusTool {
        inspections++
        WorkerDeviceStatus(Clock.System.now(), DeviceStateEvent.DeviceInfo(WorkerPlatform.ANDROID, "Test", "Android", "1"),
            DeviceStateEvent.Battery(50, false), false, null, 100, 0)
    }
    private val execution = WorkerToolExecutionRequest(
        ConversationRuntimeTaskTarget.Worker(worker),
        listOf(ToolCall(ToolCall.Id("call"), ToolCall.Data(tool.descriptor.definition.name, JsonObject(emptyMap())))), emptyMap(),
    )
    private fun request(execution: WorkerToolExecutionRequest = this.execution) = WorkerGatewayMessage.Request(
        "request", WorkerGatewayOperation.TOOL_EXECUTION, Json.encodeToString(execution).encodeToByteArray(),
    )

    @Test
    fun `device status executes through the shared tool protocol`() = runTest {
        val response = WorkerToolRequestHandler(worker, listOf(tool)).execute(request())
        val result = Json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(response.payload).decodeToString()).results.single()
        assertEquals(ToolCall.Id("call"), result.toolUseId)
        assertEquals(tool.descriptor.definition.name, result.toolName)
        assertFalse(result.isError)
        assertEquals(1, inspections)
    }

    @Test
    fun `wrong target unsupported operations and disabled enrollment never execute`() = runTest {
        val handler = WorkerToolRequestHandler(worker, listOf(tool))
        assertFailsWith<IllegalArgumentException> {
            handler.execute(request(execution.copy(executionTarget = ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("other")))))
        }
        assertFailsWith<IllegalArgumentException> { handler.execute(request().copy(operation = WorkerGatewayOperation.WORKER_CONTROL)) }
        assertFailsWith<IllegalStateException> { WorkerToolRequestHandler(worker, listOf(tool)) { error("Disabled") }.execute(request()) }
        assertEquals(0, inspections)
    }

    @Test
    fun `unknown tools and arguments return explicit tool errors and cancellation propagates`() = runTest {
        for (call in listOf(
            execution.toolCalls.single().copy(call = ToolCall.Data("missing", JsonObject(emptyMap()))),
            execution.toolCalls.single().copy(call = ToolCall.Data(tool.descriptor.definition.name, JsonObject(mapOf("unexpected" to JsonPrimitive(true))))),
        )) {
            val response = WorkerToolRequestHandler(worker, listOf(tool)).execute(request(execution.copy(toolCalls = listOf(call))))
            assertTrue(Json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(response.payload).decodeToString()).results.single().isError)
        }
        assertFailsWith<CancellationException> {
            WorkerToolRequestHandler(worker, listOf(WorkerDeviceStatusTool { throw CancellationException("Stopped") })).execute(request())
        }
        assertEquals(0, inspections)
    }
}
