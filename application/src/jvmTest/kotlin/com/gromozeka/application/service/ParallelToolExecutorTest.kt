package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.Base64

class ParallelToolExecutorTest {
    private val server = ConversationRuntimeExecutorIdentity.Server(
        ConversationRuntimeServerSessionId("server-session")
    )
    private val workerIdentity = ConversationRuntimeWorkerIdentity(
        workerId = ConversationRuntimeWorkerId("worker"),
        sessionId = ConversationRuntimeWorkerSessionId("worker-session"),
    )
    private val worker = ConversationRuntimeExecutorIdentity.Worker(workerIdentity)

    @Test
    fun `conversation runtime tool cannot execute on Worker`() = runBlocking {
        val executor = executor(TestTool("runtime_tool", AiToolExecutionScope.SERVER))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.executeParallel(
                toolCalls = listOf(toolCall("runtime_tool")),
                toolContext = ToolExecutionContext(),
                runtimeTaskId = null,
                executor = worker,
                expectedTarget = ConversationRuntimeTaskTarget.Worker(workerIdentity.workerId),
            )
        }

        assertTrue(error.message.orEmpty().contains("cannot execute on Worker"))
    }

    @Test
    fun `command owner tool cannot execute on Server`() = runBlocking {
        val executor = executor(TestTool("command_tool", AiToolExecutionScope.COMMAND_TASK_OWNER))

        val error = assertFailsWith<IllegalArgumentException> {
            executor.executeParallel(
                toolCalls = listOf(toolCall("command_tool")),
                toolContext = ToolExecutionContext(),
                runtimeTaskId = null,
                executor = server,
                expectedTarget = ConversationRuntimeTaskTarget.Server,
            )
        }

        assertTrue(error.message.orEmpty().contains("cannot execute on Server"))
    }

    @Test
    fun `conversation runtime tool executes on Server`() = runBlocking {
        val executor = executor(TestTool("runtime_tool", AiToolExecutionScope.SERVER))

        val result = executor.executeParallel(
            toolCalls = listOf(toolCall("runtime_tool")),
            toolContext = ToolExecutionContext(),
            runtimeTaskId = null,
            executor = server,
            expectedTarget = ConversationRuntimeTaskTarget.Server,
        )

        assertEquals(
            "ok",
            assertIs<Conversation.Message.ContentItem.ToolResult.Data.Text>(
                result.results.single().result.single()
            ).content,
        )
    }

    @Test
    fun `binary tool result remains typed`() = runBlocking {
        val bytes = byteArrayOf(1, 3, 3, 7)
        val executor = executor(BinaryTestTool(bytes))

        val result = executor.executeParallel(
            toolCalls = listOf(toolCall("binary_tool")),
            toolContext = ToolExecutionContext(),
            runtimeTaskId = null,
            executor = server,
            expectedTarget = ConversationRuntimeTaskTarget.Server,
        )

        val binary = assertIs<Conversation.Message.ContentItem.ToolResult.Data.Base64Data>(
            result.results.single().result.single()
        )
        assertEquals("capture.png", binary.fileName)
        assertEquals("image/png", binary.mediaType.value)
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(binary.data)))
    }

    private fun executor(tool: AiToolCallback): ParallelToolExecutor =
        ParallelToolExecutor(
            aiToolProvider = object : AiToolProvider {
                override fun getTools(): List<AiToolCallback> = listOf(tool)
            },
            toolApprovalService = AutoApproveToolApprovalService(),
        )

    private fun toolCall(name: String): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-$name"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = name,
                input = JsonObject(emptyMap()),
            ),
        )
}

private class BinaryTestTool(
    private val bytes: ByteArray,
) : AiToolCallback {
    override val definition = AiToolDefinition(
        name = "binary_tool",
        description = "binary_tool",
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)

    override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Text call is not supported")

    override fun callResult(toolInput: String, context: ToolExecutionContext?): List<AiToolResult> =
        listOf(AiToolResult.Binary(bytes, "capture.png", "image/png"))
}

private class TestTool(
    name: String,
    executionScope: AiToolExecutionScope,
) : AiToolCallback {
    override val definition = AiToolDefinition(
        name = name,
        description = name,
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(executionScope = executionScope)

    override fun call(toolInput: String, context: ToolExecutionContext?): String = "ok"
}
