package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerRequestDelivery
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
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
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `sound uses durable tool execution and duplicate deliveries never play it again`() = runTest {
        var starts = 0
        var stops = 0
        val started = CompletableDeferred<Unit>()
        val controller = WorkerSoundController(output = { _, onStarted ->
            starts++
            onStarted()
            started.complete(Unit)
            try { awaitCancellation() } finally { stops++ }
        })
        val sound = WorkerSoundTool(controller)
        val call = execution.copy(toolCalls = listOf(ToolCall(ToolCall.Id("sound-call"),
            ToolCall.Data(sound.descriptor.definition.name, JsonObject(emptyMap())))))
        val message = request(call).copy(delivery = WorkerRequestDelivery(Clock.System.now() + 30.seconds, 15_000))
        val journal = TestWorkerRequestJournal()
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val executor = WorkerRequestExecutor(journal, backgroundScope, WorkerToolRequestHandler(worker, listOf(sound)), responses::send)
        executor.initialize()
        executor.accept(message)
        started.await()
        executor.accept(message)
        controller.stop()
        val first = responses.receive()
        val result = Json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(first.payload).decodeToString()).results.single()
        assertFalse(result.isError)
        assertTrue(Json.encodeToString(result).contains("STOPPED_LOCALLY"))
        assertEquals(1, stops)
        val recovered = WorkerRequestExecutor(journal, backgroundScope, WorkerToolRequestHandler(worker, listOf(sound)), responses::send)
        recovered.initialize()
        recovered.accept(message)
        assertEquals(first, responses.receive())
        assertEquals(1, starts)
    }
}
