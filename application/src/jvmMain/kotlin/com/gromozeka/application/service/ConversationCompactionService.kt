package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Checkpoint
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import org.springframework.stereotype.Service

/** Runs under the existing Conversation Runtime owner. No background jobs and no provider-error fallback. */
@Service
class ConversationCompactionService internal constructor(
    private val configurations: AiConfigurationProvider,
    private val history: ThreadMessageRepository,
    private val summaries: MessageSquashService,
    private val committer: ContextCompactionCommitter,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val events: ConversationRuntimeEventBus,
) {
    suspend fun compactIfNeeded(
        task: ConversationRuntimeTask,
        conversation: Conversation,
        agent: AgentDefinition,
        runtime: AiRuntime,
        request: AiRuntimeRequest,
        alreadyCompacted: Boolean,
        additionalInputTokens: Int = 0,
        beforeCommit: suspend () -> Unit,
    ): Conversation? {
        val checkpointId = Conversation.Message.Id("${task.id.value}:compaction")
        if (history.contains(conversation.currentThread, checkpointId)) {
            publishHistoryChange(task)
            return conversation
        }
        val resolved = configurations.resolveAiRuntime(agent.runtimeSelection)
        val threshold = resolved.modelConfiguration.compaction.thresholdTokens ?: return null
        val inspection = runtime.inspectContext(request)
        require(additionalInputTokens >= 0) { "Additional context estimate cannot be negative" }
        val occupied = inspection.inputTokens.toLong() + additionalInputTokens
        if (occupied < threshold) return null
        check(!alreadyCompacted) {
            "Context still exceeds compaction threshold after compaction (${occupied} >= $threshold, ${inspection.precision}). " +
                "Reduce the request or increase the configured threshold; ordinary generation was not started."
        }
        val sources = history.getMessagesByThread(conversation.currentThread)
        require(sources.isNotEmpty()) { "There is no history to compact" }
        val message = if (inspection.nativeCompactionAvailable) {
            val response = runtime.compact(request.copy(options = request.options.copy(usagePurpose = "CONTEXT_COMPACTION")))
            check(response.outcome in setOf(AiStepOutcome.COMPLETE, AiStepOutcome.CONTINUE) && response.toolCalls.isEmpty()) {
                "Native compaction did not complete: ${response.outcome} (${response.finishReason})"
            }
            val mapped = AiConversationMessageMapper.toConversationMessages(conversation.id, response,
                Conversation.Message.Author.Agent(agent.id, agent.name))
            check(mapped.size == 1 && mapped.single().content.singleOrNull() is Checkpoint) {
                "Native compact-only operation must return exactly one checkpoint and no ordinary answer"
            }
            normalizeNative(mapped.single().copy(id = checkpointId), sources, request.messages.map { it.id })
        } else {
            val readable = ConversationContext(sources).messagesForProvider(null)
            val generated = summaries.generateWithAi(conversation, readable,
                SquashType.SUMMARIZE, agent.runtimeSelection, request.options.toolContext)
            Conversation.Message(
                id = checkpointId, conversationId = conversation.id, role = Conversation.Message.Role.ASSISTANT,
                author = Conversation.Message.Author.Agent(agent.id, agent.name), createdAt = Clock.System.now(),
                content = listOf(Checkpoint(
                    payload = Checkpoint.Payload.ReadableSummary(generated.text), origin = Checkpoint.Origin.GROMOZEKA_POLICY,
                    strategy = Checkpoint.Strategy.SUMMARIZE, sourceMessageIds = sources.map { it.id },
                    coverage = Checkpoint.Coverage.SELECTED_MESSAGES, providerScope = generated.providerScope,
                    promptTemplate = generated.promptTemplate,
                )),
            )
        }
        currentCoroutineContext().ensureActive()
        beforeCommit()
        check(configurations.resolveAiRuntime(agent.runtimeSelection) == resolved) { "Model configuration changed during compaction" }
        val updated = committer.commit(conversation.currentThread, message)
        publishHistoryChange(task)
        return updated
    }

    /** Native checkpoints emitted by a provider-managed generation use the same COW commit. */
    suspend fun commitNative(
        task: ConversationRuntimeTask,
        conversation: Conversation,
        message: Conversation.Message,
        capturedInputIds: List<Conversation.Message.Id>,
        beforeCommit: suspend () -> Unit,
    ): Conversation {
        val sources = history.getMessagesByThread(conversation.currentThread)
        val normalized = normalizeNative(message, sources, capturedInputIds)
        currentCoroutineContext().ensureActive()
        beforeCommit()
        val updated = committer.commit(conversation.currentThread, normalized)
        publishHistoryChange(task)
        return updated
    }

    private fun normalizeNative(message: Conversation.Message, sources: List<Conversation.Message>,
        capturedInputIds: List<Conversation.Message.Id>): Conversation.Message {
        val checkpoint = message.content.single() as Checkpoint
        require(checkpoint.payload is Checkpoint.Payload.OpaqueProviderState && checkpoint.coversAllPrevious) {
            "Native compaction must identify its complete input boundary"
        }
        val ids = checkpoint.sourceMessageIds.ifEmpty { capturedInputIds }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && sources.map { it.id }.containsAll(ids)) {
            "Native compaction input is not a valid captured history prefix"
        }
        val boundary = sources.indexOfLast { it.id in ids }
        // The provider sees the projected prefix, while coverage names all archived canonical sources.
        return message.copy(content = listOf(checkpoint.copy(sourceMessageIds = sources.take(boundary + 1).map { it.id })))
    }

    private suspend fun publishHistoryChange(task: ConversationRuntimeTask) {
        val event = ConversationRuntimeEvent.HistoryChanged(task.conversationId, task.id, ConversationHistoryMutationKind.COMPACT)
        val entry = runtimeCoordinator.recordEvent(event)
        events.publish(event.copy(cursorSequence = entry.sequence))
    }
}
