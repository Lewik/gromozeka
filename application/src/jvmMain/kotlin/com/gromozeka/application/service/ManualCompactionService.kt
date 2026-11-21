package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageCompactionTextRenderer
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AgentDomainService
import kotlinx.coroutines.ensureActive
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class ManualCompactionService internal constructor(
    private val agents: AgentDomainService,
    private val conversationRepository: ConversationRepository,
    private val threadMessageRepository: ThreadMessageRepository,
    private val toolCallPairingService: ToolCallPairingService,
    private val textCompactions: CompactionTextService,
    private val compactionCommitter: ContextCompactionCommitter,
) {
    private val log = KLoggers.logger(this)

    internal suspend fun compactRuntimeHistory(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
        checkpointId: Conversation.Message.Id = Conversation.Message.Id(com.gromozeka.shared.uuid.uuid7()),
        requestContext: Map<String, Any?> = emptyMap(),
        beforeCommit: (suspend () -> Unit)? = null,
    ): Conversation {
        val minimum = if (strategy == SquashType.CONCATENATE) 2 else 1
        require(messageIds.size >= minimum) { "Need at least $minimum message(s) to compact" }
        require(messageIds.distinct().size == messageIds.size) { "Duplicate message IDs are not allowed" }

        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        val expectedThreadId = conversation.currentThread
        if (threadMessageRepository.contains(expectedThreadId, checkpointId)) return conversation
        val headers = threadMessageRepository.compactionHeaders(expectedThreadId)
        val window = CompactionHistoryWindow(headers)
        val requested = messageIds.toSet()
        require(headers.count { it.id in requested } == requested.size) {
            "Some messages are not in the current conversation thread"
        }

        val full = window.isFullSelection(requested)
        val selection = if (full) window.prefix(toolCallPairingService) else {
            val paired = toolCallPairingService.includePairedHeaders(headers, requested)
            headers.filter { it.id in paired }
        }
        require(selection.size >= minimum) {
            "Full compaction has no older history outside its retained 80000-character suffix; select older messages explicitly"
        }
        require(selection.none { it.streaming }) { "Wait for selected messages to finish streaming before compaction" }
        log.info { "COMPACTION phase=start id=${checkpointId.value} mode=manual full=$full sources=${selection.size} retained=${headers.size - selection.size}" }
        val sourceMessageIds = selection.map { it.id }
        val readableMessages = window.messages(threadMessageRepository, expectedThreadId, selection, null)

        val generated = when (strategy) {
            SquashType.CONCATENATE -> CompactionTextService.GeneratedCompaction(
                text = MessageCompactionTextRenderer.render(readableMessages),
                providerScope = null,
                promptTemplate = null,
            )

            SquashType.DISTILL, SquashType.COMPACT -> textCompactions.generateWithAi(
                conversation = conversation,
                sourceMessages = readableMessages,
                strategy = strategy,
                currentSelection = currentSelection(conversation),
                requestContext = requestContext,
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

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        beforeCommit?.invoke()
        return compactionCommitter.commit(expectedThreadId, Conversation.Message(
            id = checkpointId,
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(result),
            createdAt = kotlin.time.Clock.System.now(),
        )).also { log.info { "COMPACTION phase=applied id=${checkpointId.value} mode=manual full=$full compactedChars=${generated.text.length}" } }
    }

    private suspend fun currentSelection(conversation: Conversation): AiRuntimeSelection {
        val candidates = conversation.autoRespondAgentIds.ifEmpty {
            conversation.participants.filterIsInstance<Conversation.Participant.Agent>().map { it.agentDefinitionId }.toSet()
        }
        val agentId = requireNotNull(candidates.singleOrNull()) {
            "Manual compaction requires one auto-responding agent, or one connected agent"
        }
        return requireNotNull(agents.findById(agentId)) { "Compaction agent no longer exists" }.runtimeSelection
    }
}

private fun SquashType.toCompactionStrategy(): Conversation.Message.ContentItem.ContextCompactionResult.Strategy =
    when (this) {
        SquashType.CONCATENATE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.CONCATENATE
        SquashType.COMPACT -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.COMPACT
        SquashType.DISTILL -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.DISTILL
    }
