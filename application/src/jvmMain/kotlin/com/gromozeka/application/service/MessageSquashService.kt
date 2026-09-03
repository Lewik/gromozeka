package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntimeProvider
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class MessageSquashService internal constructor(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val conversationRepository: ConversationRepository,
    private val threadMessageRepository: ThreadMessageRepository,
    private val toolCallPairingService: ToolCallPairingService,
    private val compactionCommitter: ContextCompactionCommitter,
) {
    private val log = KLoggers.logger(this)

    internal suspend fun compactRuntimeHistory(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
    ): Conversation {
        require(messageIds.size >= 2) { "Need at least 2 messages to compact" }
        require(messageIds.distinct().size == messageIds.size) { "Duplicate message IDs are not allowed" }

        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        val expectedThreadId = conversation.currentThread
        val allMessages = threadMessageRepository.getMessagesByThread(expectedThreadId)
        require(allMessages.count { it.id in messageIds } == messageIds.size) {
            "Some messages are not in the current conversation thread"
        }

        val sourceIdSet = toolCallPairingService.includePairedToolMessages(allMessages, messageIds)
        val sourceMessageIds = allMessages.map(Conversation.Message::id).filter(sourceIdSet::contains)
        ensureMessagesAreNotCoveredByCompaction(
            messages = allMessages,
            targetMessageIds = sourceIdSet,
            operation = "compact",
            allowLatestReadableCompaction = true,
        )

        val generated = when (strategy) {
            SquashType.CONCATENATE -> GeneratedCompaction(
                text = MessageCompactionTextRenderer.render(allMessages.filter { it.id in sourceIdSet }),
                providerScope = null,
                promptTemplate = null,
            )

            SquashType.DISTILL, SquashType.SUMMARIZE -> generateWithAi(
                conversation = conversation,
                allMessages = allMessages,
                sourceIdSet = sourceIdSet,
                strategy = strategy,
            )
        }

        val result = Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(generated.text),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
            strategy = strategy.toCompactionStrategy(),
            sourceMessageIds = sourceMessageIds,
            providerScope = generated.providerScope,
            promptTemplate = generated.promptTemplate,
        )

        return compactionCommitter.commit(conversationId, expectedThreadId, result)
    }

    private suspend fun generateWithAi(
        conversation: Conversation,
        allMessages: List<Conversation.Message>,
        sourceIdSet: Set<Conversation.Message.Id>,
        strategy: SquashType,
    ): GeneratedCompaction {
        val runtimeSelection = aiConfigurationProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MESSAGE_SQUASH)
        val resolvedRuntime = aiConfigurationProvider.resolveAiRuntime(runtimeSelection)
        val promptTemplate = strategy.promptTemplate()
        val markedMessages = allMessages.map { message ->
            if (message.id in sourceIdSet) message.asCompactionSelection() else message
        }
        val commandMessage = Conversation.Message(
            id = Conversation.Message.Id("compaction-command"),
            conversationId = conversation.id,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(strategy.promptText())),
            createdAt = kotlin.time.Clock.System.now(),
        )

        log.info {
            "Starting AI compaction: strategy=$strategy sourceCount=${sourceIdSet.size} " +
                "runtime=${runtimeSelection.modelConfigurationId.value}"
        }
        val response = aiRuntimeProvider.getRuntime(runtimeSelection, workspaceRootPath = null).call(
            AiRuntimeRequest(
                systemPrompts = emptyList(),
                messages = markedMessages + commandMessage,
                options = AiRuntimeOptions(
                    toolContext = mapOf(
                        "conversationId" to conversation.id.value,
                        "threadId" to conversation.currentThread.value,
                        "projectId" to conversation.projectId.value,
                    ),
                    usagePurpose = "MESSAGE_SQUASH",
                ),
            )
        )
        val text = AiConversationMessageMapper.extractAssistantText(response).trim()
        require(text.isNotBlank()) { "AI returned an empty compaction result" }

        return GeneratedCompaction(
            text = text,
            providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(
                provider = resolvedRuntime.connection.kind.name,
                connectionId = resolvedRuntime.connection.id.value,
                modelConfigurationId = resolvedRuntime.modelConfiguration.id.value,
                modelName = resolvedRuntime.modelConfiguration.providerModelId,
            ),
            promptTemplate = promptTemplate,
        )
    }

    private fun Conversation.Message.asCompactionSelection(): Conversation.Message {
        val content = MessageCompactionTextRenderer.render(listOf(this))
            .replace("</selection", "< /selection", ignoreCase = true)
        val selection = "<selection>\n$content\n</selection>"
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

    private data class GeneratedCompaction(
        val text: String,
        val providerScope: Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope?,
        val promptTemplate: Conversation.Message.ContentItem.ContextCompactionResult.PromptTemplateReference?,
    )
}

private fun SquashType.toCompactionStrategy(): Conversation.Message.ContentItem.ContextCompactionResult.Strategy =
    when (this) {
        SquashType.CONCATENATE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.CONCATENATE
        SquashType.SUMMARIZE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.SUMMARIZE
        SquashType.DISTILL -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.DISTILL
    }

private fun SquashType.promptTemplate(): Conversation.Message.ContentItem.ContextCompactionResult.PromptTemplateReference =
    Conversation.Message.ContentItem.ContextCompactionResult.PromptTemplateReference(
        id = when (this) {
            SquashType.DISTILL -> "gromozeka.message-compaction.distill"
            SquashType.SUMMARIZE -> "gromozeka.message-compaction.summarize"
            SquashType.CONCATENATE -> error("Concatenation has no AI prompt template")
        },
        version = 1,
    )

private fun SquashType.promptText(): String = when (this) {
    SquashType.DISTILL -> """
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

    SquashType.SUMMARIZE -> """
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

    SquashType.CONCATENATE -> error("Concatenation has no AI prompt")
}
