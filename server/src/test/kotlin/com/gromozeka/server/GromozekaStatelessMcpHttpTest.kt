package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallbackContributor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import kotlin.test.*

class GromozekaStatelessMcpHttpTest {
    @Test
    fun `initialize returns a serialized JSON-RPC response`() = runBlocking<Unit> {
        val factory = testUnifiedMcpFactory(mcpTestProvider())
        val authentication = sessionAuthentication("session", testControlMcpCaller().user)
        SpringHttpTestFixture(GromozekaStatelessMcpHttp(factory), authentication = authentication).use { http ->
            val response = http.perform(rpcRequest("initialize", initializeParams(), "session"))
            assertEquals(200, response.status, response.contentAsString)
            assertTrue(response.contentType.orEmpty().startsWith("application/json"))
            assertTrue(response.contentAsString.contains("\"serverInfo\""))
        }
    }

    @Test
    fun `native control callbacks retain metadata results and owner checks over authenticated HTTP`() = runBlocking<Unit> {
        val owner = testControlMcpCaller().user
        val member = testControlMcpCaller(User.Role.MEMBER).user
        val authentication = sessionAuthentication("owner-session", owner)
        Mockito.`when`(authentication.authenticate("member-session")).thenReturn(AuthenticatedUser(member, UserSession.Id("member-session")))
        var executions = 0
        val callback = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "http_native_tool", "Native tool.",
            readOnly = false, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) {
            executions++
            buildJsonObject { put("actor", user.id.value) }
        }
        val provider = testNativeToolProvider(listOf(object : AiToolCallbackContributor {
            override val callbacks = listOf(callback)
        }))
        val factory = testUnifiedMcpFactory(provider)
        SpringHttpTestFixture(GromozekaStatelessMcpHttp(factory), authentication = authentication).use { http ->
            suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = "owner-session") =
                http.perform(rpcRequest(method, params, session))
            assertEquals(200, rpc("initialize", initializeParams()).status)
            val list = rpc("tools/list")
            assertEquals(200, list.status, list.contentAsString)
            val definition = result(list).getValue("tools").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(callback.definition.name), definition["name"])
            assertTrue("outputSchema" in definition)
            assertEquals(JsonPrimitive(false), definition.getValue("annotations").jsonObject["readOnlyHint"])
            val arguments = buildJsonObject { put("name", callback.definition.name); put("arguments", JsonObject(emptyMap())) }
            val success = rpc("tools/call", arguments)
            assertEquals(200, success.status, success.contentAsString)
            assertFalse(result(success).getValue("isError").jsonPrimitive.boolean)
            assertEquals(JsonPrimitive(owner.id.value), result(success).getValue("structuredContent").jsonObject.getValue("result").jsonObject["actor"])
            val denied = rpc("tools/call", arguments, "member-session")
            assertEquals(200, denied.status, denied.contentAsString)
            assertTrue(result(denied).getValue("isError").jsonPrimitive.boolean)
            assertEquals(JsonPrimitive("forbidden"), result(denied).getValue("structuredContent").jsonObject.getValue("error").jsonObject["code"])
            assertEquals(401, rpc("tools/call", arguments, null).status)
            assertFalse("_grz" in definition.getValue("inputSchema").jsonObject.getValue("properties").jsonObject)
            for (injected in listOf(
                buildJsonObject { put("_grz", buildJsonObject { put("user_id", owner.id.value) }) },
                buildJsonObject { put("_grz", buildJsonObject { put("conversation_id", "unneeded") }) },
                buildJsonObject { put("execution_target", JsonObject(emptyMap())) },
            )) {
                val response = rpc("tools/call", buildJsonObject { put("name", callback.definition.name); put("arguments", injected) })
                assertEquals(200, response.status)
                assertTrue(result(response).getValue("isError").jsonPrimitive.boolean)
            }
            assertEquals(1, executions)
        }
    }

    @Test
    fun `one authenticated endpoint lists and executes Server and Worker tools with native typed results`() = runBlocking<Unit> {
        val control = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "synthetic_control", "Control", readOnly = true) {
            buildJsonObject { put("actor", user.id.value) }
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider(control, mcpTestTool("synthetic_native"), mcpTestTool("memory_remember"))).initialize()
        val structured = buildJsonObject { put("value", 7) }
        f.advertise(mcpTestTool("synthetic_read", com.gromozeka.domain.tool.AiToolExecutionScope.WORKSPACE) { _, _ ->
            com.gromozeka.domain.tool.AiToolCallResult(listOf(
                com.gromozeka.domain.tool.AiToolResult.Text("from-worker"),
                com.gromozeka.domain.tool.AiToolResult.Binary(byteArrayOf(1, 2, 3), "test.bin", "application/octet-stream")), structured, true)
        }, mcpTestTool("mcp__synthetic__echo", com.gromozeka.domain.tool.AiToolExecutionScope.WORKER, source = "mcp:synthetic"))
        SpringHttpTestFixture(GromozekaStatelessMcpHttp(f.factory),
            authentication = sessionAuthentication("synthetic-session", f.user)).use { http ->
            suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
                val response = http.perform(rpcRequest(method, params, "synthetic-session"))
                assertEquals(200, response.status, response.contentAsString)
                return result(response)
            }
            rpc("initialize", initializeParams())
            val published = rpc("tools/list").getValue("tools").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
            assertEquals(setOf("synthetic_control", "synthetic_native", "synthetic_read", "mcp__synthetic__echo"), published.keys)
            for (definition in f.service.list(f.user.id)) {
                assertEquals(serverToolJson.parseToJsonElement(definition.inputSchema), published.getValue(definition.name)["inputSchema"])
                definition.outputSchema?.let { assertEquals(serverToolJson.parseToJsonElement(it), published.getValue(definition.name)["outputSchema"]) }
            }
            assertEquals(JsonPrimitive(false), published.getValue("synthetic_read").getValue("inputSchema").jsonObject["additionalProperties"])
            val local = rpc("tools/call", buildJsonObject { put("name", "synthetic_control"); put("arguments", JsonObject(emptyMap())) })
            assertEquals(JsonPrimitive(f.user.id.value), local.getValue("structuredContent").jsonObject.getValue("result").jsonObject["actor"])
            val remote = f.withWorker { rpc("tools/call", buildJsonObject {
                put("name", "synthetic_read"); put("arguments", buildJsonObject {
                    put("_grz", buildJsonObject { put("workspace_mount_id", f.mount.id.value) })
                })
            }) }
            assertEquals(structured, remote["structuredContent"])
            assertTrue(remote.getValue("isError").jsonPrimitive.boolean)
            val content = remote.getValue("content").jsonArray
            assertEquals(JsonPrimitive("from-worker"), content.first().jsonObject["text"])
            val binary = content.last().jsonObject.getValue("resource").jsonObject
            assertEquals(JsonPrimitive("AQID"), binary["blob"])
            assertEquals(JsonPrimitive("application/octet-stream"), binary["mimeType"])
            assertEquals(1, f.received.size); assertEquals(1, f.records.records.size)
            assertEquals(404, http.perform(post("/mcp/control").secureTestRequest("synthetic-session")).status)
        }
    }

    @Test
    fun `server HTTP handler cancellation reaches the native operation`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val callback = mcpTestTool("wait") { _, _ ->
            try { started.complete(Unit); awaitCancellation() } finally { finished.complete(Unit) }
        }
        val controller = GromozekaStatelessMcpHttp(testUnifiedMcpFactory(mcpTestProvider(callback)))
        val request = mockMcpRequest("tools/call", buildJsonObject { put("name", "wait"); put("arguments", JsonObject(emptyMap())) })
        request.setAttribute(AUTHENTICATED_MCP_CALLER, testControlMcpCaller())
        withTimeout(5_000) {
            coroutineScope {
                val operation = async { controller.post(request, MockHttpServletResponse()) }
                try {
                    started.await()
                    operation.cancelAndJoin()
                    finished.await()
                    assertTrue(finished.isCompleted)
                    assertFailsWith<CancellationException> { operation.await() }
                } finally { operation.cancelAndJoin() }
            }
        }
    }
}

private suspend fun sessionAuthentication(token: String, user: User): AuthenticationService =
    Mockito.mock(AuthenticationService::class.java).also {
        Mockito.`when`(it.authenticate(token)).thenReturn(AuthenticatedUser(user, UserSession.Id(token)))
    }

private fun initializeParams() = buildJsonObject {
    put("protocolVersion", "2025-06-18"); put("capabilities", JsonObject(emptyMap()))
    put("clientInfo", buildJsonObject { put("name", "synthetic-client"); put("version", "1") })
}

private fun rpcRequest(method: String, params: JsonObject, session: String?) = post("/mcp")
    .secureTestRequest(session).contentType("application/json")
    .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18")
    .content(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())

private fun result(response: MockHttpServletResponse): JsonObject {
    val envelope = serverToolJson.parseToJsonElement(response.contentAsString).jsonObject
    assertNull(envelope["error"], response.contentAsString)
    return envelope.getValue("result").jsonObject
}
