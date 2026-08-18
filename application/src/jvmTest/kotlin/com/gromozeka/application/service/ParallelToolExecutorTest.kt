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
import com.gromozeka.domain.tool.TOOL_CONTEXT_SECRET_ENVIRONMENT
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `resolved secret reaches callback without mutating tool call`() = runBlocking {
        val tool = CapturingTestTool()
        val executor = executor(tool)
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-capture"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "capture",
                input = JsonObject(mapOf("token" to JsonPrimitive("secret://github-pat"))),
            ),
        )

        executor.executeParallel(
            toolCalls = listOf(toolCall),
            toolContext = ToolExecutionContext(),
            runtimeTaskId = null,
            executor = server,
            expectedTarget = ConversationRuntimeTaskTarget.Server,
            resolvedSecretsByToolCallId = mapOf(
                toolCall.id.value to mapOf("github-pat" to "actual-token")
            ),
        )

        assertTrue(tool.receivedInput.orEmpty().contains("actual-token"))
        assertTrue(toolCall.call.input.toString().contains("secret://github-pat"))
    }

    @Test
    fun `command secret is delivered through generated environment`() = runBlocking {
        val tool = CommandCapturingTestTool()
        val executor = executor(tool)
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-command"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = GRZ_EXECUTE_COMMAND_TOOL_NAME,
                input = JsonObject(mapOf("command" to JsonPrimitive("echo secret://github-pat"))),
            ),
        )

        executor.executeParallel(
            toolCalls = listOf(toolCall),
            toolContext = ToolExecutionContext(),
            runtimeTaskId = null,
            executor = server,
            expectedTarget = ConversationRuntimeTaskTarget.Server,
            resolvedSecretsByToolCallId = mapOf(
                toolCall.id.value to mapOf("github-pat" to "actual-token")
            ),
        )

        assertFalse(tool.receivedInput.orEmpty().contains("secret://github-pat"))
        assertFalse(tool.receivedInput.orEmpty().contains("actual-token"))
        assertEquals(listOf("actual-token"), tool.receivedEnvironment.values.toList())
        assertTrue(toolCall.call.input.toString().contains("secret://github-pat"))
    }

    @Test
    fun `blocking tools execute concurrently and preserve result order`() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = executor(
            BlockingTestTool("first", started, release),
            BlockingTestTool("second", started, release),
        )

        val execution = async {
            executor.executeParallel(
                toolCalls = listOf(toolCall("first"), toolCall("second")),
                toolContext = ToolExecutionContext(),
                runtimeTaskId = null,
                executor = server,
                expectedTarget = ConversationRuntimeTaskTarget.Server,
            )
        }
        val startedConcurrently = withContext(Dispatchers.IO) {
            started.await(2, TimeUnit.SECONDS)
        }
        release.countDown()
        val result = execution.await()

        assertTrue(startedConcurrently)
        assertEquals(
            listOf("first", "second"),
            result.results.map { toolResult ->
                assertIs<Conversation.Message.ContentItem.ToolResult.Data.Text>(
                    toolResult.result.single()
                ).content
            },
        )
    }

    private fun executor(vararg tools: AiToolCallback): ParallelToolExecutor =
        ParallelToolExecutor(
            aiToolProvider = object : AiToolProvider {
                override fun getTools(): List<AiToolCallback> = tools.toList()
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

private class CapturingTestTool : AiToolCallback {
    var receivedInput: String? = null
    override val definition = AiToolDefinition(
        name = "capture",
        description = "capture",
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)

    override fun call(toolInput: String, context: ToolExecutionContext?): String {
        receivedInput = toolInput
        return "ok"
    }
}

private class CommandCapturingTestTool : AiToolCallback {
    var receivedInput: String? = null
    var receivedEnvironment: Map<String, String> = emptyMap()
    override val definition = AiToolDefinition(
        name = GRZ_EXECUTE_COMMAND_TOOL_NAME,
        description = GRZ_EXECUTE_COMMAND_TOOL_NAME,
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)

    override fun call(toolInput: String, context: ToolExecutionContext?): String {
        receivedInput = toolInput
        @Suppress("UNCHECKED_CAST")
        receivedEnvironment = context?.get(TOOL_CONTEXT_SECRET_ENVIRONMENT) as? Map<String, String>
            ?: emptyMap()
        return "ok"
    }
}

private class BlockingTestTool(
    name: String,
    private val started: CountDownLatch,
    private val release: CountDownLatch,
) : AiToolCallback {
    override val definition = AiToolDefinition(
        name = name,
        description = name,
        inputSchema = """{"type":"object"}""",
    )
    override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)

    override fun call(toolInput: String, context: ToolExecutionContext?): String {
        started.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release tool ${definition.name}" }
        return definition.name
    }
}
