package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

@Service
class MemoryMessageRoutingApplicationService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val store: MemoryStore,
    private val threadMessageRepository: ThreadMessageRepository,
    private val writeTraceSinks: List<MemoryWriteTraceSink>,
) {
    private val log = KLoggers.logger(this)
    private val sourceMapper = ConversationMessageMemorySourceMapper()
    private val failFastOnError: Boolean
        get() = java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")

    suspend fun routeMessage(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        message: Conversation.Message,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        threadContextMessages: List<Conversation.Message>? = null,
    ): DirectStructuredMemoryWriteResult? {
        if (!message.isMemoryRouteableTarget()) {
            log.info {
                "Memory router skipped: conversation=${conversationId.value} message=${message.id.value} " +
                    "role=${message.role} reason=system_or_error_message"
            }
            return null
        }

        val namespace = MemoryNamespace("project:${project.id.value}")
        val source = sourceMapper.toChatTurn(
            namespace = namespace,
            conversationId = conversationId,
            threadId = threadId,
            message = message,
        )

        if (source == null) {
            val skipped = "Memory router skipped: conversation=${conversationId.value} message=${message.id.value} role=${message.role} reason=blank_or_non_memory_content"
            log.info { skipped }
            return null
        }

        val threadContext = buildThreadContext(
            conversationId = conversationId,
            threadId = threadId,
            targetMessage = message,
            threadContextMessages = threadContextMessages,
        )

        log.info {
            "Memory router trigger: conversation=${conversationId.value} thread=${threadId.value} message=${message.id.value} " +
                "role=${message.role} source=${source.id.value} sourceChars=${source.contentText.length} " +
                "threadContextMessages=${threadContext.messages.size} targetIndex=${threadContext.messages.indexOfFirst { it.id == message.id }} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        return routeSourceInternal(
            namespace = namespace,
            source = source,
            threadContext = threadContext,
            agent = agent,
            project = project,
            runtimeSystemPrompts = runtimeSystemPrompts,
            runtimeTools = runtimeTools,
            logContext = "conversation=${conversationId.value} message=${message.id.value} role=${message.role}",
            traceContext = MemoryWriteTraceContext(
                conversationId = conversationId,
                threadId = threadId,
                targetMessageId = message.id,
            ),
        )
    }

    suspend fun routeSource(
        namespace: MemoryNamespace,
        source: MemorySource,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
    ): DirectStructuredMemoryWriteResult? {
        log.info {
            "Memory router trigger: namespace=${namespace.value} source=${source.id.value} " +
                "sourceType=${source::class.simpleName} sourceChars=${source.contentText.length} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        return routeSourceInternal(
            namespace = namespace,
            source = source,
            threadContext = null,
            agent = agent,
            project = project,
            runtimeSystemPrompts = runtimeSystemPrompts,
            runtimeTools = runtimeTools,
            logContext = "namespace=${namespace.value} source=${source.id.value} sourceType=${source::class.simpleName}",
            traceContext = null,
        )
    }

    private suspend fun routeSourceInternal(
        namespace: MemoryNamespace,
        source: MemorySource,
        threadContext: MemoryThreadContext?,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        logContext: String,
        traceContext: MemoryWriteTraceContext?,
    ): DirectStructuredMemoryWriteResult? {
        val runtime = aiRuntimeProvider.getRuntime(
            provider = AIProvider.valueOf(agent.aiProvider),
            modelName = agent.modelName,
            projectPath = project.path,
        )

        val pipeline = DirectStructuredMemoryWritePipeline(
            store = store,
            router = LlmMemoryWriteRouter(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            retrievalPlanner = LlmMemoryWriteRetrievalPlanner(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            entityCanonicalizer = LlmMemoryEntityCanonicalizer(
                runtime = runtime,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            noteConstructor = LlmMemoryNoteConstructor(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            noteReconciler = LlmMemoryNoteReconciler(
                runtime = runtime,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            claimExtractor = LlmMemoryClaimExtractor(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            claimReconciler = LlmMemoryClaimReconciler(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            taskUpdater = LlmMemoryTaskUpdater(
                runtime = runtime,
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                UuidMemoryIdFactory("hot-path")
            ),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            forgetPipeline = ExplicitMemoryForgetPipeline(
                store = store,
                planner = LlmMemoryForgetPlanner(
                    runtime = runtime,
                    runtimeSystemPrompts = runtimeSystemPrompts,
                    runtimeTools = runtimeTools,
                ),
                idFactory = UuidMemoryIdFactory("hot-path-forget"),
                profileUpdater = ProjectionMemoryProfileUpdater(store),
            ),
        )

        return runCatching {
            pipeline.write(
                DirectStructuredMemoryWriteRequest(
                    namespace = namespace,
                    source = source,
                    threadContext = threadContext,
                )
            )
        }.onSuccess { result ->
            log.info {
                "Memory router routed: $logContext decision=${result.routeDecision.decision.name} " +
                    "types=${result.routeDecision.memoryTypes.joinToString { it.name }} " +
                    "salience=${result.routeDecision.salience} reason=${result.routeDecision.reason} " +
                    "retrieval=${result.retrievalPlan?.describeForLog() ?: "none"} " +
                    "retrievedHits=${result.retrievedHits.size} hitBreakdown=${result.retrievedHits.breakdownForLog()} " +
                    "entityOps=${result.entityOps.size} entityActions=${result.entityOps.entityActionsForLog()} " +
                    "noteCandidates=${result.noteCandidates.size} noteOps=${result.noteOps.size} " +
                    "claimCandidates=${result.claimCandidates.size} claimPredicates=${result.claimCandidates.claimPredicatesForLog()} " +
                    "taskOps=${result.taskOps.size}"
            }
            traceContext?.let { trace ->
                writeTraceSinks.forEach { sink ->
                    runCatching {
                        sink.onMemoryWrite(
                            MemoryWriteTraceEvent(
                                namespace = namespace,
                                conversationId = trace.conversationId,
                                threadId = trace.threadId,
                                targetMessageId = trace.targetMessageId,
                                result = result,
                            )
                        )
                    }.onFailure { error ->
                        log.warn(error) {
                            "Memory write trace sink failed: conversation=${trace.conversationId.value} " +
                                "target=${trace.targetMessageId.value} sink=${sink::class.simpleName} error=${error.message}"
                        }
                    }
                }
            }
        }.onFailure { error ->
            val failed = "Memory router failed: $logContext error=${error.message}"
            log.warn(error) { failed }
            if (failFastOnError) {
                throw error
            }
        }.getOrNull()
    }

    private data class MemoryWriteTraceContext(
        val conversationId: Conversation.Id,
        val threadId: Conversation.Thread.Id,
        val targetMessageId: Conversation.Message.Id,
    )

    private suspend fun buildThreadContext(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        targetMessage: Conversation.Message,
        threadContextMessages: List<Conversation.Message>? = null,
    ): MemoryThreadContext {
        val threadMessages = (threadContextMessages ?: threadMessageRepository.getMessagesByThread(threadId))
            .filterNot { it.isSyntheticMemoryRuntimeMessage() }
            .filter { it.isMemoryStageContextMessage() }
        val targetIndex = threadMessages.indexOfFirst { it.id == targetMessage.id }
        val contextMessages = if (targetIndex >= 0) {
            threadMessages.take(targetIndex + 1)
        } else {
            log.warn {
                "Memory thread context target missing in thread repository: conversation=${conversationId.value} " +
                    "thread=${threadId.value} target=${targetMessage.id.value}; appending target to loaded context"
            }
            threadMessages + targetMessage
        }

        return MemoryThreadContext(
            conversationId = conversationId,
            threadId = threadId,
            targetMessageId = targetMessage.id,
            messages = contextMessages,
        )
    }

    private fun Conversation.Message.isSyntheticMemoryRuntimeMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"

    private fun Conversation.Message.isMemoryRouteableTarget(): Boolean =
        role != Conversation.Message.Role.SYSTEM && error == null

    private fun Conversation.Message.isMemoryStageContextMessage(): Boolean =
        role != Conversation.Message.Role.SYSTEM && error == null
}

private fun MemoryWriteRetrievalPlan.describeForLog(): String =
    "need=$needRetrieval types=${memoryTypes.joinToString { it.name }} " +
        "entities=${entityQueries.joinToString("|")} texts=${textQueries.joinToString("|")} " +
        "predicates=${predicateHints.joinToString("|")} budget=$retrievalBudget"

private fun List<MemoryStore.SearchHit>.breakdownForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { hit ->
        when (hit) {
            is MemoryStore.SearchHit.SourceHit -> "source"
            is MemoryStore.SearchHit.EntityHit -> "entity"
            is MemoryStore.SearchHit.ClaimHit -> "claim"
            is MemoryStore.SearchHit.NoteHit -> "note"
            is MemoryStore.SearchHit.TaskHit -> "task"
            is MemoryStore.SearchHit.ProfileHit -> "profile"
            is MemoryStore.SearchHit.EpisodeHit -> "episode"
            is MemoryStore.SearchHit.RunHit -> "run"
        }
    }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp>.entityActionsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<com.gromozeka.domain.model.memory.MemoryClaimCandidate>.claimPredicatesForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.predicate }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}
