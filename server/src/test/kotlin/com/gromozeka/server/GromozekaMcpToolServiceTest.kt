package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class GromozekaMcpToolServiceTest {
    @Test fun `unbound schemas expose only necessary context and reuse runtime contract identity`() = runBlocking<Unit> {
        val rename = mcpTestTool("rename", requirements = AiToolContextRequirements(setOf(USER, CONVERSATION)))
        val f = UnifiedMcpTestFixture(mcpTestProvider(rename, mcpTestTool("ordinary"))).initialize()
        f.advertise(mcpTestTool("read", AiToolExecutionScope.WORKSPACE), mcpTestTool("screen", AiToolExecutionScope.WORKER))
        val listed = f.service.list(f.user.id).associateBy { it.name }
        fun fields(name: String) = Json.parseToJsonElement(listed.getValue(name).inputSchema).jsonObject.getValue("properties").jsonObject
        assertFalse("_grz" in fields("ordinary"))
        val renameEnvelope = fields("rename").getValue("_grz").jsonObject
        assertEquals(setOf("conversation_id"), renameEnvelope.getValue("properties").jsonObject.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("conversation_id"))), renameEnvelope.getValue("required").jsonArray)
        val fileEnvelope = fields("read").getValue("_grz").jsonObject
        assertTrue("workspace_mount_id" in fileEnvelope.getValue("properties").jsonObject)
        assertFalse("worker_id" in fileEnvelope.getValue("properties").jsonObject)
        assertTrue("project_id" in fields("screen").getValue("_grz").jsonObject.getValue("properties").jsonObject)
        val runtimeContract = f.catalog.serverContracts().single { it.logicalName == "rename" }
        val externalContract = f.catalog.authenticatedTools(f.user, ::isMcpExposed).single { it.contract.logicalName == "rename" }
        assertEquals(runtimeContract, externalContract.contract)
        assertFalse("_grz" in Json.parseToJsonElement(runtimeContract.descriptor.definition.inputSchema).jsonObject.getValue("properties").jsonObject)
        assertEquals(4, f.contractRepository.findAll().size)
    }

    @Test fun `Server call derives trusted actor thread and call id without a model turn or guessed agent`() = runBlocking<Unit> {
        var context: ToolExecutionContext? = null
        var payload: String? = null
        val tool = mcpTestTool("rename", requirements = AiToolContextRequirements(setOf(USER, CONVERSATION, TOOL_CALL_ID))) { input, ctx ->
            payload = input; context = ctx; AiToolCallResult(listOf(AiToolResult.Text("done")))
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider(tool)).initialize()
        val literal = "  hello\r\nשלום 🐱  "
        val input = buildJsonObject { put("text", literal); put("_grz", buildJsonObject { put("conversation_id", f.conversation.id.value) }) }
        assertFalse(f.service.call(f.user.id, "rename", input).isError)
        assertEquals(literal, Json.parseToJsonElement(payload!!).jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse("_grz" in payload!!)
        assertEquals(f.user.id.value, context?.getString(TOOL_CONTEXT_USER_ID))
        assertEquals(f.conversation.currentThread.value, context?.getString(TOOL_CONTEXT_THREAD_ID))
        assertEquals(f.project.id.value, context?.getString(TOOL_CONTEXT_PROJECT_ID))
        assertNotNull(context?.getString("toolCallId"))
        assertNull(context?.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
        assertNull(context?.getString(TOOL_CONTEXT_WORKSPACE_MOUNT_ID))
        assertTrue(f.records.records.isEmpty())
        Mockito.verifyNoInteractions(f.coordinator, f.namedSecrets)
    }

    @Test fun `workspace dispatch preserves literal input exact mount secret attribution and typed failure through wire`() = runBlocking<Unit> {
        var context: ToolExecutionContext? = null
        var payload: String? = null
        val structured = buildJsonObject { put("code", "synthetic_rejected") }
        val bytes = byteArrayOf(0, 1, 2, -1)
        val tool = mcpTestTool("read", AiToolExecutionScope.WORKSPACE) { input, ctx ->
            payload = input; context = ctx
            AiToolCallResult(listOf(AiToolResult.Text("details"), AiToolResult.Binary(bytes, "résultat.bin", "application/octet-stream")), structured, true)
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize(); f.advertise(tool)
        Mockito.`when`(f.namedSecrets.resolve(f.user.id, setOf("synthetic"))).thenReturn(mapOf("synthetic" to "synthetic-value"))
        val result = f.withWorker { f.call("read", """{"text":"secret://synthetic","_grz":{"workspace_mount_id":"${f.mount.id.value}"}}""") }
        assertTrue(result.isError)
        assertEquals(structured, result.structuredContent)
        assertEquals(AiToolResult.Binary(bytes, "résultat.bin", "application/octet-stream"), result.content.last())
        assertEquals("synthetic-value", Json.parseToJsonElement(payload!!).jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(f.mount.rootPath, context?.getString(TOOL_CONTEXT_WORKSPACE_ROOT_PATH))
        assertEquals(f.mount.id.value, context?.getString(TOOL_CONTEXT_WORKSPACE_MOUNT_ID))
        assertEquals(f.user.id.value, context?.getString(TOOL_CONTEXT_USER_ID))
        assertNull(context?.getString(TOOL_CONTEXT_CONVERSATION_ID))
        val request = f.received.single()
        assertEquals(f.identity.workerId, request.executionTarget.workerId)
        val expected = Json.parseToJsonElement(request.toolContext.getValue(com.gromozeka.application.service.EXPECTED_TOOL_FINGERPRINTS_CONTEXT_KEY)).jsonObject
        assertEquals(JsonPrimitive(AiToolDescriptor(tool.definition, tool.metadata).contractFingerprint()), expected[request.toolCalls.single().id.value])
        assertFalse("_grz" in request.toolCalls.single().call.input.jsonObject)
        val record = f.records.records.values.single()
        assertEquals(f.user.id, record.actorUserId); assertEquals(f.project.id, record.projectId)
        Mockito.verify(f.projects).requirePermission(f.user.id, f.project.id, ProjectPermission.WRITE)
        Mockito.verify(f.workerAccess, Mockito.atLeastOnce()).requirePermission(f.user, f.identity.workerId, WorkerPermission.USE, f.project.id)
        Mockito.verifyNoInteractions(f.coordinator)
    }

    @Test fun `external MCP Worker callback can use explicit project grant but cannot guess one`() = runBlocking<Unit> {
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize()
        f.advertise(mcpTestTool("mcp__synthetic__echo", AiToolExecutionScope.WORKER, source = "mcp:synthetic"))
        Mockito.`when`(f.workerAccess.requirePermission(f.user, f.identity.workerId, WorkerPermission.USE, null)).thenThrow(WorkerAccessDeniedException())
        assertTrue(f.call("mcp__synthetic__echo", """{"_grz":{"worker_id":"${f.identity.workerId.value}"}}""").isError)
        assertTrue(f.records.records.isEmpty())
        val result = f.withWorker { f.call("mcp__synthetic__echo", """{"_grz":{"worker_id":"${f.identity.workerId.value}","project_id":"${f.project.id.value}"}}""") }
        assertFalse(result.isError)
        assertEquals(f.project.id.value, f.received.single().toolContext[TOOL_CONTEXT_PROJECT_ID])
        Mockito.verify(f.workerAccess).requireProjectAccess(f.identity.workerId, f.project.id)
    }

    @Test fun `unknown identity scope and target selectors fail before secret reads or dispatch`() = runBlocking<Unit> {
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize(); f.advertise(mcpTestTool("read", AiToolExecutionScope.WORKSPACE))
        for (input in listOf(
            """{"text":"secret://synthetic"}""",
            """{"_grz":{"worker_id":"synthetic-worker"}}""",
            """{"_grz":{"workspace_mount_id":"synthetic-mount","user_id":"forged"}}""",
            """{"_grz":{"workspace_mount_id":"synthetic-mount","thread_id":"forged"}}""",
            """{"_grz":{"workspace_mount_id":"synthetic-mount","toolCallId":"forged"}}""",
            """{"_grz":{"workspace_mount_id":"synthetic-mount"},"_context":{"userId":"forged"}}""",
            """{"execution_target":{"workspace_mount_id":"synthetic-mount"}}""",
            """{"_grz":{"workspace_mount_id":null}}""",
            """{"_grz":{"workspace_mount_id":"unknown"}}""",
        )) assertTrue(f.call("read", input).isError, input)
        assertTrue(f.records.records.isEmpty()); Mockito.verifyNoInteractions(f.namedSecrets)
    }

    @Test fun `revoked resource access and missing participants never reach callbacks`() = runBlocking<Unit> {
        var executions = 0
        val rename = mcpTestTool("rename", requirements = AiToolContextRequirements(setOf(CONVERSATION))) { _, _ -> executions++; AiToolCallResult() }
        val f = UnifiedMcpTestFixture(mcpTestProvider(rename)).initialize()
        f.advertise(mcpTestTool("read", AiToolExecutionScope.WORKSPACE))
        val listedServer = f.factory.create(testControlMcpCaller())
        assertTrue("rename" in listedServer.tools)
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation.copy(participants = setOf(Conversation.Participant.User(User.Id("other")))))
        assertTrue(f.call("rename", """{"_grz":{"conversation_id":"${f.conversation.id.value}"}}""").isError)
        Mockito.`when`(f.projects.requirePermission(f.user.id, f.project.id, ProjectPermission.WRITE)).thenThrow(ProjectAccessDeniedException())
        assertTrue(f.call("read", """{"_grz":{"workspace_mount_id":"${f.mount.id.value}"}}""").isError)
        f.visibleWorkers = emptyList()
        assertTrue(f.call("read", """{"_grz":{"workspace_mount_id":"${f.mount.id.value}"}}""").isError)
        Mockito.`when`(f.users.findActiveById(f.user.id)).thenReturn(null)
        assertTrue(f.call("rename", "{}").isError)
        assertEquals(0, executions); assertTrue(f.records.records.isEmpty())
    }

    @Test fun `contract changes produce a shared versioned name and stale names never fall back`() = runBlocking<Unit> {
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize()
        val first = mcpTestTool("screen", AiToolExecutionScope.WORKER)
        f.advertise(first)
        assertEquals("screen", f.service.list(f.user.id).single().name)
        val changed = object : AiToolCallback by first { override val definition = first.definition.copy(description = "New incompatible descriptor") }
        f.advertise(changed)
        val name = f.service.list(f.user.id).single().name
        assertEquals("screen__v2", name)
        assertTrue(f.call("screen", """{"_grz":{"worker_id":"${f.identity.workerId.value}"}}""").isError)
        assertTrue(f.records.records.isEmpty())
        assertFalse(f.withWorker { f.call(name, """{"_grz":{"worker_id":"${f.identity.workerId.value}"}}""") }.isError)
        assertEquals("screen", f.received.single().toolCalls.single().call.name)
    }

    @Test fun `selected agent exact revision policy is checked after membership and before execution`() = runBlocking<Unit> {
        var count = 0
        val tool = mcpTestTool("agent_operation", requirements = AiToolContextRequirements(setOf(USER, CONVERSATION, AGENT))) { _, _ -> count++; AiToolCallResult() }
        val f = UnifiedMcpTestFixture(mcpTestProvider(tool)).initialize()
        val contract = f.catalog.serverContracts().single()
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(f.agent.copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        val input = """{"_grz":{"conversation_id":"${f.conversation.id.value}","agent_id":"${f.agent.id.value}"}}"""
        assertTrue(f.call("agent_operation", input).isError); assertEquals(0, count)
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(f.agent.copy(toolAccess = ToolAccessPolicy.AllowOnly(setOf(ToolSelector.ExactRevision(ToolContractFingerprint(contract.fingerprint))))))
        assertFalse(f.call("agent_operation", input).isError); assertEquals(1, count)
        assertEquals(contract, f.contractRepository.findAll().single())
        Mockito.verifyNoInteractions(f.coordinator, f.namedSecrets)
    }

    @Test fun `short Worker wait returns recoverable id without cancellation retry or project invention`() = runBlocking<Unit> {
        var tools = emptyList<AiToolCallback>()
        val f = UnifiedMcpTestFixture(object : AiToolProvider { override fun getTools() = tools }).initialize()
        tools = WorkerRequestToolContributor(f.requests, f.requestAuthorization).callbacks
        f.advertise(mcpTestTool("screen", AiToolExecutionScope.WORKER))
        val pending = f.call("screen", """{"_grz":{"worker_id":"${f.identity.workerId.value}","wait_timeout_seconds":1}}""")
        assertFalse(pending.isError)
        assertEquals("PENDING", pending.structuredContent!!.getValue("status").jsonPrimitive.content)
        val id = pending.structuredContent!!.getValue("request_id").jsonPrimitive.content
        assertEquals(setOf(id), f.records.records.keys)
        assertNull(f.records.find(id)?.projectId); assertNull(f.records.find(id)?.cancelRequestedAt)
        assertTrue(f.call("grz_worker_request_get", """{"request_id":"$id"}""").renderText().contains("QUEUED"))
        assertFalse(f.call("grz_worker_request_cancel", """{"request_id":"$id"}""").isError)
        assertNotNull(f.records.find(id)?.cancelRequestedAt)
        assertTrue(f.received.isEmpty()); assertEquals(1, f.records.records.size)
    }

    @Test fun `cancelling MCP wait cancels only its submitted Worker request and propagates cancellation`() = runBlocking<Unit> {
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize(); f.advertise(mcpTestTool("screen", AiToolExecutionScope.WORKER))
        val operation = async { f.call("screen", """{"_grz":{"worker_id":"${f.identity.workerId.value}"}}""") }
        withTimeout(5000) { while (f.records.records.isEmpty()) delay(10) }
        operation.cancelAndJoin()
        assertFailsWith<CancellationException> { operation.await() }
        assertNotNull(f.records.records.values.single().cancelRequestedAt)
        assertTrue(f.received.isEmpty())
    }

    @Test fun `Server callback revision is pinned through asynchronous preparation`() = runBlocking<Unit> {
        var calls = 0
        val original = mcpTestTool("changeable") { _, _ -> calls++; AiToolCallResult() }
        var callbacks = listOf(original)
        val f = UnifiedMcpTestFixture(object : AiToolProvider { override fun getTools() = callbacks }).initialize()
        Mockito.`when`(f.namedSecrets.resolve(f.user.id, setOf("synthetic"))).thenAnswer {
            callbacks = listOf(object : AiToolCallback by original {
                override val definition = original.definition.copy(description = "New revision")
            })
            mapOf("synthetic" to "synthetic-value")
        }
        assertTrue(f.call("changeable", """{"text":"secret://synthetic"}""").isError)
        assertEquals(0, calls)
        assertTrue(f.records.records.isEmpty())
    }

    @Test fun `Worker memory advertisement is excluded too and cannot be invoked by guessed name`() = runBlocking<Unit> {
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize()
        f.advertise(mcpTestTool("memory_remember", AiToolExecutionScope.WORKER))
        assertTrue(f.service.list(f.user.id).isEmpty())
        assertTrue(f.call("memory_remember", """{"_grz":{"worker_id":"${f.identity.workerId.value}"}}""").isError)
        assertTrue(f.records.records.isEmpty()); assertTrue(f.contractRepository.findAll().isEmpty())
    }
}
