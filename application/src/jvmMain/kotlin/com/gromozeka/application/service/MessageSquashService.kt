package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.repository.ConversationDomainService
import com.gromozeka.domain.service.ChatModelProvider
import klog.KLoggers
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

@Service
class MessageSquashService(
    private val chatModelProvider: ChatModelProvider,
    private val conversationService: ConversationDomainService,
    private val messageConversionService: MessageConversionService
) {
    private val log = KLoggers.logger(this)

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

        val springAIMessages = messageConversionService.convertHistoryToSpringAI(markedMessages)
        val commandMessage = UserMessage(commandPrompt)

        val fullPrompt = springAIMessages + commandMessage

        log.debug { "Calling AI with ${fullPrompt.size} messages (${springAIMessages.size} history + 1 command)" }

        val chatModel = chatModelProvider.getChatModel(aiProvider, modelName, projectPath)
        val result = chatModel.stream(Prompt(fullPrompt))
            .collectList()
            .awaitSingle()
            .lastOrNull()
            ?.result?.output?.text
            ?: ""

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
