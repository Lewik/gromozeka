package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import java.security.MessageDigest
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Service

@Service
class MemoryMessageRoutingApplicationService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val settingsProvider: SettingsProvider,
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
        forceMemoryWrite: Boolean = false,
        namespaceOverride: MemoryNamespace? = null,
    ): DirectStructuredMemoryWriteResult? {
        if (!message.isMemoryRouteableTarget()) {
            log.info {
                "Memory router skipped: conversation=${conversationId.value} message=${message.id.value} " +
                    "role=${message.role} reason=system_or_error_message"
            }
            return null
        }

        val namespace = namespaceOverride
            ?: settingsProvider.userProfile.memorySettings.defaultMemoryNamespace()
            ?: project.defaultMemoryNamespace()
        val baseSource = sourceMapper.toChatTurn(
            namespace = namespace,
            conversationId = conversationId,
            threadId = threadId,
            message = message,
        )
        val source = baseSource?.let {
            if (forceMemoryWrite) {
                it.withForceMemoryWrite() as MemorySource.ChatTurn
            } else {
                it
            }
        }

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

        MarkdownDocumentImportDetector.detect(source.contentText)?.let { document ->
            return routeImportedMarkdownDocument(
                namespace = namespace,
                parentSource = source,
                document = document,
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

        return routeSourceInternal(
            namespace = namespace,
            source = source,
            threadContext = threadContext,
            parentRunId = null,
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

    private suspend fun routeImportedMarkdownDocument(
        namespace: MemoryNamespace,
        parentSource: MemorySource.ChatTurn,
        document: MarkdownDocumentImport,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        logContext: String,
        traceContext: MemoryWriteTraceContext,
    ): DirectStructuredMemoryWriteResult {
        val now = Clock.System.now()
        val documentHash = parentSource.contentHash
        val sections = MarkdownDocumentSlicer.slice(document.markdown)
        require(sections.isNotEmpty()) { "Imported markdown document has no non-blank sections." }

        val parentRun = MemoryRun(
            id = MemoryRun.Id("document-ingest:run:${com.gromozeka.shared.uuid.uuid7()}"),
            namespace = namespace,
            runType = MemoryRun.Type.DOCUMENT_INGEST,
            triggerMode = MemoryRun.TriggerMode.HOT_PATH,
            summary = "Pasted markdown document ingest: ${sections.size} sections",
            sourceIds = listOf(parentSource.id),
            progress = MemoryRun.Progress(
                totalUnits = sections.size,
                completedUnits = sections.size,
            ),
            inputHash = documentHash,
            output = buildJsonObject {
                put("document_type", MemoryDocumentType.MARKDOWN.name)
                put("input_kind", "PASTED_CHAT_DOCUMENT")
                put("title", document.title.orEmpty())
                put("source_ref", document.sourceRef)
                put("ingested_at", now.toString())
                put("parent_source_id", parentSource.id.value)
                put("sections_total", sections.size)
            },
            metadata = buildJsonObject {
                put("memoryToolOrigin", "pasted_document")
                put("sourceKind", "document")
                put("documentType", MemoryDocumentType.MARKDOWN.name)
                put("sourceRef", document.sourceRef)
                put("importedAt", now.toString())
                document.title?.let { put("title", it) }
            },
            createdAt = now,
            startedAt = now,
            completedAt = now,
        )

        store.apply(MemoryUpdateBatch(sources = listOf(parentSource), runs = listOf(parentRun)))

        log.info {
            "Memory pasted document routing: $logContext parentSource=${parentSource.id.value} " +
                "parentRun=${parentRun.id.value} sections=${sections.size} title=${document.title.orEmpty()} sourceRef=${document.sourceRef}"
        }

        val processedSections = sections.map { section ->
            MemoryDocumentAdaptiveIngest.processSection(section) { effectiveSection ->
                routeSourceInternal(
                    namespace = namespace,
                    source = parentSource.toDocumentSectionSource(
                        section = effectiveSection,
                        document = document,
                        documentHash = documentHash,
                        parentRun = parentRun,
                    ),
                    threadContext = null,
                    parentRunId = parentRun.id,
                    agent = agent,
                    project = project,
                    runtimeSystemPrompts = runtimeSystemPrompts,
                    runtimeTools = runtimeTools,
                    logContext = "$logContext documentSection=${effectiveSection.index}/${sections.size}",
                    traceContext = null,
                    throwOnError = true,
                ) ?: throw IllegalStateException("Section routing returned no result.")
            }
        }

        val sectionResults = processedSections.flatMap { it.results }
        val sectionFailures = processedSections.flatMap { it.failedSections }
        if (sectionFailures.isNotEmpty()) {
            log.warn {
                "Memory pasted document partial failures: $logContext parentRun=${parentRun.id.value} " +
                    "failures=${sectionFailures.size} first=${sectionFailures.first().message}"
            }
        }

        val completedResults = sectionResults
        val aggregate = completedResults.toDocumentAggregateResult(
            parentSource = parentSource,
            parentRun = parentRun,
            sections = sections,
        )

        writeTraceSinks.forEach { sink ->
            runCatching {
                sink.onMemoryWrite(
                    MemoryWriteTraceEvent(
                        namespace = namespace,
                        conversationId = traceContext.conversationId,
                        threadId = traceContext.threadId,
                        targetMessageId = traceContext.targetMessageId,
                        result = aggregate,
                    )
                )
            }.onFailure { error ->
                log.warn(error) {
                    "Memory write trace sink failed: conversation=${traceContext.conversationId.value} " +
                        "target=${traceContext.targetMessageId.value} sink=${sink::class.simpleName} error=${error.message}"
                }
            }
        }

        log.info {
            "Memory pasted document routed: $logContext parentRun=${parentRun.id.value} " +
                "sections=${sections.size} completed=${completedResults.size} " +
                "adaptiveSplits=${processedSections.sumOf { it.splitCount }} failures=${sectionFailures.size} " +
                "notes=${aggregate.memoryBatch.notes.size} claims=${aggregate.memoryBatch.claims.size} tasks=${aggregate.memoryBatch.tasks.size}"
        }

        return aggregate
    }

    suspend fun routeSource(
        namespace: MemoryNamespace,
        source: MemorySource,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        parentRunId: MemoryRun.Id? = null,
        throwOnError: Boolean = false,
    ): DirectStructuredMemoryWriteResult? {
        log.info {
            "Memory router trigger: namespace=${namespace.value} source=${source.id.value} " +
                "sourceType=${source::class.simpleName} sourceChars=${source.contentText.length} " +
                "parentRun=${parentRunId?.value ?: "none"} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        return routeSourceInternal(
            namespace = namespace,
            source = source,
            threadContext = null,
            parentRunId = parentRunId,
            agent = agent,
            project = project,
            runtimeSystemPrompts = runtimeSystemPrompts,
            runtimeTools = runtimeTools,
            logContext = "namespace=${namespace.value} source=${source.id.value} sourceType=${source::class.simpleName}",
            traceContext = null,
            throwOnError = throwOnError,
        )
    }

    private suspend fun routeSourceInternal(
        namespace: MemoryNamespace,
        source: MemorySource,
        threadContext: MemoryThreadContext?,
        parentRunId: MemoryRun.Id?,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        logContext: String,
        traceContext: MemoryWriteTraceContext?,
        throwOnError: Boolean = false,
    ): DirectStructuredMemoryWriteResult? {
        val runtimes = MemoryWriteStageRuntimes(project)
        val focusedThreadContext = threadContext?.let {
            MemoryThreadContextCompactor(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_CONTEXT_COMPACTOR),
                preCompactThresholdTokens = MemoryContextWindowPolicy.writePreCompactThresholdTokens(settingsProvider.userProfile.aiSettings),
            ).compactIfNeeded(
                context = it,
                targetSourceLabel = source.id.value,
                logContext = logContext,
            )
        }

        val pipeline = DirectStructuredMemoryWritePipeline(
            store = store,
            router = LlmMemoryWriteRouter(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            retrievalPlanner = LlmMemoryWriteRetrievalPlanner(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_RETRIEVAL_PLANNER),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            entityCanonicalizer = LlmMemoryEntityCanonicalizer(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_ENTITY_CANONICALIZER),
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            noteConstructor = LlmMemoryNoteConstructor(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_CONSTRUCTOR),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            noteReconciler = LlmMemoryNoteReconciler(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_RECONCILER),
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            claimExtractor = LlmMemoryClaimExtractor(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            claimReconciler = LlmMemoryClaimReconciler(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_RECONCILER),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            taskUpdater = LlmMemoryTaskUpdater(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_TASK_UPDATER),
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
                    runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_FORGET_PLANNER),
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
                    threadContext = focusedThreadContext,
                    parentRunId = parentRunId,
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
            if (throwOnError || failFastOnError) {
                throw error
            }
        }.getOrNull()
    }

    private inner class MemoryWriteStageRuntimes(
        private val project: Project,
    ) {
        private val runtimes = mutableMapOf<AiRuntimeAssignment.Purpose, AiRuntime>()

        fun runtimeFor(purpose: AiRuntimeAssignment.Purpose): AiRuntime =
            runtimes.getOrPut(purpose) {
                aiRuntimeProvider.getRuntime(
                    selection = settingsProvider.runtimeSelectionFor(purpose),
                    projectPath = project.path,
                )
            }
    }

    private fun MemorySource.ChatTurn.toDocumentSectionSource(
        section: MarkdownDocumentSection,
        document: MarkdownDocumentImport,
        documentHash: String,
        parentRun: MemoryRun,
    ): MemorySource.ExternalRecord {
        val now = Clock.System.now()
        val sectionHash = "$documentHash:${section.index}:${section.text.sha256()}".sha256()
        return MemorySource.ExternalRecord(
            id = MemorySource.Id("external:document-section:${sectionHash.take(32)}"),
            namespace = namespace,
            recordRef = "${document.sourceRef}#section:${section.index}",
            authorLabel = "pasted document section",
            contentText = section.toMemorySourceText(
                title = document.title,
                sourceRef = document.sourceRef,
                importedAt = now,
            ),
            contentPayload = buildJsonObject {
                put("memoryToolOrigin", "pasted_document_section")
                put("sourceKind", "document")
                put("parentSourceId", id.value)
                put("parentRunId", parentRun.id.value)
                put("documentHash", documentHash)
                put("documentType", MemoryDocumentType.MARKDOWN.name)
                put("sourceRef", document.sourceRef)
                put("importedAt", now.toString())
                document.title?.let { put("title", it) }
                put("sectionIndex", section.index)
                put("heading", section.headingLabel)
                put("startLine", section.startLine)
                put("endLine", section.endLine)
                putJsonArray("headingPath") {
                    section.headingPath.forEach { add(JsonPrimitive(it)) }
                }
            },
            contentHash = sectionHash,
            observedAt = observedAt,
            createdAt = now,
            retentionClass = MemorySource.RetentionClass.IMPORTED,
        )
    }

    private fun List<DirectStructuredMemoryWriteResult>.toDocumentAggregateResult(
        parentSource: MemorySource,
        parentRun: MemoryRun,
        sections: List<MarkdownDocumentSection>,
    ): DirectStructuredMemoryWriteResult {
        val aggregateBatch = fold(MemoryUpdateBatch(runs = listOf(parentRun))) { acc, result ->
            acc + result.memoryBatch
        }
        return DirectStructuredMemoryWriteResult(
            sourceBatch = fold(MemoryUpdateBatch(sources = listOf(parentSource))) { acc, result ->
                acc + result.sourceBatch
            },
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.NOTE_WRITE,
                memoryTypes = setOf(MemorySemanticType.NOTE, MemorySemanticType.SOURCE),
                salience = 1.0,
                sourcePolicy = MemorySourceUsagePolicy.STANDARD,
                sourceSearchText = parentSource.searchText,
                reason = "Pasted markdown document was ingested through ${sections.size} structure-aware sections.",
            ),
            predicateCatalog = flatMap { it.predicateCatalog }.distinctBy { it.predicate },
            retrievalPlan = firstNotNullOfOrNull { it.retrievalPlan },
            retrievedHits = flatMap { it.retrievedHits },
            entityOps = flatMap { it.entityOps },
            noteCandidates = flatMap { it.noteCandidates },
            rawNoteOps = flatMap { it.rawNoteOps },
            noteOps = flatMap { it.noteOps },
            claimCandidates = flatMap { it.claimCandidates },
            rawClaimOps = flatMap { it.rawClaimOps },
            claimOps = flatMap { it.claimOps },
            rawTaskOps = flatMap { it.rawTaskOps },
            taskOps = flatMap { it.taskOps },
            memoryBatch = aggregateBatch,
        )
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

private operator fun MemoryUpdateBatch.plus(other: MemoryUpdateBatch): MemoryUpdateBatch =
    MemoryUpdateBatch(
        predicateDefinitions = predicateDefinitions + other.predicateDefinitions,
        sources = sources + other.sources,
        runs = runs + other.runs,
        entities = entities + other.entities,
        claims = claims + other.claims,
        notes = notes + other.notes,
        tasks = tasks + other.tasks,
        profiles = profiles + other.profiles,
        episodes = episodes + other.episodes,
    )

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
