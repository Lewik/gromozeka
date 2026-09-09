package com.gromozeka.server

import com.gromozeka.application.service.AgentToolExecutionGuard
import com.gromozeka.application.service.DistributedAiToolCatalog
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class AgentToolExecutionGuardTest {
    @Test fun `queued tool calls use current policy and contract availability`() = runBlocking {
        val now = Clock.System.now()
        val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val conversationId = Conversation.Id("conversation")
        Mockito.`when`(conversations.getProject(conversationId)).thenReturn(project)
        val agents = Mockito.mock(AgentDomainService::class.java)
        val agent = AgentDefinition(AgentDefinition.Id("agent"), project.id, "Agent", emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
            type = AgentDefinition.Type.Project, createdAt = now, updatedAt = now)
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent)
        val workers = Mockito.mock(ConversationRuntimeWorkerRegistry::class.java)
        Mockito.`when`(workers.list()).thenReturn(emptyList())
        val workspaces = Mockito.mock(WorkspaceDomainService::class.java)
        Mockito.`when`(workspaces.findByProject(project.id)).thenReturn(emptyList())
        val workerAccess = Mockito.mock(WorkerAccessService::class.java)
        Mockito.`when`(workerAccess.listAvailableToProject(project.id)).thenReturn(emptyList())
        val provider = Mockito.mock(AiToolProvider::class.java)
        val web = object : AiToolCallback {
            override val definition = AiToolDefinition("public_search", "Search the public web", "{\"type\":\"object\"}")
            override val metadata = ServerToolMetadata
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("This test must not execute a tool")
        }
        Mockito.`when`(provider.getTools()).thenReturn(listOf(web))
        val contracts = Mockito.mock(AiToolContractRepository::class.java)
        Mockito.`when`(contracts.resolveAll(Mockito.anyCollection())).thenAnswer { invocation ->
            invocation.getArgument<Collection<AiToolDescriptor>>(0).map {
                AiToolContract(it.contractFingerprint(), it.definition.name, it.definition.name, 1, it, now)
            }
        }
        val catalog = DistributedAiToolCatalog(workers, workspaces, provider, workerAccess, contracts)
        val registered = catalog.snapshot(project).entries.values.single()
        val policy = ToolAccessPolicy.AllowOnly(setOf(ToolSelector.ExactRevision(ToolContractFingerprint(registered.contractFingerprint))))
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent.copy(toolAccess = policy))
        val call = Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("call"),
            Conversation.Message.ContentItem.ToolCall.Data(registered.modelName, JsonObject(emptyMap())))
        val payload = ConversationRuntimeTask.Payload.ToolExecution(Conversation.Message.Id("root"), agent.id, 1,
            listOf(call), false, mapOf("call" to ConversationRuntimeTaskTarget.Server))
        val task = Mockito.mock(ConversationRuntimeTask::class.java)
        Mockito.`when`(task.payload).thenReturn(payload)
        Mockito.`when`(task.conversationId).thenReturn(conversationId)
        val guard = AgentToolExecutionGuard(agents, conversations, catalog)
        guard.validate(task)
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent.copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { guard.validate(task) }
        Mockito.`when`(agents.findById(agent.id)).thenReturn(agent.copy(toolAccess = policy))
        guard.validate(task)
        Mockito.`when`(provider.getTools()).thenReturn(emptyList())
        assertFailsWith<IllegalArgumentException> { guard.validate(task) }
    }
}
