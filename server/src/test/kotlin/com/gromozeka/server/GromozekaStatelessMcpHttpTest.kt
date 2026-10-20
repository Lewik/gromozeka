package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallbackContributor
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GromozekaStatelessMcpHttpTest {
    @Test
    fun `initialize returns a serialized JSON-RPC response`() = testApplication {
        application {
            statelessMcpStreamableHttp(
                path = "/mcp",
                allowedHosts = null,
                allowedOrigins = null,
            ) { testServer() }
        }

        val response = client.post("https://localhost/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(
                HttpHeaders.Accept,
                "${ContentType.Application.Json}, ${ContentType.Text.EventStream}",
            )
            setBody(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test-client", "version": "1"}
                  }
                }
                """.trimIndent()
            )
        }

        val responseBody = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, responseBody)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertTrue(responseBody.contains("\"serverInfo\""))
    }
    @Test
    fun `native control callbacks retain metadata results and owner checks over authenticated HTTP`() = testApplication {
        val authentication = Mockito.mock(AuthenticationService::class.java)
        val tokens = Mockito.mock(PersonalAccessTokenService::class.java)
        val owner = testControlMcpCaller().user
        val member = testControlMcpCaller(User.Role.MEMBER).user
        Mockito.`when`(authentication.authenticate("owner-session"))
            .thenReturn(AuthenticatedUser(owner, UserSession.Id("owner-session")))
        Mockito.`when`(authentication.authenticate("member-session"))
            .thenReturn(AuthenticatedUser(member, UserSession.Id("member-session")))
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
        application {
            installHttpAuthenticationErrors()
            installMcpAuthentication(authentication, tokens)
            statelessMcpStreamableHttp("/mcp", null, null) { call ->
                factory.create(call)
            }
        }
        suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = "owner-session") =
            client.post("https://localhost/mcp") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
                header("MCP-Protocol-Version", "2025-06-18")
                session?.let { cookie(SESSION_COOKIE_NAME, it) }
                setBody(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
                }.toString())
            }
        val initialize = rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "test"); put("version", "1") })
        })
        assertEquals(HttpStatusCode.OK, initialize.status, initialize.bodyAsText())
        val list = rpc("tools/list")
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val definition = serverToolJson.parseToJsonElement(list.bodyAsText()).jsonObject
            .getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(callback.definition.name), definition["name"])
        assertTrue("outputSchema" in definition)
        assertEquals(JsonPrimitive(false), definition.getValue("annotations").jsonObject["readOnlyHint"])
        val arguments = buildJsonObject { put("name", callback.definition.name); put("arguments", JsonObject(emptyMap())) }
        val success = rpc("tools/call", arguments)
        assertEquals(HttpStatusCode.OK, success.status, success.bodyAsText())
        val result = serverToolJson.parseToJsonElement(success.bodyAsText()).jsonObject.getValue("result").jsonObject
        assertFalse(result.getValue("isError").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(owner.id.value), result.getValue("structuredContent").jsonObject.getValue("result").jsonObject["actor"])
        val denied = rpc("tools/call", arguments, "member-session")
        assertEquals(HttpStatusCode.OK, denied.status, denied.bodyAsText())
        val deniedResult = serverToolJson.parseToJsonElement(denied.bodyAsText()).jsonObject.getValue("result").jsonObject
        assertTrue(deniedResult.getValue("isError").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("forbidden"), deniedResult.getValue("structuredContent").jsonObject.getValue("error").jsonObject["code"])
        assertEquals(HttpStatusCode.Unauthorized, rpc("tools/call", arguments, null).status)
        assertFalse("_grz" in definition.getValue("inputSchema").jsonObject.getValue("properties").jsonObject)
        for (injected in listOf(
            buildJsonObject { put("_grz", buildJsonObject { put("user_id", owner.id.value) }) },
            buildJsonObject { put("_grz", buildJsonObject { put("conversation_id", "unneeded") }) },
            buildJsonObject { put("execution_target", JsonObject(emptyMap())) },
        )) {
            val response = rpc("tools/call", buildJsonObject {
                put("name", callback.definition.name); put("arguments", injected)
            })
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(serverToolJson.parseToJsonElement(response.bodyAsText()).jsonObject
                .getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        }
        assertEquals(1, executions)
    }

    @Test
    fun `one authenticated endpoint lists and executes Server and Worker tools with native typed results`() = testApplication {
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
        val authentication = Mockito.mock(AuthenticationService::class.java)
        val tokens = Mockito.mock(PersonalAccessTokenService::class.java)
        Mockito.`when`(authentication.authenticate("synthetic-session")).thenReturn(AuthenticatedUser(f.user, UserSession.Id("synthetic-session")))
        application {
            installHttpAuthenticationErrors()
            installMcpAuthentication(authentication, tokens)
            statelessMcpStreamableHttp("/mcp", null, null) { call -> f.factory.create(call) }
        }
        suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
            val response = client.post("https://localhost/mcp") {
                header(HttpHeaders.Host, "localhost"); header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
                cookie(SESSION_COOKIE_NAME, "synthetic-session"); header("MCP-Protocol-Version", "2025-06-18")
                setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return serverToolJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
        }
        rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18"); put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "synthetic-client"); put("version", "1") })
        })
        val published = rpc("tools/list").getValue("tools").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
        assertEquals(setOf("synthetic_control", "synthetic_native", "synthetic_read", "mcp__synthetic__echo"), published.keys)
        for (definition in f.service.list(f.user.id)) {
            assertEquals(serverToolJson.parseToJsonElement(definition.inputSchema), published.getValue(definition.name)["inputSchema"])
            definition.outputSchema?.let { assertEquals(serverToolJson.parseToJsonElement(it), published.getValue(definition.name)["outputSchema"]) }
        }
        assertEquals(JsonPrimitive(false), published.getValue("synthetic_read").getValue("inputSchema").jsonObject["additionalProperties"])
        val local = rpc("tools/call", buildJsonObject { put("name", "synthetic_control"); put("arguments", JsonObject(emptyMap())) })
        assertEquals(JsonPrimitive(f.user.id.value), local.getValue("structuredContent").jsonObject.getValue("result").jsonObject["actor"])
        val result = f.withWorker { rpc("tools/call", buildJsonObject {
            put("name", "synthetic_read"); put("arguments", buildJsonObject {
                put("_grz", buildJsonObject { put("workspace_mount_id", f.mount.id.value) })
            })
        }) }
        assertEquals(structured, result["structuredContent"])
        assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        val content = result.getValue("content").jsonArray
        assertEquals(JsonPrimitive("from-worker"), content.first().jsonObject["text"])
        val binary = content.last().jsonObject.getValue("resource").jsonObject
        assertEquals(JsonPrimitive("AQID"), binary["blob"])
        assertEquals(JsonPrimitive("application/octet-stream"), binary["mimeType"])
        assertEquals(1, f.received.size); assertEquals(1, f.records.records.size)
        val obsolete = client.post("https://localhost/mcp/control") { cookie(SESSION_COOKIE_NAME, "synthetic-session") }
        assertEquals(HttpStatusCode.NotFound, obsolete.status)
    }

    @Test
    fun `server HTTP handler cancellation reaches the native operation`() = testApplication {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handler = kotlinx.coroutines.CompletableDeferred<kotlinx.coroutines.Job>()
        val callback = mcpTestTool("wait") { _, _ ->
            try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() } finally { finished.complete(Unit) }
        }
        val factory = testUnifiedMcpFactory(mcpTestProvider(callback))
        val authentication = Mockito.mock(AuthenticationService::class.java)
        Mockito.`when`(authentication.authenticate("session")).thenReturn(
            AuthenticatedUser(testControlMcpCaller().user, UserSession.Id("session")))
        application {
            installHttpAuthenticationErrors()
            installMcpAuthentication(authentication, Mockito.mock(PersonalAccessTokenService::class.java))
            statelessMcpStreamableHttp("/mcp", null, null) { call ->
                handler.complete(checkNotNull(call.coroutineContext[kotlinx.coroutines.Job]))
                factory.create(call)
            }
        }
        kotlinx.coroutines.withTimeout(5000) {
            kotlinx.coroutines.coroutineScope {
                val request = async {
                    client.post("https://localhost/mcp") {
                        header(HttpHeaders.Host, "localhost"); header(HttpHeaders.ContentType, ContentType.Application.Json)
                        header(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
                        header("MCP-Protocol-Version", "2025-06-18")
                        cookie(SESSION_COOKIE_NAME, "session")
                        setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"wait","arguments":{}}}""")
                    }
                }
                // A client disconnect is not an assertion that the Server cancelled its job.
                // Cancel the actual handler parent and require propagation through SDK -> executor -> callback.
                try {
                    started.await()
                    handler.await().cancel(kotlinx.coroutines.CancellationException("synthetic handler cancellation"))
                    finished.await()
                    assertTrue(finished.isCompleted)
                } finally { request.cancelAndJoin() }
            }
        }
    }

}

private fun testServer() = Server(
    serverInfo = Implementation("test-server", "1"),
    options = ServerOptions(ServerCapabilities()),
)
