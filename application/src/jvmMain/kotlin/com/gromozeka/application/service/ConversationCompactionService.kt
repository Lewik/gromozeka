package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.CompactionMessageHeader
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
    private val textCompactions: CompactionTextService,
    private val committer: ContextCompactionCommitter,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val events: ConversationRuntimeEventBus,
    private val runtimes: AiRuntimeProvider,
    private val preparations: ProactiveCompactionJobs,
    private val preparationScope: CompactionPreparationScope,
    private val toolPairs: ToolCallPairingService,
    private val continuationStates: ConversationContinuationStateService = ConversationContinuationStateService(
        StickyMessageInstructionService(),
        MessageTemporalContextService(),
    ),
) {
    private val log = KLoggers.logger(this)

    suspend fun discardPreparedIfCurrent(prepared: ProactiveCompaction): Boolean =
        preparations.finish(prepared, ProactiveCompaction.Status.DISCARDED)

    suspend fun applyPreparedIfReady(
        task: ConversationRuntimeTask,
        conversation: Conversation,
        agent: AgentDefinition,
        promptScope: List<String>,
        request: AiRuntimeRequest,
        beforeCommit: suspend () -> Unit,
    ): Conversation? {
        reconcileHistoryMutations(conversation.id)
        val prepared = runtimeCoordinator.proactiveCompaction(conversation.id)
            ?.takeIf { it.status == ProactiveCompaction.Status.READY } ?: return null
        if (history.contains(conversation.currentThread, Conversation.Message.Id(prepared.id))) {
            preparations.finish(prepared, ProactiveCompaction.Status.APPLIED)
            publishHistoryChange(task)
            return conversation
        }
        val sources = history.compactionWindowHeaders(conversation.currentThread)
        val nativeProvider = configurations.resolveAiRuntime(agent.runtimeSelection)
            .connection.kind.nativeCompactionProvider()
        val scopedRequest = request.forPreparedPrefix(prepared, sources, nativeProvider)
        val valid = prepared.agentId == agent.id && prepared.actorUserId == task.actorUserId &&
            prepared.matches(conversation, sources) && scopedRequest != null
        if (!valid) {
            preparations.finish(prepared, ProactiveCompaction.Status.DISCARDED)
            return null
        }
        val currentRequest = requireNotNull(scopedRequest)
        val scope = try {
            preparationScope.fingerprint(conversation, agent, prepared.actorUserId, promptScope,
                request = currentRequest)
        } catch (_: CompactionScopeChangedException) {
            preparations.finish(prepared, ProactiveCompaction.Status.DISCARDED)
            return null
        }
        if (scope != prepared.scopeFingerprint) {
            preparations.finish(prepared, ProactiveCompaction.Status.DISCARDED)
            return null
        }
        currentCoroutineContext().ensureActive()
        beforeCommit()
        check(preparationScope.fingerprint(conversation, agent, prepared.actorUserId, promptScope,
            request = currentRequest) == prepared.scopeFingerprint) {
            "Compaction scope changed before application"
        }
        check(runtimeCoordinator.proactiveCompaction(conversation.id) == prepared) { "Compaction preparation was cancelled or replaced" }
        val updated = committer.commit(conversation.currentThread, requireNotNull(prepared.checkpoint), prepared.id)
        preparations.finish(prepared, ProactiveCompaction.Status.APPLIED)
        publishHistoryChange(task)
        return updated
    }

    suspend fun compactIfNeeded(
        task: ConversationRuntimeTask,
        conversation: Conversation,
        agent: AgentDefinition,
        runtime: AiRuntime,
        request: AiRuntimeRequest,
        alreadyCompacted: Boolean,
        promptScope: List<String> = request.systemPrompts,
        unpinnedPrompts: List<String> = request.systemPrompts,
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
        val occupied = inspection.inputTokens.toLong()
        if (alreadyCompacted) log.info { "COMPACTION phase=verified task=${task.id.value} inputTokens=$occupied precision=${inspection.precision}" }
        var prepared = runtimeCoordinator.proactiveCompaction(conversation.id)
        if (occupied < threshold && prepared?.status != ProactiveCompaction.Status.READY) return null
        val sources = history.compactionWindowHeaders(conversation.currentThread)
        if (prepared != null && prepared.status != ProactiveCompaction.Status.APPLIED) {
            if (prepared.status == ProactiveCompaction.Status.READY && history.contains(conversation.currentThread, Conversation.Message.Id(prepared.id))) {
                preparations.finish(prepared, ProactiveCompaction.Status.APPLIED)
                publishHistoryChange(task)
                return conversation
            }
            check(!(prepared.status == ProactiveCompaction.Status.FAILED && prepared.outcomeUnknown && occupied >= threshold)) {
                "Compaction outcome is unknown; inspect it or use Stop to discard the preparation before a new request"
            }
            val scopedRequest = request.forPreparedPrefix(
                prepared,
                sources,
                resolved.connection.kind.nativeCompactionProvider(),
            )
            val valid = prepared.agentId == agent.id && prepared.actorUserId == task.actorUserId &&
                prepared.matches(conversation, sources) &&
                scopedRequest != null && preparationScope.fingerprint(conversation, agent, prepared.actorUserId,
                    promptScope, request = scopedRequest) == prepared.scopeFingerprint
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
                applyPreparedIfReady(task, conversation, agent, promptScope, request, beforeCommit)?.let { return it }
                prepared = runtimeCoordinator.proactiveCompaction(conversation.id)
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
        val window = CompactionHistoryWindow(sources)
        val prefix = window.prefix(toolPairs)
        require(prefix.isNotEmpty()) { "Context exceeds the blocking threshold but all history is inside the retained 80000-character suffix; select older messages explicitly or adjust the threshold" }
        val started = System.nanoTime()
        log.info { "COMPACTION phase=start id=${checkpointId.value} mode=blocking inputTokens=$occupied sources=${prefix.size} retained=${sources.size - prefix.size}" }
        val pins = preparationScope.pinPrompts(conversation)
        val scope = task.actorUserId?.let { preparationScope.fingerprint(conversation, agent, it, promptScope, pins) }
        val message = generateCheckpoint(
            conversation,
            agent,
            runtime,
            request.copy(systemPrompts = unpinnedPrompts + pins),
            window,
            prefix,
            checkpointId,
            pins,
            Checkpoint.Origin.GROMOZEKA_POLICY,
            nativeProvider = resolved.connection.kind.nativeCompactionProvider(),
        )
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
    ): Boolean {
        if (hasHistoryMutationWork(conversation.id, excludingActiveTaskId = task.id)) {
            val deferred = reconcileHistoryMutations(conversation.id)
                ?: runtimeCoordinator.proactiveCompaction(conversation.id)
            if (deferred?.reprepareAfterHistoryMutation == true) return false
        }
        if (conversation.externalChannel != null) {
            log.debug { "COMPACTION phase=skipped task=${task.id.value} reason=external_channel" }
            return false
        }
        val resolved = configurations.resolveAiRuntime(agent.runtimeSelection)
        val threshold = resolved.modelConfiguration.compaction.proactiveThresholdTokens ?: return false
        val actor = task.actorUserId ?: return false
        if (runtimeCoordinator.proactiveCompaction(conversation.id)?.cancellable == true) return false
        // Use the active request window to trigger preparation. Fetch archived sources only when needed.
        val inspectionWindow = if (request.messages.isEmpty()) {
            history.getContextMessagesByThread(
                conversation.currentThread,
                resolved.connection.kind.nativeCompactionProvider(),
            ).messages
        } else request.messages
        val pins = preparationScope.pinPrompts(conversation)
        val inspectionRequest = request.copy(systemPrompts = request.systemPrompts + pins, messages = textCompactions.materializeSnapshot(conversation.id, inspectionWindow))
        val estimate = AiContextMeasurement.portableInspection(resolved, inspectionRequest)?.inputTokens
            ?: AiRequestTokenEstimate.input(inspectionRequest.copy(messages = inspectionRequest.projectedMessages(resolved.connection.kind.nativeCompactionProvider())))
        if (estimate < threshold) return false
        val sources = history.compactionWindowHeaders(conversation.currentThread)
        if (sources.isEmpty() || sources.any { it.streaming }) return false
        val window = CompactionHistoryWindow(sources)
        val prefix = window.prefix(toolPairs)
        if (prefix.isEmpty()) {
            log.debug { "COMPACTION phase=skipped task=${task.id.value} reason=only_retained_suffix" }
            return false
        }
        val nativeProvider = resolved.connection.kind.nativeCompactionProvider()
        val captured = request.forCompactionPrefix(prefix, nativeProvider).copy(
            systemPrompts = request.systemPrompts + pins,
            options = request.options.copy(toolContext = request.options.toolContext +
                ("threadId" to conversation.currentThread.value)),
        )
        val signature = preparationScope.fingerprint(conversation, agent, actor, promptScope, pins, captured)
        val preparation = ProactiveCompaction(
            id = "${task.id.value}:proactive-compaction", conversationId = conversation.id,
            sourceTaskId = task.id, sourceTurnId = task.turnId, executor = executor, agentId = agent.id, actorUserId = actor,
            threadId = conversation.currentThread, capturedMessageCount = sources.size,
            capturedLastMessageId = sources.last().id, historyFingerprint = authorFingerprint(sources), scopeFingerprint = signature,
            inputTokens = estimate, sourceCount = prefix.size, retainedCount = sources.size - prefix.size, createdAt = Clock.System.now(),
        )
        return preparations.start(preparation) {
            if (preparationScope.fingerprint(conversation, agent, actor, promptScope, request = captured) != signature) {
                throw CompactionScopeChangedException()
            }
            val preparationRuntime = runtimes.getRuntime(agent.runtimeSelection, null)
            val checkpoint = generateCheckpoint(conversation, agent, preparationRuntime, captured, window, prefix,
                Conversation.Message.Id(preparation.id), pins, Checkpoint.Origin.GROMOZEKA_POLICY,
                nativeProvider = nativeProvider)
            if (preparationScope.fingerprint(conversation, agent, actor, promptScope, request = captured) != signature) {
                throw CompactionScopeChangedException()
            }
            checkpoint
        }
    }

    internal suspend fun reconcileHistoryMutations(conversationId: Conversation.Id): ProactiveCompaction? =
        preparations.cancelForHistoryMutationIfNeeded(conversationId)

    private suspend fun hasHistoryMutationWork(
        conversationId: Conversation.Id,
        excludingActiveTaskId: ConversationRuntimeTask.Id? = null,
    ): Boolean {
        val snapshot = runtimeCoordinator.schedulingSnapshot(conversationId)
        return snapshot.activeTask?.takeUnless { it.id == excludingActiveTaskId }
            ?.payload is ConversationRuntimeTask.Payload.HistoryMutation ||
            snapshot.pendingTasks.any { it.payload is ConversationRuntimeTask.Payload.HistoryMutation }
    }

    internal suspend fun generateRequestedCompact(
        conversation: Conversation,
        agent: AgentDefinition,
        window: CompactionHistoryWindow,
        prefix: List<CompactionMessageHeader>,
        checkpointId: Conversation.Message.Id,
        request: AiRuntimeRequest?,
        requestContext: Map<String, Any?>,
    ): Conversation.Message {
        val pins = preparationScope.pinPrompts(conversation)
        val nativeRequest = request?.copy(systemPrompts = request.systemPrompts + pins)
        val nativeProvider = configurations.resolveAiRuntime(agent.runtimeSelection)
            .connection.kind.nativeCompactionProvider()
        return generateCheckpoint(
            conversation = conversation,
            agent = agent,
            runtime = runtimes.getRuntime(agent.runtimeSelection, workspaceRootPath = null),
            request = nativeRequest,
            requestContext = request?.options?.toolContext.orEmpty() + requestContext + mapOf(
                "conversationId" to conversation.id.value,
                "threadId" to conversation.currentThread.value,
                "projectId" to conversation.projectId.value,
            ),
            window = window,
            prefix = prefix,
            checkpointId = checkpointId,
            pins = pins,
            origin = Checkpoint.Origin.USER_REQUESTED,
            nativeProvider = nativeProvider,
        )
    }

    private suspend fun generateCheckpoint(
        conversation: Conversation,
        agent: AgentDefinition,
        runtime: AiRuntime,
        request: AiRuntimeRequest?,
        window: CompactionHistoryWindow,
        prefix: List<CompactionMessageHeader>,
        checkpointId: Conversation.Message.Id,
        pins: List<String>,
        origin: Checkpoint.Origin,
        requestContext: Map<String, Any?> = request?.options?.toolContext.orEmpty(),
        nativeProvider: String?,
    ): Conversation.Message {
        val compactionContext = requestContext + ("aiCallDiagnosticId" to checkpointId.value)
        val continuationState = continuationStates.captureHeaders(prefix)
        val prefixRequest = if (request != null && runtime.capabilities.supportsDetachedNativeCompaction) {
            request.forCompactionPrefix(prefix, nativeProvider)
                .takeIf { it.messages.lastOrNull()?.id == prefix.last().id }
                ?.copy(options = request.options.copy(usagePurpose = "CONTEXT_COMPACTION", toolContext = compactionContext +
                    ("compactionTransportId" to checkpointId.value)))
                ?.takeIf { runtime.inspectContext(it, allowProviderCount = false).nativeCompactionAvailable }
        } else null
        log.info { "COMPACTION phase=generating id=${checkpointId.value} method=${if (prefixRequest != null) "native-prefix" else "text"} sources=${prefix.size}" }
        if (prefixRequest != null) {
            val response = runtime.compact(prefixRequest)
            check(response.outcome in setOf(AiStepOutcome.COMPLETE, AiStepOutcome.CONTINUE) && response.toolCalls.isEmpty()) {
                "Native compaction did not complete: ${response.outcome}"
            }
            val mapped = AiConversationMessageMapper.toConversationMessages(conversation.id, response,
                Conversation.Message.Author.Agent(agent.id, agent.name))
            check(mapped.size == 1 && mapped.single().content.singleOrNull() is Checkpoint) {
                "Native compact-only operation must return exactly one checkpoint and no ordinary answer"
            }
            val normalized = normalizeNative(mapped.single().copy(id = checkpointId), prefix, prefixRequest.messages.map { it.id })
            val checkpoint = normalized.content.single() as Checkpoint
            return normalized.copy(content = listOf(checkpoint.copy(
                origin = origin,
                continuationState = continuationState,
            )))
        }
        // A live-session /compact cannot compact just an older prefix while retaining its suffix.
        // Use the configured text compaction model, not a speculative mutation of that session.
        val readable = window.messages(
            history,
            conversation.currentThread,
            prefix,
            provider = null,
        )
        require(readable.isNotEmpty()) {
            "Full compaction has no portable input and native compaction is unavailable"
        }
        val generated = textCompactions.generateWithAi(conversation, readable,
            SquashType.COMPACT, agent.runtimeSelection, compactionContext, pins)
        log.info { "COMPACTION phase=generated id=${checkpointId.value} compactedChars=${generated.text.length}" }
        return Conversation.Message(
            id = checkpointId, conversationId = conversation.id, role = Conversation.Message.Role.ASSISTANT,
            author = Conversation.Message.Author.Agent(agent.id, agent.name), createdAt = Clock.System.now(),
            content = listOf(Checkpoint(
                payload = Checkpoint.Payload.ReadableSummary(generated.text), origin = origin,
                strategy = Checkpoint.Strategy.COMPACT, sourceBoundaryMessageId = prefix.last().id,
                coverage = Checkpoint.Coverage.ALL_PREVIOUS, providerScope = generated.providerScope,
                promptTemplate = generated.promptTemplate,
                continuationState = continuationState,
            )),
        )
    }

    /** Preserve the exact model-call materialization used by the ordinary runtime path. */
    private fun AiRuntimeRequest.forCompactionPrefix(
        prefix: List<CompactionMessageHeader>,
        nativeProvider: String?,
    ): AiRuntimeRequest {
        val ids = prefix.mapTo(hashSetOf()) { it.id }
        return copy(messages = ConversationContext(messages).messagesForProvider(
            nativeProvider?.let { provider ->
                Checkpoint.ProviderScope(
                    provider = provider,
                    connectionId = options.toolContext["aiConnectionId"] as? String,
                    modelConfigurationId = options.toolContext["aiModelConfigurationId"] as? String,
                    modelName = options.toolContext["modelName"] as? String,
                )
            },
            ids,
        ))
    }

    private fun AiRuntimeRequest.forPreparedPrefix(
        prepared: ProactiveCompaction,
        sources: List<CompactionMessageHeader>,
        nativeProvider: String?,
    ): AiRuntimeRequest? {
        if (prepared.sourceCount > sources.size) return null
        val prefix = sources.take(prepared.sourceCount)
        return forCompactionPrefix(prefix, nativeProvider)
            .takeIf { it.messages.lastOrNull()?.id == prefix.lastOrNull()?.id }
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
        val sources = history.compactionWindowHeaders(conversation.currentThread)
        val normalized = normalizeNative(message, sources, capturedInputIds)
        currentCoroutineContext().ensureActive()
        beforeCommit()
        val updated = committer.commit(conversation.currentThread, normalized)
        publishHistoryChange(task)
        return updated
    }

    private fun normalizeNative(message: Conversation.Message, sources: List<CompactionMessageHeader>,
        capturedInputIds: List<Conversation.Message.Id>): Conversation.Message {
        val checkpoint = message.content.single() as Checkpoint
        require(checkpoint.payload is Checkpoint.Payload.OpaqueProviderState && checkpoint.coversAllPrevious) {
            "Native compaction must identify its complete input boundary"
        }
        require(checkpoint.sourceMessageIds.isEmpty()) { "Native compaction must use a source boundary" }
        require(capturedInputIds.isNotEmpty() && capturedInputIds.distinct().size == capturedInputIds.size) {
            "Native compaction captured an invalid input"
        }
        val boundaryId = checkpoint.sourceBoundaryMessageId ?: capturedInputIds.last()
        val inputBoundary = capturedInputIds.indexOf(boundaryId)
        val positions = sources.mapIndexed { index, source -> source.id to index }.toMap()
        val sourcePositions = capturedInputIds.take(inputBoundary + 1).map { positions[it] ?: -1 }
        require(inputBoundary >= 0 && sourcePositions.all { it >= 0 } &&
            sourcePositions.zipWithNext().all { (left, right) -> left < right }) {
            "Native compaction input is not a valid captured history prefix"
        }
        return message.copy(content = listOf(checkpoint.copy(
            sourceMessageIds = emptyList(),
            sourceBoundaryMessageId = sources[sourcePositions.last()].id,
            continuationState = continuationStates.captureHeaders(sources.take(sourcePositions.last() + 1)),
        )))
    }

    private suspend fun publishHistoryChange(task: ConversationRuntimeTask) {
        val event = ConversationRuntimeEvent.HistoryChanged(task.conversationId, task.id, ConversationHistoryMutationKind.COMPACT)
        val entry = runtimeCoordinator.recordEvent(event)
        events.publish(event.copy(cursorSequence = entry.sequence))
    }
}

private fun ProactiveCompaction.matches(conversation: Conversation, headers: List<CompactionMessageHeader>): Boolean =
    threadId == conversation.currentThread && capturedLastMessageId != null &&
        headers.getOrNull(capturedMessageCount - 1)?.id == capturedLastMessageId &&
        authorFingerprint(headers.subList(0, capturedMessageCount)) == historyFingerprint
