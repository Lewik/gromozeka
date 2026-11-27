package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageCompactionTextRenderer
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.ai.AiRuntimeRequest
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
    private val fullCompactions: ConversationCompactionService,
    private val compactionCommitter: ContextCompactionCommitter,
    private val continuationStates: ConversationContinuationStateService = ConversationContinuationStateService(
        MessageTemporalContextService(),
    ),
) {
    private val log = KLoggers.logger(this)

    internal suspend fun compactRuntimeHistory(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        fullSelection: Boolean,
        strategy: SquashType,
        checkpointId: Conversation.Message.Id = Conversation.Message.Id(com.gromozeka.shared.uuid.uuid7()),
        requestContext: Map<String, Any?> = emptyMap(),
        nativeRequest: suspend (Conversation, AgentDefinition) -> AiRuntimeRequest? = { _, _ -> null },
        beforeCommit: (suspend () -> Unit)? = null,
    ): Conversation {
        val minimum = if (strategy == SquashType.CONCATENATE) 2 else 1
        if (fullSelection) {
            require(messageIds.isEmpty()) { "Full compaction must not include message IDs" }
        } else {
            require(messageIds.size >= minimum) { "Need at least $minimum message(s) to compact" }
        }
        require(messageIds.distinct().size == messageIds.size) { "Duplicate message IDs are not allowed" }

        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        val expectedThreadId = conversation.currentThread
        if (threadMessageRepository.contains(expectedThreadId, checkpointId)) return conversation
        val headers = threadMessageRepository.compactionWindowHeaders(expectedThreadId)
        val window = CompactionHistoryWindow(headers)
        val requested = messageIds.toSet()
        if (!fullSelection) {
            val activeIds = headers.mapTo(mutableSetOf()) { it.id }
            require(requested.all(activeIds::contains)) {
                "Some selected messages are outside the current compaction window"
            }
        }

        val selection = if (fullSelection && strategy == SquashType.COMPACT) window.prefix(toolCallPairingService)
        else if (fullSelection) window.fullSelection()
        else {
            val paired = toolCallPairingService.includePairedHeaders(headers, requested)
            headers.filter { it.id in paired }
        }
        require(selection.size >= minimum) {
            "Full compaction has no older history outside its retained 80000-character suffix; select older messages explicitly"
        }
        require(selection.none { it.streaming }) { "Wait for selected messages to finish streaming before compaction" }
        log.info { "COMPACTION phase=start id=${checkpointId.value} mode=manual full=$fullSelection sources=${selection.size} retained=${headers.size - selection.size}" }
        val checkpoint = if (fullSelection && strategy == SquashType.COMPACT) {
            val agent = currentAgent(conversation)
            val request = try {
                nativeRequest(conversation, agent)
            } catch (unavailable: ContextSnapshotUnavailableException) {
                log.info { "COMPACTION phase=native_skipped id=${checkpointId.value} reason=context_snapshot_unavailable" }
                null
            }
            fullCompactions.generateRequestedCompact(
                conversation = conversation,
                agent = agent,
                window = window,
                prefix = selection,
                checkpointId = checkpointId,
                request = request,
                requestContext = requestContext,
            )
        } else {
            val readableMessages = window.messages(
                threadMessageRepository,
                expectedThreadId,
                selection,
                provider = null,
            )
            require(readableMessages.size >= minimum) {
                "Need at least $minimum readable message(s) to compact"
            }
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
                    currentSelection = currentAgent(conversation).runtimeSelection,
                    requestContext = requestContext,
                )
            }
            val coverage = if (fullSelection) Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS
                else Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES
            val result = Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(generated.text),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                strategy = strategy.toCompactionStrategy(),
                sourceMessageIds = if (fullSelection) emptyList() else selection.map { it.id },
                sourceBoundaryMessageId = if (fullSelection) selection.last().id else null,
                coverage = coverage,
                providerScope = generated.providerScope,
                promptTemplate = generated.promptTemplate,
                continuationState = if (fullSelection) continuationStates.captureHeaders(selection)
                    else Conversation.Message.ContentItem.ContextCompactionResult.ContinuationState(),
            )
            Conversation.Message(
                id = checkpointId,
                conversationId = conversationId,
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(result),
                createdAt = kotlin.time.Clock.System.now(),
            )
        }

        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        beforeCommit?.invoke()
        return compactionCommitter.commit(expectedThreadId, checkpoint)
            .also { log.info { "COMPACTION phase=applied id=${checkpointId.value} mode=manual full=$fullSelection strategy=$strategy" } }
    }

    private suspend fun currentAgent(conversation: Conversation): AgentDefinition {
        val candidates = conversation.autoRespondAgentIds.ifEmpty {
            conversation.participants.filterIsInstance<Conversation.Participant.Agent>().map { it.agentDefinitionId }.toSet()
        }
        val agentId = requireNotNull(candidates.singleOrNull()) {
            "Manual compaction requires one auto-responding agent, or one connected agent"
        }
        return requireNotNull(agents.findById(agentId)) { "Compaction agent no longer exists" }
    }
}

private fun SquashType.toCompactionStrategy(): Conversation.Message.ContentItem.ContextCompactionResult.Strategy =
    when (this) {
        SquashType.CONCATENATE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.CONCATENATE
        SquashType.COMPACT -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.COMPACT
        SquashType.DISTILL -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.DISTILL
    }
