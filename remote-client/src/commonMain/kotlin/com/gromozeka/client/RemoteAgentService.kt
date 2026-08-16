package com.gromozeka.client

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.remote.protocol.AgentResponse
import com.gromozeka.remote.protocol.AgentsResponse
import com.gromozeka.remote.protocol.CountAgentsRequest
import com.gromozeka.remote.protocol.CountResponse
import com.gromozeka.remote.protocol.DuplicateAgentRequest
import com.gromozeka.remote.protocol.CreateAgentRequest
import com.gromozeka.remote.protocol.DefaultAgentResponse
import com.gromozeka.remote.protocol.DeleteAgentRequest
import com.gromozeka.remote.protocol.FindAgentRequest
import com.gromozeka.remote.protocol.FindAgentsRequest
import com.gromozeka.remote.protocol.GetDefaultAgentRequest
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.UpdateAgentRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemoteAgentService(
    private val client: GromozekaWsClient,
) : AgentDomainService, DefaultAgentProvider {
    override suspend fun getDefault(): AgentDefinition =
        client.requestTyped<GetDefaultAgentRequest, DefaultAgentResponse>(GetDefaultAgentRequest).agent

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? =
        client.requestTyped<FindAgentRequest, AgentResponse>(FindAgentRequest(id)).agent

    override suspend fun findAll(): List<AgentDefinition> =
        client.requestTyped<FindAgentsRequest, AgentsResponse>(FindAgentsRequest()).agents

    override fun observeAll(): Flow<List<AgentDefinition>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.AGENTS, load = ::findAll)

    override suspend fun findByProject(projectId: com.gromozeka.domain.model.Project.Id): List<AgentDefinition> =
        client.requestTyped<FindAgentsRequest, AgentsResponse>(FindAgentsRequest(projectId)).agents

    override fun observeByProject(
        projectId: com.gromozeka.domain.model.Project.Id,
    ): Flow<List<AgentDefinition>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.AGENTS) { findByProject(projectId) }

    override suspend fun createAgent(
        projectId: com.gromozeka.domain.model.Project.Id?,
        name: String,
        prompts: List<Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides,
        tools: List<String>,
        description: String?,
        skills: List<AgentSkill.Id>,
    ): AgentDefinition =
        client.requestTyped<CreateAgentRequest, AgentResponse>(
            CreateAgentRequest(
                projectId,
                name,
                prompts,
                runtimeSelection,
                runtimeOverrides,
                tools,
                description,
                skills,
            )
        ).agent ?: error("Server returned null agent after create")

    override suspend fun duplicateAgent(
        projectId: com.gromozeka.domain.model.Project.Id?,
        sourceAgentId: AgentDefinition.Id,
        name: String,
    ): AgentDefinition =
        client.requestTyped<DuplicateAgentRequest, AgentResponse>(
            DuplicateAgentRequest(projectId, sourceAgentId, name)
        ).agent ?: error("Server returned null agent after duplicate")

    override suspend fun update(
        id: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String?,
        skills: List<AgentSkill.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides,
        tools: List<String>,
    ): AgentDefinition? =
        client.requestTyped<UpdateAgentRequest, AgentResponse>(
            UpdateAgentRequest(
                id,
                name,
                prompts,
                description,
                skills,
                runtimeSelection,
                runtimeOverrides,
                tools,
            )
        ).agent

    override suspend fun delete(id: AgentDefinition.Id) {
        client.requestTyped<DeleteAgentRequest, SavedResponse>(DeleteAgentRequest(id))
    }

    override suspend fun count(): Int =
        client.requestTyped<CountAgentsRequest, CountResponse>(CountAgentsRequest).count
}
