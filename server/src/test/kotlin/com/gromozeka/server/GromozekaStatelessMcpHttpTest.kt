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
            statelessMcpStreamableHttp(
                path = "/mcp/control",
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
        val factory = GromozekaControlMcpServerFactory(provider)
        application {
            installHttpAuthenticationErrors()
            installMcpAuthentication(authentication, tokens)
            statelessMcpStreamableHttp("/mcp/control", null, null) { call ->
                factory.create(call.attributes[authenticatedMcpCallerKey])
            }
        }
        suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = "owner-session") =
            client.post("https://localhost/mcp/control") {
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
        assertEquals(1, executions)
    }

}

private fun testServer() = Server(
    serverInfo = Implementation("test-server", "1"),
    options = ServerOptions(ServerCapabilities()),
)
