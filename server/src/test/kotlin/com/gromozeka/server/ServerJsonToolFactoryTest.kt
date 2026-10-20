package com.gromozeka.server

import com.gromozeka.application.service.AutoApproveToolApprovalService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerJsonToolFactoryTest {
    private val owner = testControlMcpCaller().user
    private val context = ServerToolContext(owner, AgentDefinition.Id("test-agent")).toExecutionContext()
    private val factory = testServerToolFactory()

    @Test
    fun `native descriptor declares output hints and access without an MCP adapter`() {
        val tool = factory.create(CONTROL_TOOL_SOURCE, "test_control", "Test operation.", readOnly = true) {
            buildJsonObject { put("userId", user.id.value) }
        }
        assertEquals(CONTROL_TOOL_SOURCE, tool.definition.source)
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertEquals(AiToolAccessPolicy.AUTHENTICATED, tool.metadata.accessPolicy)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
        assertFalse(tool.metadata.logInput)
        assertEquals(true, tool.definition.annotations?.readOnlyHint)
        assertEquals(false, tool.definition.annotations?.openWorldHint)
        assertNotNull(tool.definition.outputSchema)
        val exported = tool.definition.toMcpTool()
        assertEquals(tool.definition.name, exported.name)
        assertEquals(tool.definition.description, exported.description)
        assertEquals(
            serverToolJson.parseToJsonElement(tool.definition.inputSchema),
            serverToolJson.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.serializer(), exported.inputSchema),
        )
        assertNotNull(exported.outputSchema)
        assertEquals(true, exported.annotations?.readOnlyHint)
    }

    @Test
    fun `same registered callback serves runtime and HTTP with trusted actor and identical result`() = runBlocking<Unit> {
        val actors = mutableListOf<User.Id>()
        val tool = factory.create(CONTROL_TOOL_SOURCE, "test_control", "Test operation.", readOnly = true) {
            actors += user.id
            buildJsonObject { put("userId", user.id.value) }
        }
        val provider = provider(tool)
        assertSame(tool, provider.getTools().single())
        val arguments = buildJsonObject { put("userId", "forged-user") }
        val native = tool.callResult(arguments.toString(), context)
        val exported = testUnifiedMcpFactory(provider).create(testControlMcpCaller())
            .tools.getValue(tool.definition.name).callForTest(request(tool, arguments))
        assertFalse(native.isError)
        assertFalse(exported.isError == true)
        assertEquals(native.structuredContent, exported.structuredContent)
        assertEquals(listOf(owner.id, owner.id), actors)
        assertEquals(JsonPrimitive(owner.id.value), native.structuredContent!!.getValue("result").jsonObject["userId"])
    }

    @Test
    fun `missing and inactive actor never reach the operation`() = runBlocking<Unit> {
        var executed = false
        val users = object : UserDirectoryService {
            override suspend fun findActiveById(id: User.Id): User? = null
            override suspend fun listActive(): List<User> = emptyList()
        }
        val tool = testServerToolFactory(users).create(CONTROL_TOOL_SOURCE, "test_control", "Test.", readOnly = true) {
            executed = true
            buildJsonObject {}
        }
        for (caller in listOf(null, ToolExecutionContext(), context)) {
            val result = tool.callResult("{}", caller)
            assertTrue(result.isError)
            assertEquals(JsonPrimitive("unauthenticated"), result.structuredContent!!.getValue("error").jsonObject["code"])
        }
        val exported = testUnifiedMcpFactory(provider(tool)).create(testControlMcpCaller())
            .tools.getValue(tool.definition.name).callForTest(request(tool))
        assertTrue(exported.isError == true)
        assertFalse(executed)
    }

    @Test
    fun `owner policy is enforced before execution on both entry points`() = runBlocking<Unit> {
        var executions = 0
        val tool = factory.create(CONTROL_TOOL_SOURCE, "owner_tool", "Owner only.", readOnly = false,
            accessPolicy = AiToolAccessPolicy.SERVER_OWNER) {
            executions++
            buildJsonObject {}
        }
        val member = testControlMcpCaller(User.Role.MEMBER)
        val native = tool.callResult("{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to member.user.id.value)))
        val http = testUnifiedMcpFactory(provider(tool)).create(member)
            .tools.getValue(tool.definition.name).callForTest(request(tool))
        assertTrue(native.isError)
        assertTrue(http.isError == true)
        assertEquals(native.structuredContent, http.structuredContent)
        assertEquals(JsonPrimitive("forbidden"), native.structuredContent!!.getValue("error").jsonObject["code"])
        assertEquals(0, executions)
    }

    @Test
    fun `runtime preserves structured tool failure and reports FAILED rather than COMPLETED`() = runBlocking<Unit> {
        var executions = 0
        val tool = factory.create(CONTROL_TOOL_SOURCE, "failing_tool", "Test failure.", readOnly = true) {
            executions++
            throw IllegalStateException("Expected operational failure")
        }
        val progress = mutableListOf<ConversationRuntimeToolExecution>()
        val result = ParallelToolExecutor(provider(tool), AutoApproveToolApprovalService()).executeParallel(
            toolCalls = listOf(Conversation.Message.ContentItem.ToolCall(
                id = Conversation.Message.ContentItem.ToolCall.Id("test-call"),
                call = Conversation.Message.ContentItem.ToolCall.Data(tool.definition.name, JsonObject(emptyMap())),
            )),
            toolContext = context,
            runtimeTaskId = null,
            executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("test-server")),
            expectedTarget = ConversationRuntimeTaskTarget.Server,
            onToolExecutionChanged = { progress += it },
        ).results.single()
        assertTrue(result.isError)
        val body = (result.result.single() as Conversation.Message.ContentItem.ToolResult.Data.Text).content
        assertEquals(JsonPrimitive("invalid_state"), serverToolJson.parseToJsonElement(body).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(listOf(ConversationRuntimeToolExecution.Status.RUNNING, ConversationRuntimeToolExecution.Status.FAILED), progress.map { it.status })
        assertEquals(1, executions)
    }

    @Test
    fun `unexpected failure stays opaque and isError on both entry points`() = runBlocking<Unit> {
        var calls = 0
        val tool = factory.create(CONTROL_TOOL_SOURCE, "private_failure", "Test.", readOnly = true) {
            calls++
            throw RuntimeException("PRIVATE_CREDENTIAL_SENTINEL")
        }
        val native = tool.callResult("{}", context)
        val http = testUnifiedMcpFactory(provider(tool)).create(testControlMcpCaller())
            .tools.getValue(tool.definition.name).callForTest(request(tool))
        assertTrue(native.isError)
        assertTrue(http.isError == true)
        assertEquals(native.structuredContent, http.structuredContent)
        assertFalse(native.renderText().contains("PRIVATE_CREDENTIAL_SENTINEL"))
        assertEquals(2, calls)
    }

    @Test
    fun `parent cancellation reaches a suspended operation through native and HTTP calls`() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (http in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val tool = factory.create(CONTROL_TOOL_SOURCE, "waiting_tool", "Test.", readOnly = true) {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finished.complete(Unit)
                    }
                }
                val operation = async {
                    if (http) testUnifiedMcpFactory(provider(tool)).create(testControlMcpCaller())
                        .tools.getValue(tool.definition.name).callForTest(request(tool))
                    else tool.callResult("{}", context)
                }
                started.await()
                operation.cancelAndJoin()
                assertTrue(finished.isCompleted)
                assertFailsWith<CancellationException> { operation.await() }
            }
        }
    }

    private fun provider(tool: AiToolCallback) = testNativeToolProvider(listOf(
        object : AiToolCallbackContributor { override val callbacks = listOf(tool) }
    ))

    private fun request(tool: AiToolCallback, arguments: JsonObject = JsonObject(emptyMap())) =
        CallToolRequest(CallToolRequestParams(name = tool.definition.name, arguments = arguments))
}
