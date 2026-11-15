package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.withoutProviderReplay
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Checkpoint
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.CancellationException
import klog.KLoggers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import org.springframework.stereotype.Service

/** Application is Runtime-owned; preparation may run independently against an immutable captured prefix. */
@Service
class ConversationCompactionService internal constructor(
    private val configurations: AiConfigurationProvider,
    private val history: ThreadMessageRepository,
    private val summaries: MessageSquashService,
    private val committer: ContextCompactionCommitter,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val events: ConversationRuntimeEventBus,
    private val runtimes: AiRuntimeProvider,
    private val preparations: ProactiveCompactionJobs,
    private val preparationScope: CompactionPreparationScope,
    private val toolPairs: ToolCallPairingService,
) {
    private val log = KLoggers.logger(this)

    suspend fun compactIfNeeded(
        task: ConversationRuntimeTask,
        conversation: Conversation,
        agent: AgentDefinition,
        runtime: AiRuntime,
        request: AiRuntimeRequest,
        alreadyCompacted: Boolean,
        additionalInputTokens: Int = 0,
        promptScope: List<String> = request.systemPrompts,
        beforeCommit: suspend () -> Unit,
    ): Conversation? {
        val checkpointId = Conversation.Message.Id("${task.id.value}:compaction")
        if (history.contains(conversation.currentThread, checkpointId)) {
            publishHistoryChange(task)
            return conversation
        }
        val resolved = configurations.resolveAiRuntime(agent.runtimeSelection)
        val threshold = resolved.modelConfiguration.compaction.thresholdTokens ?: return null
        val inspection = inspectContext(resolved, runtime, request)
        check(configurations.resolveAiRuntime(agent.runtimeSelection) == resolved) { "Model configuration changed during context counting" }
        require(additionalInputTokens >= 0)
        val occupied = inspection.inputTokens.toLong() + additionalInputTokens
        if (alreadyCompacted) log.info { "COMPACTION phase=verified task=${task.id.value} inputTokens=$occupied precision=${inspection.precision}" }
        var prepared = runtimeCoordinator.proactiveCompaction(conversation.id)
        if (occupied < threshold && prepared?.status != ProactiveCompaction.Status.READY) return null
        val sources = history.getMessagesByThread(conversation.currentThread)
        if (prepared != null && prepared.status != ProactiveCompaction.Status.APPLIED) {
            if (prepared.status == ProactiveCompaction.Status.READY && history.contains(conversation.currentThread, Conversation.Message.Id(prepared.id))) {
                preparations.finish(prepared, ProactiveCompaction.Status.APPLIED)
                publishHistoryChange(task)
                return conversation
            }
            check(!(prepared.status == ProactiveCompaction.Status.FAILED && prepared.outcomeUnknown && occupied >= threshold)) {
                "Compaction outcome is unknown; inspect it or use Stop to discard the preparation before a new request"
            }
            val valid = prepared.agentId == agent.id && prepared.actorUserId == task.actorUserId &&
                prepared.threadId == conversation.currentThread && sources.size >= prepared.capturedMessageCount &&
                compactionHistoryFingerprint(sources.take(prepared.capturedMessageCount)) == prepared.historyFingerprint &&
                preparationScope.fingerprint(conversation, agent, prepared.actorUserId, promptScope) == prepared.scopeFingerprint
            if (!valid) {
                preparations.finish(prepared, ProactiveCompaction.Status.DISCARDED)
                prepared = null
            } else if (prepared.status == ProactiveCompaction.Status.RUNNING && occupied >= threshold) {
                log.info { "COMPACTION phase=waiting id=${prepared.id} inputTokens=$occupied threshold=$threshold" }
                val awaitedId = prepared.id
                preparations.await(prepared)
                currentCoroutineContext().ensureActive()
                beforeCommit()
                prepared = runtimeCoordinator.proactiveCompaction(conversation.id)
                if (prepared?.id != awaitedId || prepared.status in setOf(ProactiveCompaction.Status.CANCELLED, ProactiveCompaction.Status.DISCARDED)) {
                    throw CancellationException("Compaction preparation was stopped or invalidated")
                }
            }
            if (prepared?.status == ProactiveCompaction.Status.READY) {
                currentCoroutineContext().ensureActive()
                beforeCommit()
                check(preparationScope.fingerprint(conversation, agent, prepared.actorUserId, promptScope) == prepared.scopeFingerprint) {
                    "Compaction configuration changed before application"
                }
                // Stop changes this receipt under the same Runtime lock as foreground cancellation.
                check(runtimeCoordinator.proactiveCompaction(conversation.id) == prepared) { "Compaction preparation was cancelled or replaced" }
                val updated = committer.commit(conversation.currentThread, requireNotNull(prepared.checkpoint), prepared.id)
                preparations.finish(prepared, ProactiveCompaction.Status.APPLIED)
                publishHistoryChange(task)
                return updated
            }
            if (prepared?.status == ProactiveCompaction.Status.FAILED && occupied >= threshold) {
                log.info { "COMPACTION phase=fallback id=${prepared.id} mode=blocking reason=${prepared.failureCode}" }
            }
        }
        if (occupied < threshold) return null
        currentCoroutineContext().ensureActive()
        beforeCommit()
        check(!alreadyCompacted) {
            "Context still exceeds compaction threshold after compaction ($occupied >= $threshold, ${inspection.precision}). Ordinary generation was not started."
        }
        val prefix = CompactionHistoryWindow.prefix(sources, toolPairs)
        require(prefix.isNotEmpty()) { "Context exceeds the blocking threshold but all history is inside the retained 80000-character suffix; select older messages explicitly or adjust the threshold" }
        val started = System.nanoTime()
        log.info { "COMPACTION phase=start id=${checkpointId.value} mode=blocking inputTokens=$occupied sources=${prefix.size} retained=${sources.size - prefix.size}" }
        val scope = task.actorUserId?.let { preparationScope.fingerprint(conversation, agent, it, promptScope) }
        val message = generateCheckpoint(conversation, agent, runtime, request, prefix, checkpointId)
        currentCoroutineContext().ensureActive()
        beforeCommit()
        check(configurations.resolveAiRuntime(agent.runtimeSelection) == resolved) { "Model configuration changed during compaction" }
        if (scope != null) check(preparationScope.fingerprint(conversation, agent, requireNotNull(task.actorUserId), promptScope) == scope) {
            "Compaction scope changed during blocking preparation"
        }
        val updated = committer.commit(conversation.currentThread, message)
        prepared?.takeIf { it.status == ProactiveCompaction.Status.FAILED }?.let { preparations.finish(it, ProactiveCompaction.Status.DISCARDED) }
        publishHistoryChange(task)
        log.info { "COMPACTION phase=applied id=${checkpointId.value} mode=blocking elapsedMs=${(System.nanoTime() - started) / 1_000_000}" }
        return updated
    }

    suspend fun prepareAfterResponse(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        conversation: Conversation,
        agent: AgentDefinition,
        request: AiRuntimeRequest,
        promptScope: List<String> = request.systemPrompts,
    ) {
        val resolved = configurations.resolveAiRuntime(agent.runtimeSelection)
        val threshold = resolved.modelConfiguration.compaction.proactiveThresholdTokens ?: return
        val actor = task.actorUserId ?: return
        if (runtimeCoordinator.proactiveCompaction(conversation.id)?.cancellable == true) return
        // Use the active request window to trigger preparation. Fetch archived sources only when needed.
        val inspectionRequest = if (request.messages.isEmpty()) request.copy(messages = history.getMessagesByThread(conversation.currentThread)) else request
        val estimate = AiContextMeasurement.portableInspection(resolved, inspectionRequest)?.inputTokens
            ?: AiRequestTokenEstimate.input(inspectionRequest.copy(messages = inspectionRequest.projectedMessages(resolved.connection.kind.nativeCompactionProvider())))
        if (estimate < threshold) return
        val sources = history.getMessagesByThread(conversation.currentThread)
        if (sources.isEmpty() || sources.any { message -> message.content.any { it.state == Conversation.Message.BlockState.STREAMING } }) return
        val captured = request.copy(messages = sources, options = request.options.copy(toolContext = request.options.toolContext +
            ("threadId" to conversation.currentThread.value)))
        val prefix = CompactionHistoryWindow.prefix(sources, toolPairs)
        if (prefix.isEmpty()) {
            log.debug { "COMPACTION phase=skipped task=${task.id.value} reason=only_retained_suffix" }
            return
        }
        val signature = preparationScope.fingerprint(conversation, agent, actor, promptScope)
        val preparation = ProactiveCompaction(
            id = "${task.id.value}:proactive-compaction", conversationId = conversation.id,
            sourceTaskId = task.id, sourceTurnId = task.turnId, executor = executor, agentId = agent.id, actorUserId = actor,
            threadId = conversation.currentThread, capturedMessageCount = sources.size,
            historyFingerprint = compactionHistoryFingerprint(sources), scopeFingerprint = signature,
            inputTokens = estimate, sourceCount = prefix.size, retainedCount = sources.size - prefix.size, createdAt = Clock.System.now(),
        )
        preparations.start(preparation) {
            if (preparationScope.fingerprint(conversation, agent, actor, promptScope) != signature) throw CompactionScopeChangedException()
            val preparationRuntime = runtimes.getRuntime(agent.runtimeSelection, null)
            val checkpoint = generateCheckpoint(conversation, agent, preparationRuntime, captured, prefix, Conversation.Message.Id(preparation.id))
            if (preparationScope.fingerprint(conversation, agent, actor, promptScope) != signature) throw CompactionScopeChangedException()
            checkpoint
        }
    }

    private suspend fun generateCheckpoint(
        conversation: Conversation,
        agent: AgentDefinition,
        runtime: AiRuntime,
        request: AiRuntimeRequest,
        prefix: List<Conversation.Message>,
        checkpointId: Conversation.Message.Id,
    ): Conversation.Message {
        val resolved = configurations.resolveAiRuntime(agent.runtimeSelection)
        val compactionContext = request.options.toolContext + ("aiCallDiagnosticId" to checkpointId.value)
        val nativeMessages = ConversationContext(prefix).messagesForProvider(resolved.connection.kind.nativeCompactionProvider())
        val prefixRequest = request.copy(messages = summaries.materializeSnapshot(conversation.id, nativeMessages),
            options = request.options.copy(usagePurpose = "CONTEXT_COMPACTION", toolContext = compactionContext +
                ("compactionTransportId" to checkpointId.value)))
        val nativeAvailable = runtime.capabilities.supportsDetachedNativeCompaction &&
            runtime.inspectContext(prefixRequest, allowProviderCount = false).nativeCompactionAvailable
        log.info { "COMPACTION phase=generating id=${checkpointId.value} method=${if (nativeAvailable) "native-prefix" else "text"} sources=${prefix.size}" }
        if (nativeAvailable) {
            val response = runtime.compact(prefixRequest)
            check(response.outcome in setOf(AiStepOutcome.COMPLETE, AiStepOutcome.CONTINUE) && response.toolCalls.isEmpty()) {
                "Native compaction did not complete: ${response.outcome}"
            }
            val mapped = AiConversationMessageMapper.toConversationMessages(conversation.id, response,
                Conversation.Message.Author.Agent(agent.id, agent.name))
            check(mapped.size == 1 && mapped.single().content.singleOrNull() is Checkpoint) {
                "Native compact-only operation must return exactly one checkpoint and no ordinary answer"
            }
            return normalizeNative(mapped.single().copy(id = checkpointId), prefix, prefixRequest.messages.map { it.id })
        }
        // A live-session /compact cannot compact just an older prefix while retaining its suffix.
        // Use the existing configured text summarizer, not a speculative mutation of that session.
        val readable = ConversationContext(prefix).messagesForProvider(null)
        val generated = summaries.generateWithAi(conversation, readable,
            SquashType.SUMMARIZE, agent.runtimeSelection, compactionContext)
        log.info { "COMPACTION phase=generated id=${checkpointId.value} summaryChars=${generated.text.length}" }
        return Conversation.Message(
            id = checkpointId, conversationId = conversation.id, role = Conversation.Message.Role.ASSISTANT,
            author = Conversation.Message.Author.Agent(agent.id, agent.name), createdAt = Clock.System.now(),
            content = listOf(Checkpoint(
                payload = Checkpoint.Payload.ReadableSummary(generated.text), origin = Checkpoint.Origin.GROMOZEKA_POLICY,
                strategy = Checkpoint.Strategy.SUMMARIZE, sourceMessageIds = prefix.map { it.id },
                coverage = Checkpoint.Coverage.SELECTED_MESSAGES, providerScope = generated.providerScope,
                promptTemplate = generated.promptTemplate,
            )),
        )
    }

    private suspend fun inspectContext(resolved: ResolvedAiRuntime, runtime: AiRuntime, request: AiRuntimeRequest): AiContextInspection {
        AiContextMeasurement.portableInspection(resolved, request)?.let { return it }
        val policy = resolved.modelConfiguration.compaction.tokenCounting
        val projected = request.copy(messages = request.projectedMessages(resolved.connection.kind.nativeCompactionProvider()))
        fun local() = AiContextInspection(AiRequestTokenEstimate.input(projected), AiContextInspection.Precision.ESTIMATE)
        return when (policy.mode) {
            AiModelConfiguration.TokenCounting.Mode.LOCAL -> local()
            AiModelConfiguration.TokenCounting.Mode.CURRENT ->
                if (resolved.connection.kind.hasContextCountApi) runtime.inspectContext(request) else local()
            AiModelConfiguration.TokenCounting.Mode.MODEL_CONFIGURATION -> {
                val selection = AiRuntimeSelection(requireNotNull(policy.modelConfigurationId))
                val counter = configurations.resolveAiRuntime(selection)
                check(counter.connection.kind.hasContextCountApi) { "Selected context counter has no count API" }
                var opaqueTokens = 0L
                val messages = projected.messages.mapNotNull { message ->
                    val (opaque, portable) = message.content.partition { item ->
                        item is Conversation.Message.ContentItem.Thinking ||
                            (item is Checkpoint && item.payload is Checkpoint.Payload.OpaqueProviderState)
                    }
                    if (opaque.isNotEmpty()) opaqueTokens += AiRequestTokenEstimate.message(message.copy(content = opaque, instructions = emptyList()))
                    if (portable.isEmpty() && message.instructions.isEmpty()) null
                        else message.copy(content = portable).withoutProviderReplay()
                }
                // Signed/opaque state is never forwarded to a different model. Count its
                // local estimate alongside the portable active window, not its archived sources.
                if (messages.isEmpty()) return local()
                val countRequest = projected.copy(messages = messages, options = projected.options.copy(
                    reasoning = null, maxOutputTokens = null, usagePurpose = "CONTEXT_COUNT",
                ))
                val result = runtimes.getRuntime(selection, null).inspectContext(countRequest)
                check(result.precision == AiContextInspection.Precision.PROVIDER_COUNT) { "Selected context counter did not return a provider count" }
                check(configurations.resolveAiRuntime(selection) == counter) { "Context counter changed during counting" }
                val tokens = result.inputTokens.toLong() + opaqueTokens
                check(tokens <= Int.MAX_VALUE) { "Context count exceeds the supported range" }
                AiContextInspection(tokens.toInt(), AiContextInspection.Precision.REFERENCE_MODEL_ESTIMATE)
            }
        }
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
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && capturedInputIds.take(ids.size) == ids &&
            sources.map { it.id }.containsAll(ids)) {
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
