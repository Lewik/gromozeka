package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.tool.AiToolCallback
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

internal data class MemoryOperationContext(
    val conversation: Conversation?,
    val agent: AgentDefinition,
    val project: Project,
    val systemPrompts: List<String>,
    val memoryTools: List<AiToolCallback>,
    val threadMessages: List<Conversation.Message>,
)

@Service
class MemoryOperationContextResolver(
    private val conversationService: ConversationDomainService,
    private val agentDomainService: AgentDomainService,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val projectService: ProjectDomainService,
    private val settingsService: SettingsService,
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
        val project = conversationService.getProject(conversationId)
        return MemoryOperationContext(
            conversation = conversation,
            agent = agent,
            project = project,
            systemPrompts = agentDomainService.assembleSystemPrompt(agent, project),
            memoryTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
            threadMessages = threadId
                ?.let { threadMessageRepository.getMessagesByThread(it) }
                ?: conversationService.loadCurrentMessages(conversationId),
        )
    }

    internal suspend fun resolveStandalone(): MemoryOperationContext {
        val project = projectService.getOrCreate(defaultStandaloneProjectPath())
        return resolveProject(project)
    }

    internal suspend fun resolveProject(project: Project): MemoryOperationContext {
        val agent = defaultAgentProvider.getDefault()
        return MemoryOperationContext(
            conversation = null,
            agent = agent,
            project = project,
            systemPrompts = agentDomainService.assembleSystemPrompt(agent, project),
            memoryTools = aiToolProvider.getTools().withoutMemoryManagementTools(),
            threadMessages = emptyList(),
        )
    }

    internal fun resolveTargetMessage(
        threadMessages: List<Conversation.Message>,
        targetMessageId: String?,
    ): Conversation.Message {
        val explicitMessageId = targetMessageId?.takeIf { it.isNotBlank() }
        if (explicitMessageId != null) {
            return threadMessages.firstOrNull { message ->
                message.id.value == explicitMessageId && !message.isSyntheticMemoryMessage()
            } ?: throw IllegalArgumentException("Target message not found in the current thread: $explicitMessageId")
        }

        return threadMessages
            .asReversed()
            .firstOrNull { message ->
                message.hasUserAuthoredContent() && !message.isSyntheticMemoryMessage()
            }
            ?: throw IllegalArgumentException("No previous user-authored message found in the current thread.")
    }

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

    internal fun resolveNamespace(explicitNamespaceValue: String?): MemoryNamespace =
        explicitNamespaceValue.toMemoryNamespaceOverride() ?: MemoryNamespace.Global

    internal fun defaultStandaloneProjectPath(): String =
        System.getProperty("gromozeka.project.root")
            ?: settingsService.homeDirectory

    private fun Conversation.Message.hasUserAuthoredContent(): Boolean =
        content.any { it is Conversation.Message.ContentItem.UserMessage }

    private fun Conversation.Message.isSyntheticMemoryMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"
}
