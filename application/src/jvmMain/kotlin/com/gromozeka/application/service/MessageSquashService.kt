package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.MessageSquashService as MessageSquashServiceSpec
import com.gromozeka.domain.service.AgentDomainService
import klog.KLoggers
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt as SpringPrompt
import org.springframework.stereotype.Service

@Service
class MessageSquashService(
    private val chatModelProvider: ChatModelProvider,
    private val conversationService: ConversationDomainService,
    private val messageConversionService: MessageConversionService,
    private val promptDomainService: PromptDomainService,
    private val agentDomainService: AgentDomainService
) : MessageSquashServiceSpec {
    companion object {
        private val COMMON_PROMPT_PREFIX_ID = Prompt.Id("builtin:common-prompt-prefix.md")
    }
    private val log = KLoggers.logger(this)

    /**
     * Implementation of domain specification.
     * 
     * Delegates to squashWithAI() for AI-based strategies.
     * For CONCATENATE, performs simple text merge without AI.
     */
    override suspend fun squash(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
        projectPath: String?
    ): MessageSquashServiceSpec.SquashResult {
        // Validation
        if (messageIds.size < 2) {
            return MessageSquashServiceSpec.SquashResult.Failure(
                reason = "At least 2 messages required for squashing, got ${messageIds.size}",
                errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.INSUFFICIENT_MESSAGES
            )
        }

        // Get conversation to determine AI provider/model
        val conversation = conversationService.findById(conversationId)
            ?: return MessageSquashServiceSpec.SquashResult.Failure(
                reason = "Conversation not found: $conversationId",
                errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.MESSAGES_NOT_FOUND
            )

        // Get agent definition for AI model
        val agentDefinition = agentDomainService.findById(conversation.agentDefinitionId)
            ?: return MessageSquashServiceSpec.SquashResult.Failure(
                reason = "Agent definition not found: ${conversation.agentDefinitionId}",
                errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.AI_GENERATION_FAILED
            )

        val aiProvider = try {
            AIProvider.valueOf(agentDefinition.aiProvider)
        } catch (e: IllegalArgumentException) {
            return MessageSquashServiceSpec.SquashResult.Failure(
                reason = "Invalid AI provider: ${agentDefinition.aiProvider}",
                errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.AI_GENERATION_FAILED
            )
        }

        return try {
            when (strategy) {
                SquashType.CONCATENATE -> {
                    // Simple concatenation without AI
                    val messages = conversationService.loadCurrentMessages(conversationId)
                    val selectedMessages = messages.filter { it.id in messageIds }
                    
                    if (selectedMessages.size != messageIds.size) {
                        return MessageSquashServiceSpec.SquashResult.Failure(
                            reason = "Some messages not found in conversation",
                            errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.MESSAGES_NOT_FOUND
                        )
                    }

                    val concatenated = selectedMessages.joinToString("\n\n") { message ->
                        message.content
                            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                            .joinToString(" ") { it.text }
                    }

                    MessageSquashServiceSpec.SquashResult.Success(
                        squashedContent = concatenated,
                        originalMessageCount = selectedMessages.size,
                        strategy = strategy,
                        tokensSaved = null
                    )
                }

                SquashType.SUMMARIZE, SquashType.DISTILL -> {
                    // AI-based squashing
                    if (projectPath == null) {
                        return MessageSquashServiceSpec.SquashResult.Failure(
                            reason = "Project path required for AI-based squashing strategies",
                            errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.MISSING_PROJECT_PATH
                        )
                    }

                    val squashedContent = squashWithAI(
                        conversationId = conversationId,
                        selectedIds = messageIds,
                        squashType = strategy,
                        aiProvider = aiProvider,
                        modelName = agentDefinition.modelName,
                        projectPath = projectPath
                    )

                    MessageSquashServiceSpec.SquashResult.Success(
                        squashedContent = squashedContent,
                        originalMessageCount = messageIds.size,
                        strategy = strategy,
                        tokensSaved = null // TODO: calculate token savings
                    )
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to squash messages: ${e.message}" }
            MessageSquashServiceSpec.SquashResult.Failure(
                reason = "AI generation failed: ${e.message}",
                errorType = MessageSquashServiceSpec.SquashResult.Failure.ErrorType.AI_GENERATION_FAILED
            )
        }
    }

    suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        aiProvider: AIProvider,
        modelName: String,
        projectPath: String?
    ): String {
        require(squashType != SquashType.CONCATENATE) {
            "Use simple concatenation for CONCATENATE type, not AI"
        }

        log.info { "Starting AI squash: type=$squashType, selectedCount=${selectedIds.size}" }

        val allMessages = conversationService.loadCurrentMessages(conversationId)
        log.debug { "Loaded ${allMessages.size} messages from conversation" }

        val markedMessages = allMessages.map { message ->
            if (message.id in selectedIds) {
                wrapWithSelectionMarker(message)
            } else {
                message
            }
        }

        val commandPrompt = when (squashType) {
            SquashType.DISTILL -> buildDistillPrompt()
            SquashType.SUMMARIZE -> buildSummarizePrompt()
            SquashType.CONCATENATE -> throw IllegalStateException("Should not reach here")
        }

        val systemMessages = loadCommonPromptPrefix()
            .takeIf { it.isNotBlank() }
            ?.let { listOf(SystemMessage(it)) }
            ?: emptyList()

        val springAIMessages = messageConversionService.convertHistoryToSpringAI(markedMessages)
        val commandMessage = UserMessage(commandPrompt)

        val fullPrompt = systemMessages + springAIMessages + commandMessage

        log.debug { "Calling AI with ${fullPrompt.size} messages (${systemMessages.size} system + ${springAIMessages.size} history + 1 command)" }

        val chatModel = chatModelProvider.getChatModel(aiProvider, modelName, projectPath)
        val result = chatModel.call(SpringPrompt(fullPrompt)).result.output.text ?: ""

        log.info { "AI squash completed: result length=${result.length}" }

        return result
    }

    private fun wrapWithSelectionMarker(message: Conversation.Message): Conversation.Message {
        val wrappedContent = message.content.map { contentItem ->
            when (contentItem) {
                is Conversation.Message.ContentItem.UserMessage -> {
                    Conversation.Message.ContentItem.UserMessage(
                        text = "<selection>${contentItem.text}</selection>"
                    )
                }
                is Conversation.Message.ContentItem.AssistantMessage -> {
                    val wrappedText = contentItem.structured.fullText?.let {
                        "<selection>$it</selection>"
                    } ?: ""

                    Conversation.Message.ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(
                            fullText = wrappedText
                        )
                    )
                }
                else -> contentItem
            }
        }

        return message.copy(content = wrappedContent)
    }

    private suspend fun loadCommonPromptPrefix(): String {
        return promptDomainService.findById(COMMON_PROMPT_PREFIX_ID)?.content ?: ""
    }

    private fun buildDistillPrompt(): String {
        return """
            Distill ONLY the messages wrapped in <selection></selection> tags.

            Extract minimum high-signal information:
            - Key decisions with rationale
            - Current state (what works/implemented)
            - Open questions and blockers

            DO NOT include:
            - Reasoning process
            - Debugging details
            - Failed attempts
            - File contents (only paths if critical)

            Format:
            **Decisions:**
            - [decision with rationale]

            **State:**
            - [what works, what's implemented]

            **Blockers:**
            - [unresolved issues]

            Return ONLY the distilled content, no meta-commentary.
        """.trimIndent()
    }

    private fun buildSummarizePrompt(): String {
        return """
            Summarize ONLY the messages wrapped in <selection></selection> tags.

            Create a coherent summary covering:
            - Main topics discussed
            - Decisions made with reasoning
            - Changes implemented
            - Key findings and conclusions

            Preserve important details and structure.
            Output as readable narrative.

            Return ONLY the summary, no meta-commentary.
        """.trimIndent()
    }
}
