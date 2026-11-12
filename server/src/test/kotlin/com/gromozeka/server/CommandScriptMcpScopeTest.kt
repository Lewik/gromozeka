package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class CommandScriptMcpScopeTest {
    @Test fun `empty or missing SDK context cannot erase trusted user Agent project or conversation`() = runBlocking<Unit> {
        val observed = mutableListOf<ToolExecutionContext>()
        val scoped = mcpTestTool("scoped", requirements = AiToolContextRequirements(setOf(USER, CONVERSATION), setOf(AGENT, PROJECT))) { _, context ->
            observed += requireNotNull(context)
            AiToolCallResult(listOf(AiToolResult.Text("ok")))
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider(scoped, mcpTestTool("forbidden"))).initialize()
        val restricted = f.agent.copy(toolAccess = ToolAccessPolicy.AllowOnly(setOf(ToolSelector.ByName(QualifiedToolName("gromozeka", "scoped")))))
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(restricted)
        val caller = caller(f)
        val catalog = f.service.list(caller)
        assertEquals(listOf("scoped"), catalog.map { it.name })
        val properties = Json.parseToJsonElement(catalog.single().inputSchema).jsonObject.getValue("properties").jsonObject
        assertFalse("_grz" in properties, "Server-bound identity must not be requested from the script")
        assertFalse(f.service.call(caller, "scoped", buildJsonObject {}).isError)
        assertFalse(f.service.call(caller, "scoped", buildJsonObject { put("_grz", buildJsonObject {}) }).isError)
        assertTrue(f.service.call(caller, "forbidden", buildJsonObject {}).isError)
        for (field in listOf("agent_id", "conversation_id", "project_id")) {
            assertTrue(f.service.call(caller, "scoped", buildJsonObject {
                put("_grz", buildJsonObject { put(field, "another") })
            }).isError)
        }
        assertEquals(2, observed.size)
        for (context in observed) {
            assertEquals(f.user.id.value, context.getString(TOOL_CONTEXT_USER_ID))
            assertEquals(f.agent.id.value, context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
            assertEquals(f.project.id.value, context.getString(TOOL_CONTEXT_PROJECT_ID))
            assertEquals(f.conversation.id.value, context.getString(TOOL_CONTEXT_CONVERSATION_ID))
            assertEquals(f.conversation.currentThread.value, context.getString(TOOL_CONTEXT_THREAD_ID))
        }
    }

    @Test fun `cached catalog and a different Agent selector cannot bypass a changed calling Agent policy`() = runBlocking<Unit> {
        var effects = 0
        val scoped = mcpTestTool("scoped", requirements = AiToolContextRequirements(setOf(USER), setOf(AGENT))) { _, _ ->
            effects++
            AiToolCallResult()
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider(scoped)).initialize()
        val caller = caller(f)
        assertEquals(listOf("scoped"), f.service.list(caller).map { it.name })
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(f.agent.copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        val other = f.agent.copy(id = AgentDefinition.Id("unrestricted"), toolAccess = ToolAccessPolicy.DenyListed())
        Mockito.`when`(f.agents.findById(other.id)).thenReturn(other)
        assertTrue(f.service.list(caller).isEmpty())
        assertTrue(f.service.call(caller, "scoped", buildJsonObject {}).isError)
        assertTrue(f.service.call(caller, "scoped", buildJsonObject {
            put("_grz", buildJsonObject { put("agent_id", other.id.value) })
        }).isError)
        assertEquals(0, effects)
        Mockito.`when`(f.agents.findById(f.agent.id)).thenReturn(null)
        assertTrue(f.service.call(caller, "scoped", buildJsonObject {}).isError)
        assertEquals(0, effects)
    }

    @Test fun `a command launched without an Agent cannot invent one`() = runBlocking<Unit> {
        var observed: ToolExecutionContext? = null
        val optional = mcpTestTool("optional", requirements = AiToolContextRequirements(setOf(USER), setOf(AGENT))) { _, context ->
            observed = context
            AiToolCallResult()
        }
        val required = mcpTestTool("required", requirements = AiToolContextRequirements(setOf(USER, AGENT)))
        val f = UnifiedMcpTestFixture(mcpTestProvider(optional, required)).initialize()
        val principal = caller(f).principal.copy(agentDefinitionId = null)
        val caller = AuthenticatedMcpCaller.CommandScript(principal)
        assertEquals(listOf("optional"), f.service.list(caller).map { it.name })
        assertTrue(f.service.call(caller, "optional", buildJsonObject {
            put("_grz", buildJsonObject { put("agent_id", f.agent.id.value) })
        }).isError)
        assertTrue(f.service.call(caller, "required", buildJsonObject {}).isError)
        assertNull(observed)
        assertFalse(f.service.call(caller, "optional", buildJsonObject {}).isError)
        assertNull(observed?.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
        assertEquals(f.user.id.value, observed?.getString(TOOL_CONTEXT_USER_ID))
    }

    @Test fun `nested Worker dispatch retains the original calling Agent without SDK selectors`() = runBlocking<Unit> {
        var observed: ToolExecutionContext? = null
        val tool = mcpTestTool("launch", scope = AiToolExecutionScope.WORKSPACE,
            requirements = AiToolContextRequirements(setOf(USER, CONVERSATION), setOf(AGENT))) { _, context ->
            observed = context
            AiToolCallResult()
        }
        val f = UnifiedMcpTestFixture(mcpTestProvider()).initialize()
        f.advertise(tool)
        val caller = caller(f)
        f.withWorker {
            assertFalse(f.service.call(caller, "launch", buildJsonObject {
                put("_grz", buildJsonObject { put("workspace_mount_id", f.mount.id.value) })
            }).isError)
        }
        assertEquals(f.agent.id.value, observed?.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID))
        assertEquals(f.user.id.value, observed?.getString(TOOL_CONTEXT_USER_ID))
        assertEquals(f.conversation.id.value, observed?.getString(TOOL_CONTEXT_CONVERSATION_ID))
        assertEquals(f.agent.id.value, f.received.single().toolContext[TOOL_CONTEXT_AGENT_DEFINITION_ID])
        assertEquals(f.user.id, f.records.records.values.single().actorUserId)
    }

    private fun caller(f: UnifiedMcpTestFixture) = AuthenticatedMcpCaller.CommandScript(CommandScriptPrincipal(
        f.user, CommandTask.Id("script-command"), f.conversation.id, f.project.id, f.agent.id))
}
