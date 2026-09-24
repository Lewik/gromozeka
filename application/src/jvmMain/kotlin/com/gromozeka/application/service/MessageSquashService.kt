package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.ai.AiToolChoice
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
        val minimum = if (strategy == SquashType.CONCATENATE) 2 else 1
        require(messageIds.size >= minimum) { "Need at least $minimum message(s) to compact" }
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
        require(allMessages.filter { it.id in sourceIdSet }.all { message ->
            message.content.none { it.state == Conversation.Message.BlockState.STREAMING }
        }) { "Wait for selected messages to finish streaming before compaction" }
        val readableMessages = ConversationContext(allMessages)
            .messagesForProvider(provider = null, selection = sourceIdSet)
        val selectedText = MessageCompactionTextRenderer.render(readableMessages)

        val generated = when (strategy) {
            SquashType.CONCATENATE -> GeneratedCompaction(
                text = selectedText,
                providerScope = null,
                promptTemplate = null,
            )

            SquashType.DISTILL, SquashType.SUMMARIZE -> generateWithAi(
                conversation = conversation,
                selectedText = selectedText,
                sourceCount = sourceIdSet.size,
                strategy = strategy,
            )
        }

        val result = Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(generated.text),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
            strategy = strategy.toCompactionStrategy(),
            sourceMessageIds = sourceMessageIds,
            coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
            providerScope = generated.providerScope,
            promptTemplate = generated.promptTemplate,
        )

        return compactionCommitter.commit(conversationId, expectedThreadId, result)
    }

    private suspend fun generateWithAi(
        conversation: Conversation,
        selectedText: String,
        sourceCount: Int,
        strategy: SquashType,
    ): GeneratedCompaction {
        val runtimeSelection = aiConfigurationProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MESSAGE_SQUASH)
        val resolvedRuntime = aiConfigurationProvider.resolveAiRuntime(runtimeSelection)
        val promptTemplate = strategy.promptTemplate()
        val commandMessage = Conversation.Message(
            id = Conversation.Message.Id("compaction-command"),
            conversationId = conversation.id,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(
                strategy.promptText() + "\n\n<selection>\n" +
                    selectedText.replace("</selection", "< /selection", ignoreCase = true) + "\n</selection>"
            )),
            createdAt = kotlin.time.Clock.System.now(),
        )

        log.info {
            "Starting AI compaction: strategy=$strategy sourceCount=$sourceCount " +
                "runtime=${runtimeSelection.modelConfigurationId.value}"
        }
        val response = aiRuntimeProvider.getRuntime(runtimeSelection, workspaceRootPath = null).call(
            AiRuntimeRequest(
                systemPrompts = emptyList(),
                // A fresh service request, not a replay of the live conversation.
                // Historical raw provider messages must not override selection text.
                messages = listOf(commandMessage),
                options = AiRuntimeOptions(
                    toolChoice = AiToolChoice.None,
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
