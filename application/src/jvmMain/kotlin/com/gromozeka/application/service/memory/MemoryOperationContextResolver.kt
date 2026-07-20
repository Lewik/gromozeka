package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentPromptAssemblyService
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.supportedBy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

internal data class MemoryOperationContext(
    val conversation: Conversation?,
    val agent: AgentDefinition,
    val runtimeContext: RuntimeEnvironmentContext,
    val systemPrompts: List<String>,
    val memoryTools: List<AiToolCallback>,
    val threadMessages: List<Conversation.Message>,
)

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class MemoryOperationContextResolver(
    private val conversationService: ConversationDomainService,
    private val agentDomainService: AgentDomainService,
    private val agentPromptAssemblyService: AgentPromptAssemblyService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val workspaceService: WorkspaceDomainService,
    private val runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
    private val aiToolProvider: AiToolProvider,
    private val messageRepository: MessageRepository,
    private val threadMessageRepository: ThreadMessageRepository,
) {
    internal suspend fun resolveConversation(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id? = null,
    ): MemoryOperationContext {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found: ${conversationId.value}")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException(
                "Agent not found for conversation ${conversationId.value}: ${conversation.agentDefinitionId.value}"
            )
        val runtimeContext = RuntimeEnvironmentContext.ProjectBound(
            project = conversationService.getProject(conversationId),
            workerId = runtimeWorkerDescriptor.id.value,
        )
        return MemoryOperationContext(
            conversation = conversation,
            agent = agent,
            runtimeContext = runtimeContext,
            systemPrompts = agentPromptAssemblyService.assembleSystemPrompt(agent, runtimeContext),
            memoryTools = memoryTools(),
            threadMessages = threadId
                ?.let { threadMessageRepository.getMessagesByThread(it) }
                ?: conversationService.loadCurrentMessages(conversationId),
        )
    }

    internal suspend fun resolveStandalone(): MemoryOperationContext {
        val agent = defaultAgentProvider.getDefault()
        val runtimeContext = RuntimeEnvironmentContext.Standalone(runtimeWorkerDescriptor.id.value)
        return MemoryOperationContext(
            conversation = null,
            agent = agent,
            runtimeContext = runtimeContext,
            systemPrompts = agentPromptAssemblyService.assembleSystemPrompt(agent, runtimeContext),
            memoryTools = memoryTools(),
            threadMessages = emptyList(),
        )
    }

    internal suspend fun resolveWorkspace(
        runtimeContext: RuntimeEnvironmentContext.WorkspaceBound,
    ): MemoryOperationContext {
        val agent = defaultAgentProvider.getDefault()
        return MemoryOperationContext(
            conversation = null,
            agent = agent,
            runtimeContext = runtimeContext,
            systemPrompts = agentPromptAssemblyService.assembleSystemPrompt(agent, runtimeContext),
            memoryTools = memoryTools(),
            threadMessages = emptyList(),
        )
    }

    internal suspend fun resolveWorkspaceId(workspaceId: Workspace.Id): MemoryOperationContext =
        resolveWorkspace(workspaceService.resolveRuntime(workspaceId, runtimeWorkerDescriptor.id.value))

    internal suspend fun loadTargetMessage(
        conversationId: Conversation.Id,
        targetMessageId: Conversation.Message.Id,
    ): Conversation.Message {
        val message = messageRepository.findById(targetMessageId)
            ?: throw IllegalArgumentException("Target message no longer exists: ${targetMessageId.value}")
        require(message.conversationId == conversationId) {
            "Target message ${targetMessageId.value} belongs to another conversation."
        }
        require(!message.isSyntheticMemoryMessage()) {
            "Synthetic memory messages cannot be used as memory operation targets."
        }
        return message
    }

    private fun memoryTools(): List<AiToolCallback> =
        aiToolProvider.getTools()
            .supportedBy(runtimeWorkerDescriptor.capabilities)
            .withoutMemoryManagementTools()

    private fun Conversation.Message.isSyntheticMemoryMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"
}
