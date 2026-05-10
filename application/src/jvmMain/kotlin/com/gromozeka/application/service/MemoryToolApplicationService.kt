package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MEMORY_RECALL_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
class MemoryToolApplicationService(
    private val conversationService: ConversationDomainService,
    private val agentDomainService: AgentDomainService,
    private val aiToolProvider: AiToolProvider,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)

    suspend fun remember(
        conversationIdValue: String,
        targetMessageId: String? = null,
    ): String = runMemoryTool(MEMORY_REMEMBER_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryMessageRoutingApplicationService.routeMessage(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            message = targetMessage,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
        )
        MemoryToolResultRenderer.rememberResultJsonString(result)
    }

    suspend fun rememberProvidedText(
        conversationIdValue: String,
        text: String,
        mode: String? = null,
    ): String {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return MemoryToolResultRenderer.failureJsonString("Provided memory text is blank.")
        }

        if (!settingsProvider.knowledgeMemoryEnabled) {
            return MemoryToolResultRenderer.failureJsonString("Knowledge memory is disabled in settings.")
        }

        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = Conversation.Message(
                id = Conversation.Message.Id(uuid7()),
                conversationId = context.conversation.id,
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(normalizedText)),
                providerMetadata = buildJsonObject {
                    put("memoryToolOrigin", "provided_text")
                    put("userConsentConfirmed", true)
                    mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                },
                createdAt = Clock.System.now(),
            )
            val result = memoryMessageRoutingApplicationService.routeMessage(
                conversationId = context.conversation.id,
                threadId = context.conversation.currentThread,
                message = targetMessage,
                agent = context.agent,
                project = context.project,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                threadContextMessages = context.threadMessages + targetMessage,
            )
            MemoryToolResultRenderer.rememberResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_REMEMBER_TOOL_NAME conversation=$conversationIdValue target=provided_text error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    suspend fun recall(
        conversationIdValue: String,
        targetMessageId: String? = null,
    ): String = runMemoryTool(MEMORY_RECALL_TOOL_NAME, conversationIdValue, targetMessageId) { context, targetMessage ->
        val result = memoryApplicationService.buildRuntimeMemoryReadResult(
            conversationId = context.conversation.id,
            threadId = context.conversation.currentThread,
            targetMessage = targetMessage,
            threadMessages = context.threadMessages,
            agent = context.agent,
            project = context.project,
            runtimeSystemPrompts = context.systemPrompts,
            runtimeTools = context.memoryTools,
        )
        MemoryToolResultRenderer.recallResultJsonString(result)
    }

    private suspend fun runMemoryTool(
        toolName: String,
        conversationIdValue: String,
        targetMessageId: String?,
        action: suspend (MemoryToolContext, Conversation.Message) -> String,
    ): String {
        if (!settingsProvider.knowledgeMemoryEnabled) {
            return MemoryToolResultRenderer.failureJsonString("Knowledge memory is disabled in settings.")
        }

        return runCatching {
            val conversationId = Conversation.Id(conversationIdValue)
            val context = resolveContext(conversationId)
            val targetMessage = resolveTargetMessage(context.threadMessages, targetMessageId)
            action(context, targetMessage)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$toolName conversation=$conversationIdValue target=${targetMessageId ?: "previous_user_message"} error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory tool failed.")
        }
    }

    private suspend fun resolveContext(conversationId: Conversation.Id): MemoryToolContext {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found: ${conversationId.value}")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found for conversation ${conversationId.value}: ${conversation.agentDefinitionId.value}")
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        val threadMessages = conversationService.loadCurrentMessages(conversationId)

        return MemoryToolContext(
            conversation = conversation,
            agent = agent,
            project = project,
            systemPrompts = systemPrompts,
            memoryTools = memoryTools,
            threadMessages = threadMessages,
        )
    }

    private fun resolveTargetMessage(
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

    private fun Conversation.Message.hasUserAuthoredContent(): Boolean =
        content.any { it is Conversation.Message.ContentItem.UserMessage }

    private fun Conversation.Message.isSyntheticMemoryMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"

    private data class MemoryToolContext(
        val conversation: Conversation,
        val agent: AgentDefinition,
        val project: Project,
        val systemPrompts: List<String>,
        val memoryTools: List<AiToolCallback>,
        val threadMessages: List<Conversation.Message>,
    )
}
