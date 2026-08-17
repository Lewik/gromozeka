package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.MessageSquashService as MessageSquashServiceSpec
import com.gromozeka.domain.service.MessageSquashGenerationService
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class MessageSquashService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val conversationService: ConversationDomainService,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val toolCallPairingService: ToolCallPairingService,
) : MessageSquashServiceSpec, MessageSquashGenerationService {
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
                    val squashedContent = runSquashWithAI(
                        conversationId = conversationId,
                        selectedIds = messageIds,
                        squashType = strategy,
                        runtimeSelection = aiConfigurationProvider.runtimeSelectionFor(
                            AiRuntimeAssignment.Purpose.MESSAGE_SQUASH
                        ),
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

    private suspend fun runSquashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        runtimeSelection: AiRuntimeSelection,
    ): String {
        require(squashType != SquashType.CONCATENATE) {
            "Use simple concatenation for CONCATENATE type, not AI"
        }
        require(selectedIds.size >= 2) { "At least 2 messages required for AI squash" }

        log.info { "Starting AI squash: type=$squashType, selectedCount=${selectedIds.size}" }

        val allMessages = conversationService.loadCurrentMessages(conversationId)
        log.debug { "Loaded ${allMessages.size} messages from conversation" }
        require(allMessages.count { it.id in selectedIds } == selectedIds.size) {
            "Some selected messages are not in the current conversation thread"
        }
        val allSelectedIds = toolCallPairingService.includePairedToolMessages(allMessages, selectedIds)

        val markedMessages = allMessages.map { message ->
            if (message.id in allSelectedIds) {
                message.asSquashSelection()
            } else {
                message
            }
        }

        val commandPrompt = when (squashType) {
            SquashType.DISTILL -> buildDistillPrompt()
            SquashType.SUMMARIZE -> buildSummarizePrompt()
            SquashType.CONCATENATE -> throw IllegalStateException("Should not reach here")
        }

        val commandMessage = Conversation.Message(
            id = Conversation.Message.Id("squash-command"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(commandPrompt)),
            createdAt = kotlin.time.Clock.System.now()
        )

        log.debug {
            "Calling AI with ${markedMessages.size + 1} messages (${markedMessages.size} history + 1 command)"
        }

        val runtime = aiRuntimeProvider.getRuntime(runtimeSelection, workspaceRootPath = null)
        val response = runtime.call(
            AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = markedMessages + commandMessage,
                options = com.gromozeka.domain.model.ai.AiRuntimeOptions(
                    toolContext = mapOf("conversationId" to conversationId.value)
                )
            )
        )
        val result = AiConversationMessageMapper.extractAssistantText(response)

        log.info { "AI squash completed: result length=${result.length}" }

        return result
    }

    override suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        runtimeSelection: AiRuntimeSelection,
    ): String =
        runSquashWithAI(
            conversationId = conversationId,
            selectedIds = selectedIds,
            squashType = squashType,
            runtimeSelection = runtimeSelection,
        )

    private fun Conversation.Message.asSquashSelection(): Conversation.Message {
        val selection = "<selection>\n${toSquashSelectionText()}\n</selection>"
        val selectedContent = when (role) {
            Conversation.Message.Role.USER -> listOf(Conversation.Message.ContentItem.UserMessage(selection))
            Conversation.Message.Role.ASSISTANT -> listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText(fullText = selection)
                )
            )
            Conversation.Message.Role.SYSTEM -> listOf(
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
                    content = selection,
                )
            )
        }
        return copy(content = selectedContent)
    }

    private fun Conversation.Message.toSquashSelectionText(): String = buildString {
        append("role=")
        append(role.name.lowercase())
        content.forEach { item ->
            append('\n')
            append(
                when (item) {
                    is Conversation.Message.ContentItem.UserMessage -> item.text
                    is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                    is Conversation.Message.ContentItem.Thinking -> "[thinking] ${item.thinking}"
                    is Conversation.Message.ContentItem.System -> "[system:${item.level.name.lowercase()}] ${item.content}"
                    is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] ${item.call.input}"
                    is Conversation.Message.ContentItem.ToolResult -> buildString {
                        append("[tool_result:${item.toolName} error=${item.isError}]")
                        item.result.forEach { result ->
                            append('\n')
                            append(
                                when (result) {
                                    is Conversation.Message.ContentItem.ToolResult.Data.Text -> result.content
                                    is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                                        "[binary:${result.fileName ?: result.mediaType.value} media_type=${result.mediaType.value}]"
                                    is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url:${result.url}]"
                                    is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                                        "[attachment:${result.artifact.fileName} media_type=${result.artifact.mediaType}]"
                                }
                            )
                        }
                    }
                    is Conversation.Message.ContentItem.ImageItem -> "[image:${item.source.type}]"
                    is Conversation.Message.ContentItem.DocumentItem -> when (val source = item.source) {
                        is Conversation.Message.DocumentSource.Base64DocumentSource ->
                            "[document:${source.fileName} media_type=${source.mediaType}]"
                    }
                    is Conversation.Message.ContentItem.ArtifactItem ->
                        "[attachment:${item.artifact.fileName} media_type=${item.artifact.mediaType}]"
                    is Conversation.Message.ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
                        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
                        is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                            "[context_compaction:${item.providerScope?.provider ?: "unknown"}]"
                    }
                    is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
                }.replace("</selection", "< /selection", ignoreCase = true)
            )
        }
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
