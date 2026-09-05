package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerRequestPolicy
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class WorkerRequestToolContributorTest {
    private val repository = TestWorkerRequestRepository()
    private val authorization = WorkerRequestAuthorization {}
    private val requests = WorkerRequestService(repository, authorization)
    private val tools = WorkerRequestToolContributor(requests, authorization).callbacks.associateBy { it.definition.name }
    private val worker = ConversationRuntimeWorkerId("worker")
    private val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to "author", TOOL_CONTEXT_PROJECT_ID to "project"))

    private suspend fun submit() = requests.submit(
        worker, WorkerGatewayOperation.TOOL_EXECUTION, byteArrayOf(), WorkerRequestPolicy(), User.Id("author"), Project.Id("project"),
    )

    @Test
    fun `only request author in original project can query or cancel`() = runBlocking {
        val id = submit()
        val input = """{"request_id":"$id"}"""
        for (tool in tools.values) {
            for (unauthorized in listOf(
                null,
                context.withValue(TOOL_CONTEXT_USER_ID, "other-user"),
                context.withValue(TOOL_CONTEXT_PROJECT_ID, "other-project"),
            )) {
                assertTrue(tool.call(input, unauthorized).contains("error"))
                assertNull(repository.find(id)?.cancelRequestedAt)
            }
        }
        assertTrue(tools.getValue("grz_worker_request_get").call(input, context).contains("QUEUED"))
        assertTrue(tools.getValue("grz_worker_request_cancel").call(input, context).contains("CANCELLED"))
        assertEquals("CANCELLED", requests.await(id, 100).errorCode)
    }

    @Test
    fun `saved binary tool results are returned as binary rather than base64 text`() = runBlocking {
        val id = submit()
        repository.markDispatched(id, Clock.System.now())
        val result = WorkerToolExecutionResponse(listOf(Conversation.Message.ContentItem.ToolResult(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("capture"),
            toolName = "capture",
            result = listOf(
                Conversation.Message.ContentItem.ToolResult.Data.Text("done"),
                Conversation.Message.ContentItem.ToolResult.Data.Base64Data("AQID", Conversation.Message.MediaType.IMAGE_PNG, "screen.png"),
            ),
        )), false)
        requests.accept(worker, WorkerGatewayMessage.Response(id, WorkerGatewayMessage.Response.Status.SUCCEEDED, Json.encodeToString(result).encodeToByteArray()))
        val output = tools.getValue("grz_worker_request_get").callResult("""{"request_id":"$id"}""", context)
        assertEquals(AiToolResult.Binary(byteArrayOf(1, 2, 3), "screen.png", "image/png"), output.last())
        assertTrue(output.filterIsInstance<AiToolResult.Text>().none { it.content.contains("AQID") })
        assertTrue(output.filterIsInstance<AiToolResult.Text>().any { it.content.contains("SUCCEEDED") })
        assertEquals(output, tools.getValue("grz_worker_request_get").callResult("""{"request_id":"$id"}""", context))
    }
}
