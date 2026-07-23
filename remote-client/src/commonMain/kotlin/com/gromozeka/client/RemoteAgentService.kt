package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.remote.protocol.AgentResponse
import com.gromozeka.remote.protocol.AgentsResponse
import com.gromozeka.remote.protocol.CountAgentsRequest
import com.gromozeka.remote.protocol.CountResponse
import com.gromozeka.remote.protocol.CopyBuiltinAgentRequest
import com.gromozeka.remote.protocol.CreateAgentRequest
import com.gromozeka.remote.protocol.DefaultAgentResponse
import com.gromozeka.remote.protocol.DeleteAgentRequest
import com.gromozeka.remote.protocol.FindAgentRequest
import com.gromozeka.remote.protocol.FindAgentsRequest
import com.gromozeka.remote.protocol.GetDefaultAgentRequest
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.UpdateAgentRequest

internal class RemoteAgentService(
    private val client: GromozekaWsClient,
) : AgentDomainService, DefaultAgentProvider {
    override suspend fun getDefault(): AgentDefinition =
        client.requestTyped<GetDefaultAgentRequest, DefaultAgentResponse>(GetDefaultAgentRequest).agent

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? =
        client.requestTyped<FindAgentRequest, AgentResponse>(FindAgentRequest(id)).agent

    override suspend fun findAll(): List<AgentDefinition> =
        client.requestTyped<FindAgentsRequest, AgentsResponse>(FindAgentsRequest()).agents

    override suspend fun findByProject(projectId: com.gromozeka.domain.model.Project.Id): List<AgentDefinition> =
        client.requestTyped<FindAgentsRequest, AgentsResponse>(FindAgentsRequest(projectId)).agents

    override suspend fun createAgent(
        projectId: com.gromozeka.domain.model.Project.Id,
        name: String,
        prompts: List<Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        tools: List<String>,
        description: String?,
        skills: List<AgentSkill.Id>,
    ): AgentDefinition =
        client.requestTyped<CreateAgentRequest, AgentResponse>(
            CreateAgentRequest(projectId, name, prompts, runtimeSelection, tools, description, skills)
        ).agent ?: error("Server returned null agent after create")

    override suspend fun copyBuiltinAgent(
        projectId: com.gromozeka.domain.model.Project.Id,
        sourceAgentId: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String?,
        skills: List<AgentSkill.Id>,
    ): AgentDefinition =
        client.requestTyped<CopyBuiltinAgentRequest, AgentResponse>(
            CopyBuiltinAgentRequest(projectId, sourceAgentId, name, prompts, description, skills)
        ).agent ?: error("Server returned null agent after builtin copy")

    override suspend fun update(
        id: AgentDefinition.Id,
        prompts: List<Prompt.Id>?,
        description: String?,
        skills: List<AgentSkill.Id>?,
    ): AgentDefinition? =
        client.requestTyped<UpdateAgentRequest, AgentResponse>(
            UpdateAgentRequest(id, prompts, description, skills)
        ).agent

    override suspend fun delete(id: AgentDefinition.Id) {
        client.requestTyped<DeleteAgentRequest, SavedResponse>(DeleteAgentRequest(id))
    }

    override suspend fun count(): Int =
        client.requestTyped<CountAgentsRequest, CountResponse>(CountAgentsRequest).count
}
