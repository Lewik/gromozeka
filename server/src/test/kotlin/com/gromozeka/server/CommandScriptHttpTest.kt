package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.CommandScriptTokens
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.PersonalAccessTokenService
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import kotlin.test.*

class CommandScriptHttpTest {
    @Test fun `command bearer carries immutable scope over real MVC and never falls back to cookie or PAT`() = runBlocking<Unit> {
        val observed = mutableListOf<ToolExecutionContext>()
        val tool = mcpTestTool("scoped", requirements = AiToolContextRequirements(setOf(USER, CONVERSATION), setOf(AGENT))) { _, context ->
            observed += requireNotNull(context)
            AiToolCallResult(listOf(AiToolResult.Text("ok")))
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider(tool)).initialize()
        val principal = CommandScriptPrincipal(f.user, CommandTask.Id("command"), f.conversation.id, f.project.id, f.agent.id)
        val token = CommandScriptTokens.create()
        var allowed = true
        val grants = Mockito.mock(CommandScriptGrantService::class.java) { call ->
            if (call.method.name.startsWith("authenticate")) {
                if (allowed && call.arguments[0] == token) principal else null
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        val sessions = Mockito.mock(AuthenticationService::class.java)
        Mockito.`when`(sessions.authenticate("valid-cookie")).thenReturn(AuthenticatedUser(f.user, UserSession.Id("session")))
        val pats = Mockito.mock(PersonalAccessTokenService::class.java)
        SpringHttpTestFixture(GromozekaStatelessMcpHttp(f.factory), authentication = sessions, tokens = pats, scriptGrants = grants).use { http ->
            suspend fun rpc(method: String, parameters: JsonObject = buildJsonObject {}) = http.perform(
                post("/mcp").secureTestRequest("valid-cookie")
                    .header("Authorization", "Bearer $token")
                    .header("MCP-Protocol-Version", "2025-11-25")
                    .header("Accept", "application/json, text/event-stream")
                    .contentType("application/json")
                    .content(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", parameters) }.toString()))
            assertEquals(200, rpc("initialize", buildJsonObject {
                put("protocolVersion", "2025-11-25"); put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "script-test"); put("version", "1") })
            }).status)
            val catalog = rpc("tools/list")
            assertEquals(200, catalog.status, catalog.contentAsString)
            val definition = Json.parseToJsonElement(catalog.contentAsString).jsonObject.getValue("result").jsonObject
                .getValue("tools").jsonArray.single().jsonObject
            assertFalse("_grz" in definition.getValue("inputSchema").jsonObject.getValue("properties").jsonObject)
            val params = buildJsonObject { put("name", "scoped"); put("arguments", buildJsonObject {}) }
            val result = rpc("tools/call", params)
            assertEquals(200, result.status, result.contentAsString)
            assertFalse(Json.parseToJsonElement(result.contentAsString).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
            assertEquals(1, observed.size)
            assertEquals(f.user.id.value, observed.single().getString(TOOL_CONTEXT_USER_ID))
            assertEquals(f.agent.id.value, observed.single().getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
            assertEquals(f.conversation.id.value, observed.single().getString(TOOL_CONTEXT_CONVERSATION_ID))
            allowed = false
            val rejected = rpc("tools/call", params)
            assertEquals(401, rejected.status)
            assertFalse(rejected.contentAsString.contains(token))
            assertEquals(1, observed.size)
            Mockito.verifyNoInteractions(pats)
            Mockito.verify(sessions, Mockito.never()).authenticate("valid-cookie")
        }
    }
}
