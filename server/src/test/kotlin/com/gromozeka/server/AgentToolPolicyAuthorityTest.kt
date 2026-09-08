package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class AgentToolPolicyAuthorityTest {
    @Test fun `model control tools cannot create update or duplicate a broader agent`() = runBlocking {
        val agents = Mockito.mock(AgentDomainService::class.java)
        val contracts = Mockito.mock(AiToolContractRepository::class.java)
        Mockito.`when`(contracts.findAll()).thenReturn(emptyList())
        val now = Clock.System.now()
        val caller = AgentDefinition(AgentDefinition.Id("restricted"), null, "Restricted", emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
            toolAccess = ToolAccessPolicy.AllowOnly(), type = AgentDefinition.Type.Global,
            createdAt = now, updatedAt = now)
        val unrestricted = caller.copy(id = AgentDefinition.Id("unrestricted"), toolAccess = ToolAccessPolicy.DenyListed())
        Mockito.`when`(agents.findById(caller.id)).thenReturn(caller)
        Mockito.`when`(agents.findById(unrestricted.id)).thenReturn(unrestricted)
        val authority = AgentToolPolicyAuthority(agents, contracts)
        val context = testControlMcpContext().copy(callingAgentId = caller.id)
        authority.requireWithinCaller(context, caller.toolAccess)
        authority.requireWithinCaller(testControlMcpContext(), unrestricted.toolAccess)
        assertFailsWith<ControlMcpToolException> { authority.requireWithinCaller(context, unrestricted.toolAccess) }
        val provider = ControlMcpAgentCatalogTools(agents, Mockito.mock(), Mockito.mock(), Mockito.mock(), Mockito.mock(), authority)
        for (name in listOf("grz_agent_create", "grz_agent_update", "grz_agent_duplicate")) {
            val input = buildJsonObject {
                put("agentId", unrestricted.id.value)
                put("sourceAgentId", unrestricted.id.value)
                put("name", "Requested copy")
                put("toolAccess", buildJsonObject { put("type", "deny_listed") })
            }
            val response = provider.tools.single { it.definition.name == name }.invokeStructured(context, input)
            assertEquals("forbidden", response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content, name)
        }
    }
}
