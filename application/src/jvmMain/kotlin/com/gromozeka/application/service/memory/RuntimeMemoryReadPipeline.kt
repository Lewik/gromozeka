package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.RuntimeMemoryReadService
import com.gromozeka.domain.model.Conversation
import klog.KLoggers
import kotlinx.datetime.Clock

class RuntimeMemoryReadPipeline(
    private val store: MemoryStore,
    private val planner: MemoryReadPlanner,
    private val selector: MemoryReadSelector = PassthroughMemoryReadSelector,
    private val composer: RuntimeMemoryPromptComposer = RuntimeMemoryPromptComposer,
    private val embeddingIndexer: MemoryEmbeddingIndexer = NoOpMemoryEmbeddingIndexer,
) : RuntimeMemoryReadService {
    private val log = KLoggers.logger(this)

    override suspend fun read(request: MemoryReadRequest): MemoryReadResult {
        val plan = planner.plan(request)
        val targetText = request.targetQueryText()
        val searchSteps = mutableListOf<MemoryReadTrace.SearchStep>()
        log.info {
            "Memory read plan: namespace=${request.namespace.value} need=${plan.needMemory} " +
                "mode=${plan.answerMode.name} core=${plan.coreBlocks.joinToString { it.name }} " +
                "budget=${plan.retrievalBudget} requests=${plan.retrievalRequests.size} " +
                "retrieval=${plan.retrievalRequests.joinToString("|") { "${it.memoryType.name}:${it.topK}:${it.query.oneLineForRuntimeMemoryLog(120)}" }}"
        }

        if (!plan.needMemory) {
            return MemoryReadResult(
                plan = plan,
                retrievedHits = emptyList(),
                runtimePrompt = null,
                trace = MemoryReadTrace(targetText = targetText),
            )
        }

        val retrieved = retrieve(request, plan, searchSteps)
        val hits = retrieved.hits
        val prompt = composer.compose(request, plan, hits)
        val refs = hits.map { it.toItemRef() }
        val trace = MemoryReadTrace(
            targetText = targetText,
            searchSteps = searchSteps,
            selectorDecisions = retrieved.selectorDecisions.toTraceSelectorDecisions(retrieved.selectorCandidateHits),
            selectedHits = hits.toTraceHits(),
            sourceSafety = retrieved.sourceSafety.toTraceSourceSafety(),
            injectedPrompt = prompt?.let {
                MemoryReadTrace.InjectedPrompt(
                    chars = it.length,
                    preview = it.oneLineForRuntimeMemoryLog(500),
                )
            },
        )

        if (refs.isNotEmpty()) {
            store.touchReferences(refs, Clock.System.now())
        }

        log.info {
            "Memory read prompt composed: namespace=${request.namespace.value} hits=${hits.size} " +
                "hitBreakdown=${hits.breakdownForRuntimeMemoryLog()} promptChars=${prompt?.length ?: 0} " +
                "selected=${trace.selectedHits.summaryForRuntimeMemoryTraceLog()}"
        }

        return MemoryReadResult(
            plan = plan,
            retrievedHits = hits,
            runtimePrompt = prompt,
            trace = trace,
        )
    }

    private suspend fun retrieve(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
        searchSteps: MutableList<MemoryReadTrace.SearchStep>,
    ): RuntimeMemoryRetrievedHits {
        val snapshot = store.loadNamespaceSnapshot(request.namespace)
        val targetEntities = snapshot.resolveTargetEntitiesForRead(request, plan)
        val queryEmbeddings = mutableMapOf<String, MemoryStore.SearchEmbedding?>()
        val hits = mutableListOf<MemoryStore.SearchHit>()
        hits += targetEntities.hits
        if (targetEntities.hits.isNotEmpty()) {
            searchSteps += MemoryReadTrace.SearchStep(
                stage = "target_entities",
                query = request.entityResolutionText(plan),
                scope = MemoryStore.SearchScope.ENTITIES.name,
                requestedLimit = 4,
                rawCount = snapshot.entities.size,
                candidateCount = targetEntities.hits.size,
                selectedCount = targetEntities.hits.size,
                rawTopHits = targetEntities.hits.toTraceHits(limit = 5),
                selectedTopHits = targetEntities.hits.toTraceHits(limit = 5),
            )
            log.info {
                "Memory read target entities: namespace=${request.namespace.value} " +
                    "query=${request.entityResolutionText(plan).oneLineForRuntimeMemoryLog(160)} " +
                    "entities=${targetEntities.hits.summaryForRuntimeMemoryLog()}"
            }
        }

        plan.coreBlocks.forEach { coreBlock ->
            when (coreBlock) {
                MemoryReadPlan.CoreBlock.PROFILE -> {
                    val profileHits = store.search(
                        MemoryStore.SearchRequest(
                            query = request.targetQueryText(),
                            namespace = request.namespace,
                            scopes = setOf(MemoryStore.SearchScope.PROFILES),
                            filters = MemoryStore.SearchFilters()
                                .withTargetEntityIds(MemorySemanticType.PROFILE, targetEntities.filterEntityIds),
                            embedding = queryEmbedding(request.targetQueryText(), queryEmbeddings),
                            limit = plan.retrievalBudget.profilesLimit(),
                        )
                    )
                    hits += profileHits
                    searchSteps += MemoryReadTrace.SearchStep(
                        stage = "core:${coreBlock.name}",
                        query = request.targetQueryText(),
                        scope = MemoryStore.SearchScope.PROFILES.name,
                        requestedLimit = plan.retrievalBudget.profilesLimit(),
                        rawCount = profileHits.size,
                        candidateCount = profileHits.size,
                        selectedCount = profileHits.size,
                        rawTopHits = profileHits.toTraceHits(limit = 5),
                        selectedTopHits = profileHits.toTraceHits(limit = 5),
                    )
                    log.info {
                        "Memory read core search: namespace=${request.namespace.value} core=${coreBlock.name} " +
                            "query=${request.targetQueryText().oneLineForRuntimeMemoryLog(120)} hits=${profileHits.size} " +
                            "hitBreakdown=${profileHits.breakdownForRuntimeMemoryLog()} top=${profileHits.summaryForRuntimeMemoryLog()}"
                    }
                }

                MemoryReadPlan.CoreBlock.TASKS -> {
                    val taskHits = store.search(
                        MemoryStore.SearchRequest(
                            query = request.targetQueryText(),
                            namespace = request.namespace,
                            scopes = setOf(MemoryStore.SearchScope.TASKS),
                            filters = MemoryStore.SearchFilters(
                                taskStatuses = setOf(
                                    MemoryTask.Status.OPEN,
                                    MemoryTask.Status.IN_PROGRESS,
                                    MemoryTask.Status.BLOCKED,
                                ),
                            ).withTargetEntityIds(MemorySemanticType.TASK, targetEntities.filterEntityIds),
                            embedding = queryEmbedding(request.targetQueryText(), queryEmbeddings),
                            limit = plan.retrievalBudget.tasksLimit(default = 2),
                        )
                    )
                    hits += taskHits
                    searchSteps += MemoryReadTrace.SearchStep(
                        stage = "core:${coreBlock.name}",
                        query = request.targetQueryText(),
                        scope = MemoryStore.SearchScope.TASKS.name,
                        requestedLimit = plan.retrievalBudget.tasksLimit(default = 2),
                        rawCount = taskHits.size,
                        candidateCount = taskHits.size,
                        selectedCount = taskHits.size,
                        rawTopHits = taskHits.toTraceHits(limit = 5),
                        selectedTopHits = taskHits.toTraceHits(limit = 5),
                    )
                    log.info {
                        "Memory read core search: namespace=${request.namespace.value} core=${coreBlock.name} " +
                            "query=${request.targetQueryText().oneLineForRuntimeMemoryLog(120)} hits=${taskHits.size} " +
                            "hitBreakdown=${taskHits.breakdownForRuntimeMemoryLog()} top=${taskHits.summaryForRuntimeMemoryLog()}"
                    }
                }

                MemoryReadPlan.CoreBlock.SESSION_SUMMARY -> Unit
            }
        }

        plan.retrievalRequests.forEach { retrievalRequest ->
            val scope = retrievalRequest.memoryType.toSearchScope() ?: MemoryStore.SearchScope.ALL
            val searchQuery = retrievalRequest.searchQuery(request, plan)
            val limit = retrievalRequest.topK.takeIf { it > 0 }
                ?: plan.retrievalBudget.limitFor(retrievalRequest.memoryType)
                ?: 4
            val resultLimit = limit.coerceIn(1, 12)
            val searchLimit = scope.selectorCandidateSearchLimit(resultLimit, request)

            val rawRequestHits = store.search(
                MemoryStore.SearchRequest(
                    query = searchQuery,
                    namespace = request.namespace,
                    scopes = setOf(scope),
                    filters = retrievalRequest.memoryType.defaultFilters()
                        .withTargetEntityIds(retrievalRequest.memoryType, targetEntities.filterEntityIds),
                    embedding = queryEmbedding(searchQuery, queryEmbeddings),
                    limit = searchLimit,
                )
            )
            val currentThreadFilteredHits = rawRequestHits.excludeCurrentThreadSources(request)
            val requestSourceSelection = currentThreadFilteredHits.applySourceRetrievalPolicyFor(retrievalRequest.memoryType)
            val requestHits = requestSourceSelection.hits
                .prioritizeForReadRequest(retrievalRequest)
            hits += requestHits
            searchSteps += MemoryReadTrace.SearchStep(
                stage = "retrieval:${retrievalRequest.memoryType.name}",
                query = searchQuery,
                scope = scope.name,
                requestedLimit = resultLimit,
                rawCount = rawRequestHits.size,
                candidateCount = requestSourceSelection.hits.size,
                selectedCount = requestHits.size,
                rawTopHits = rawRequestHits.toTraceHits(limit = 5),
                selectedTopHits = requestHits.toTraceHits(limit = 5),
            )
            log.info {
                "Memory read retrieval search: namespace=${request.namespace.value} type=${retrievalRequest.memoryType.name} " +
                    "scope=${scope.name} topK=$resultLimit searchLimit=$searchLimit query=${searchQuery.oneLineForRuntimeMemoryLog(120)} " +
                    "rawHits=${rawRequestHits.size} hits=${requestHits.size} hitBreakdown=${requestHits.breakdownForRuntimeMemoryLog()} " +
                    "currentThreadSourcesDropped=${rawRequestHits.countCurrentThreadSources(request)} " +
                    "sourcePolicy=${requestSourceSelection.summaryForLog()} top=${requestHits.summaryForRuntimeMemoryLog()}"
            }
        }

        if (plan.shouldTrySourceFallback(hits)) {
            val sourceFallbackQuery = request.sourceFallbackSearchQuery(plan)
            val sourceFallbackLimit = (plan.retrievalBudget.sources.takeIf { it > 0 } ?: 3).coerceIn(1, 8)
            val sourceFallbackSearchLimit = (sourceFallbackLimit + request.threadContext.messages.size + 4).coerceAtMost(50)
            val rawSourceFallbackHits = store.search(
                MemoryStore.SearchRequest(
                    query = sourceFallbackQuery,
                    namespace = request.namespace,
                    scopes = setOf(MemoryStore.SearchScope.SOURCES),
                    filters = MemoryStore.SearchFilters(),
                    embedding = queryEmbedding(sourceFallbackQuery, queryEmbeddings),
                    limit = sourceFallbackSearchLimit,
                )
            )
            val sourceFallbackSelection = rawSourceFallbackHits
                .excludeCurrentThreadSources(request)
                .applySourceRetrievalPolicyFor(MemorySemanticType.SOURCE)
            val sourceFallbackHits = sourceFallbackSelection.hits.take(sourceFallbackLimit)
            hits += sourceFallbackHits
            searchSteps += MemoryReadTrace.SearchStep(
                stage = "fallback:${MemorySemanticType.SOURCE.name}",
                query = sourceFallbackQuery,
                scope = MemoryStore.SearchScope.SOURCES.name,
                requestedLimit = sourceFallbackLimit,
                rawCount = rawSourceFallbackHits.size,
                candidateCount = sourceFallbackSelection.hits.size,
                selectedCount = sourceFallbackHits.size,
                rawTopHits = rawSourceFallbackHits.toTraceHits(limit = 5),
                selectedTopHits = sourceFallbackHits.toTraceHits(limit = 5),
            )
            log.info {
                "Memory read source fallback search: namespace=${request.namespace.value} " +
                    "topK=$sourceFallbackLimit searchLimit=$sourceFallbackSearchLimit " +
                    "query=${sourceFallbackQuery.oneLineForRuntimeMemoryLog(160)} " +
                    "rawHits=${rawSourceFallbackHits.size} hits=${sourceFallbackHits.size} " +
                    "currentThreadSourcesDropped=${rawSourceFallbackHits.countCurrentThreadSources(request)} " +
                    "sourcePolicy=${sourceFallbackSelection.summaryForLog()} top=${sourceFallbackHits.summaryForRuntimeMemoryLog()}"
            }
        }

        val distinctHits = hits
            .distinctBy { it.toItemRef() }
            .excludeCurrentThreadSources(request)
            .filterNot { it.isEmptyProfileHit() }
        val sourceSelection = MemorySourceRetrievalPolicy.apply(
            hits = distinctHits,
            useCase = plan.sourceRetrievalUseCase(),
        )
        val activeTypedRefsBeforeSourceSupport = sourceSelection.hits.currentTruthBearingTypedRefs()
        val sourceTypedSupportHits = sourceSelection.hits
            .withActiveTypedSupportForSourceCandidates(snapshot)
        val rawSourceDeferral = sourceTypedSupportHits.deferRawSourceCandidatesToEvidenceHydration(
            plan = plan,
            activeTypedRefs = activeTypedRefsBeforeSourceSupport,
        )
        if (rawSourceDeferral.changed) {
            log.info {
                "Memory read raw source candidates deferred: namespace=${request.namespace.value} " +
                    "mode=${plan.answerMode.name} droppedSources=${rawSourceDeferral.droppedSources.size} " +
                    "activeTyped=${rawSourceDeferral.activeTypedRefs.joinToString("|") { "${it.type.name.lowercase()}:${it.id}" }} " +
                    "droppedSourceIds=${rawSourceDeferral.droppedSources.joinToString("|") { it.source.id.value }}"
            }
        }
        val budgetedHits = rawSourceDeferral.hits
            .withActiveTypedReplacementsForSourceCandidates(snapshot)
            .enforceBudget(plan, expandForSelector = true)
        val selectorCandidateHits = budgetedHits
            .withActiveTypedSupportForSourceCandidates(snapshot)
            .withActiveTypedReplacementsForSourceCandidates(snapshot)
        val selectionResult = selector.select(
            MemoryReadSelectionRequest(
                readRequest = request,
                plan = plan,
                candidateHits = selectorCandidateHits,
                snapshot = snapshot,
            )
        )
        val coreProfileSelection = selectionResult.selectedHits.keepPlannerRequestedProfiles(
            plan = plan,
            candidateHits = selectorCandidateHits,
            selectorDecisions = selectionResult.decisions,
        )
        if (coreProfileSelection.changed) {
            log.info {
                "Memory read core profile restored: namespace=${request.namespace.value} " +
                    "restoredProfiles=${coreProfileSelection.addedHits.joinToString("|") { it.profile.id.value }}"
            }
        }
        val selectedHitsBeforeSafety = coreProfileSelection.hits.enforceBudget(plan)
        val selectorDecisions = selectionResult.decisions
            .filterNot { decision -> decision.ref in coreProfileSelection.addedRefs }
            .let { decisions -> decisions + coreProfileSelection.decisions }
        val sourceFilteredHits = selectedHitsBeforeSafety.filterNonRequiredSourcesWhenTypedMemoryAnswers(plan)
        if (sourceFilteredHits.changed) {
            log.info {
                "Memory read source selection pruned: namespace=${request.namespace.value} " +
                    "mode=${plan.answerMode.name} requireFallback=${plan.requireEvidenceFallback} " +
                    "droppedSources=${sourceFilteredHits.droppedSources.size} " +
                    "droppedSourceIds=${sourceFilteredHits.droppedSources.joinToString("|") { it.source.id.value }}"
            }
        }
        val sourceSafety = sourceFilteredHits.hits.applyActiveTypedMemorySourceSafety(snapshot, selectorCandidateHits)
        if (sourceSafety.changed) {
            log.info {
                "Memory read source freshness guard: namespace=${request.namespace.value} " +
                    "suppressedSources=${sourceSafety.suppressedSourceIds.size} " +
                    "suppressedSourceIds=${sourceSafety.suppressedSourceIds.joinToString { it.value }} " +
                    "restoredTypedRefs=${sourceSafety.restoredTypedRefs.joinToString { "${it.type.name.lowercase()}:${it.id}" }}"
            }
        }
        val selectedHits = sourceSafety.hits
        val evidenceHydratedHits = hydrateEvidenceSources(request, plan, selectedHits, snapshot)
        val entityHydratedHits = hydrateLinkedEntities(request, evidenceHydratedHits)
        searchSteps += MemoryReadTrace.SearchStep(
            stage = "selector",
            query = request.targetQueryText(),
            scope = "ALL",
            requestedLimit = plan.retrievalBudget.totalDebugLimit(),
            rawCount = selectorCandidateHits.size,
            candidateCount = selectorCandidateHits.size,
            selectedCount = selectedHits.size,
            rawTopHits = selectorCandidateHits.toTraceHits(limit = 8),
            selectedTopHits = selectedHits.toTraceHits(limit = 8),
        )
        searchSteps += MemoryReadTrace.SearchStep(
            stage = "budget",
            query = request.targetQueryText(),
            scope = "ALL",
            requestedLimit = plan.retrievalBudget.totalDebugLimit(),
            rawCount = hits.size,
            candidateCount = distinctHits.size,
            selectedCount = entityHydratedHits.size,
            rawTopHits = distinctHits.toTraceHits(limit = 8),
            selectedTopHits = entityHydratedHits.toTraceHits(limit = 8),
        )

        log.info {
            "Memory read budget applied: namespace=${request.namespace.value} before=${hits.size} " +
                "distinct=${distinctHits.size} sourcePolicy=${sourceSelection.summaryForLog()} " +
                "sourceDeferral=${rawSourceDeferral.summaryForLog()} after=${budgetedHits.size} " +
                "selected=${selectedHits.size} evidenceHydrated=${evidenceHydratedHits.size} entityHydrated=${entityHydratedHits.size} " +
                "beforeBreakdown=${hits.breakdownForRuntimeMemoryLog()} distinctBreakdown=${distinctHits.breakdownForRuntimeMemoryLog()} " +
                "afterBreakdown=${budgetedHits.breakdownForRuntimeMemoryLog()} selectedBreakdown=${selectedHits.breakdownForRuntimeMemoryLog()} " +
                "evidenceHydratedBreakdown=${evidenceHydratedHits.breakdownForRuntimeMemoryLog()} " +
                "entityHydratedBreakdown=${entityHydratedHits.breakdownForRuntimeMemoryLog()} " +
                "budget=${plan.retrievalBudget}"
        }

        return RuntimeMemoryRetrievedHits(
            hits = entityHydratedHits,
            selectorCandidateHits = selectorCandidateHits,
            selectorDecisions = selectorDecisions,
            sourceSafety = sourceSafety,
        )
    }

    private suspend fun queryEmbedding(
        query: String,
        cache: MutableMap<String, MemoryStore.SearchEmbedding?>,
    ): MemoryStore.SearchEmbedding? {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return null
        if (cache.containsKey(normalizedQuery)) return cache[normalizedQuery]
        val embedding = embeddingIndexer.searchEmbedding(normalizedQuery)
        cache[normalizedQuery] = embedding
        return embedding
    }

    private suspend fun hydrateLinkedEntities(
        request: MemoryReadRequest,
        hits: List<MemoryStore.SearchHit>,
    ): List<MemoryStore.SearchHit> {
        val existingEntityIds = hits
            .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .mapTo(mutableSetOf()) { it.entity.id }
        val linkedEntityIds = hits
            .flatMap { it.linkedEntityIdsForRecall() }
            .distinct()
            .filterNot { it in existingEntityIds }
            .take(6)

        if (linkedEntityIds.isEmpty()) return hits

        val entityHits = store.search(
            MemoryStore.SearchRequest(
                query = "",
                namespace = request.namespace,
                scopes = setOf(MemoryStore.SearchScope.ENTITIES),
                filters = MemoryStore.SearchFilters(entityIds = linkedEntityIds.toSet()),
                limit = linkedEntityIds.size,
            )
        ).filterIsInstance<MemoryStore.SearchHit.EntityHit>()

        log.info {
            "Memory read entity hydration: namespace=${request.namespace.value} requested=${linkedEntityIds.size} " +
                "found=${entityHits.size} entities=${entityHits.summaryForRuntimeMemoryLog()}"
        }

        return hits + entityHits
    }

    private suspend fun hydrateEvidenceSources(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
        hits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): List<MemoryStore.SearchHit> {
        val sourceLimit = plan.evidenceHydrationSourceLimit()
        if (sourceLimit <= 0) return hits

        val unsafeFullEvidenceSourceIds = snapshot.sourceIdsWithActiveTypedReplacement()
        val rawEvidenceSourceIds = hits
            .flatMap { it.evidenceRefsForRecall() }
            .map { it.sourceId }
            .distinct()
            .filterNot { it in request.currentThreadChatSourceIds() }
        val suppressedUnsafeEvidenceSourceIds = rawEvidenceSourceIds.filter { it in unsafeFullEvidenceSourceIds }
        val evidenceSourceIds = rawEvidenceSourceIds
            .filterNot { it in unsafeFullEvidenceSourceIds }
        val existingSourceIds = hits
            .filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .mapTo(mutableSetOf()) { it.source.id }
        val sourceIds = hits
            .let { evidenceSourceIds }
            .filterNot { it in existingSourceIds }
            .take(sourceLimit)

        if (sourceIds.isEmpty()) return hits.keepLinkedEvidenceSources(evidenceSourceIds)

        val sourceById = store.findSourcesByIds(sourceIds)
            .filter { it.namespace == request.namespace }
            .filterNot { it.isFromThread(request.threadContext.threadId) }
            .associateBy { it.id }
        val rawSourceHits = sourceIds
            .mapNotNull { sourceById[it] }
            .map { MemoryStore.SearchHit.SourceHit(it, score = 1.0) }
        val sourceSelection = MemorySourceRetrievalPolicy.apply(
            hits = rawSourceHits,
            useCase = MemorySourceRetrievalUseCase.READ_EVIDENCE,
        )
        val sourceHits = sourceSelection.hits

        log.info {
            "Memory read evidence hydration: namespace=${request.namespace.value} mode=${plan.answerMode.name} " +
                "requireFallback=${plan.requireEvidenceFallback} requested=${sourceIds.size} rawFound=${rawSourceHits.size} " +
                "found=${sourceHits.size} suppressedUnsafeEvidenceSources=${suppressedUnsafeEvidenceSourceIds.size} " +
                "sourcePolicy=${sourceSelection.summaryForLog()} sources=${sourceHits.summaryForRuntimeMemoryLog()}"
        }

        return (hits + sourceHits).keepLinkedEvidenceSources(evidenceSourceIds)
    }
}

private data class RuntimeMemoryRetrievedHits(
    val hits: List<MemoryStore.SearchHit>,
    val selectorCandidateHits: List<MemoryStore.SearchHit>,
    val selectorDecisions: List<MemoryReadSelectionResult.Decision>,
    val sourceSafety: RuntimeMemorySourceSafetyResult,
)

private data class RuntimeMemoryCoreProfileSelection(
    val hits: List<MemoryStore.SearchHit>,
    val addedHits: List<MemoryStore.SearchHit.ProfileHit>,
    val decisions: List<MemoryReadSelectionResult.Decision>,
) {
    val addedRefs: Set<MemoryItemRef> = addedHits.mapTo(mutableSetOf()) { it.toItemRef() }
    val changed: Boolean = addedHits.isNotEmpty()
}

private data class RuntimeMemorySourceSafetyResult(
    val hits: List<MemoryStore.SearchHit>,
    val suppressedSourceHits: List<MemoryStore.SearchHit.SourceHit> = emptyList(),
    val restoredTypedHits: List<MemoryStore.SearchHit> = emptyList(),
) {
    val suppressedSourceIds: Set<MemorySource.Id> = suppressedSourceHits.mapTo(mutableSetOf()) { it.source.id }
    val restoredTypedRefs: Set<MemoryItemRef> = restoredTypedHits.mapTo(mutableSetOf()) { it.toItemRef() }
    val changed: Boolean = suppressedSourceIds.isNotEmpty() || restoredTypedRefs.isNotEmpty()
}

private data class RuntimeMemoryRawSourceDeferralResult(
    val hits: List<MemoryStore.SearchHit>,
    val droppedSources: List<MemoryStore.SearchHit.SourceHit> = emptyList(),
    val activeTypedRefs: List<MemoryItemRef> = emptyList(),
) {
    val changed: Boolean = droppedSources.isNotEmpty()

    fun summaryForLog(): String =
        "sources=${droppedSources.size} activeTyped=${activeTypedRefs.size}"
}

private data class RuntimeMemoryTargetEntities(
    val hits: List<MemoryStore.SearchHit.EntityHit>,
) {
    val filterEntityIds: Set<MemoryEntity.Id> = hits.mapTo(mutableSetOf()) { it.entity.id }
}

object RuntimeMemoryPromptComposer {
    fun compose(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
        hits: List<MemoryStore.SearchHit>,
    ): String? {
        if (hits.isEmpty()) {
            return """
                MEMORY-ONLY CONTEXT
                This message is not part of the real conversation and must not be stored as evidence.
                No relevant persisted memory was retrieved for the immediately following user request.
                If the user asks what memory contains or what should be remembered, do not guess from common sense, test setup, or absent context; say that memory is insufficient.

                Namespace: ${request.namespace.value}
                Answer mode: ${plan.answerMode.name}
            """.trimIndent()
        }

        return """
            MEMORY-ONLY CONTEXT
            This message is not part of the real conversation and must not be stored as evidence.
            The retrieved memory below was selected for the immediately following user request.
            Treat it as the strongest available remembered context, stronger than guesses, defaults, or general world knowledge.
            Use selected active memory for the answer unless it is clearly irrelevant, insufficient, stale, internally conflicting, or contradicted by the current user message.
            Do not claim that raw sources are verified facts; prefer active claims for facts, notes for rationale, and tasks for commitments.
            If raw source wording conflicts with active typed memory, trust the active typed memory for current facts.
            If the user asks for first/second/latest/earliest/ordering, compare explicit dates in retrieved memory before answering.
            If the user asks for an exact quote, exact wording, source, or when something was said, prefer the complete source text from Retrieved evidence; evidence quote fields are short excerpts and may be incomplete.
            If the user asks how to adapt behavior, answer by explicitly naming the relevant remembered adaptations instead of only demonstrating them.

            Namespace: ${request.namespace.value}
            Answer mode: ${plan.answerMode.name}
            Require evidence fallback: ${plan.requireEvidenceFallback}

            Retrieved profile:
            ${hits.renderProfiles()}

            Retrieved tasks:
            ${hits.renderTasks()}

            Retrieved claims:
            ${hits.renderClaims(includeEvidence = plan.shouldRenderEvidenceInPrompt())}

            Retrieved notes:
            ${hits.renderNotes(includeEvidence = plan.shouldRenderEvidenceInPrompt())}

            Retrieved evidence:
            ${hits.renderSources(includeEvidence = plan.shouldRenderEvidenceInPrompt(), query = request.sourceFallbackSearchQuery(plan))}

            Retrieved entities:
            ${hits.renderEntities()}

            Retrieved episodes:
            ${hits.renderEpisodes()}
        """.trimIndent()
    }
}

private fun MemoryReadRequest.targetQueryText(): String {
    val target = threadContext.messages.firstOrNull { it.id == threadContext.targetMessageId }
        ?: return ""

    return target.content.mapNotNull { item ->
        when (item) {
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.UserMessage -> item.text
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall -> "Tool call: ${item.call.name}"
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolResult -> "Tool result: ${item.toolName} error=${item.isError}"
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.System -> "[${item.level.name}] ${item.content}"
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.ImageItem -> "[image:${item.source.type}]"
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
            is com.gromozeka.domain.model.Conversation.Message.ContentItem.Thinking -> null
        }
    }.joinToString("\n").trim()
}

private fun MemoryReadRequest.entityResolutionText(plan: MemoryReadPlan): String =
    buildList {
        add(targetQueryText())
        plan.retrievalRequests.forEach { retrievalRequest ->
            add(retrievalRequest.query)
        }
    }.joinToString("\n").trim()

private fun MemoryNamespaceSnapshot.resolveTargetEntitiesForRead(
    request: MemoryReadRequest,
    plan: MemoryReadPlan,
): RuntimeMemoryTargetEntities {
    val query = request.entityResolutionText(plan).normalizedEntityResolutionText()
    if (query.isBlank()) return RuntimeMemoryTargetEntities(emptyList())

    val matchedHits = entities
        .asSequence()
        .filter { it.status == MemoryEntity.Status.ACTIVE }
        .mapNotNull { entity ->
            entity.entityResolutionScore(query)?.let { score ->
                MemoryStore.SearchHit.EntityHit(entity, score)
            }
        }
        .sortedWith(
            compareByDescending<MemoryStore.SearchHit.EntityHit> { it.score }
                .thenByDescending { it.entity.updatedAt }
        )
        .toList()
    val subjectHits = matchedHits.filter { it.entity.entityType.isSubjectAnchorType() }
    val profileAnchorHits = entities.profileAnchorHitsForRead(query, plan)
    val hits = (profileAnchorHits + subjectHits.ifEmpty { matchedHits })
        .distinctBy { it.entity.id }
        .take(4)

    return RuntimeMemoryTargetEntities(hits)
}

private fun List<MemoryEntity>.profileAnchorHitsForRead(
    query: String,
    plan: MemoryReadPlan,
): List<MemoryStore.SearchHit.EntityHit> {
    if (!plan.requestsProfileContext() || !query.hasFirstPersonSingularReference()) {
        return emptyList()
    }

    return asSequence()
        .filter { it.status == MemoryEntity.Status.ACTIVE }
        .filter { it.entityType == MemoryEntity.Type.USER }
        .map { MemoryStore.SearchHit.EntityHit(it, score = 2.0) }
        .take(1)
        .toList()
}

private fun MemoryReadPlan.requestsProfileContext(): Boolean =
    MemoryReadPlan.CoreBlock.PROFILE in coreBlocks ||
        retrievalRequests.any { it.memoryType == MemorySemanticType.PROFILE }

private fun String.hasFirstPersonSingularReference(): Boolean =
    Regex("""\b(i|me|my|mine|myself)\b""").containsMatchIn(this)

private fun MemoryEntity.Type.isSubjectAnchorType(): Boolean =
    when (this) {
        MemoryEntity.Type.USER,
        MemoryEntity.Type.PERSON,
        MemoryEntity.Type.AGENT,
        MemoryEntity.Type.ORGANIZATION,
        MemoryEntity.Type.PROJECT,
        MemoryEntity.Type.REPO,
        MemoryEntity.Type.FILE,
        MemoryEntity.Type.PRODUCT,
        MemoryEntity.Type.LOCATION,
        MemoryEntity.Type.DOCUMENT,
        MemoryEntity.Type.CONVERSATION,
        MemoryEntity.Type.SERVICE,
        MemoryEntity.Type.ENVIRONMENT,
        -> true

        MemoryEntity.Type.TECHNOLOGY,
        MemoryEntity.Type.CONCEPT,
        MemoryEntity.Type.OTHER,
        -> false
    }

private fun MemoryEntity.entityResolutionScore(query: String): Double? {
    val names = (listOf(canonicalName, normalizedName) + aliases.flatMap { listOf(it.text, it.normalizedText) })
        .map { it.normalizedEntityResolutionText() }
        .filter { it.length >= 3 }
        .distinct()
    if (names.isEmpty()) return null

    val best = names.maxOfOrNull { name ->
        when {
            query.containsEntityPhrase(name) -> 2.0 + name.length.coerceAtMost(40) / 100.0
            name.contains(" ") && name.split(" ").all { token -> query.containsEntityPhrase(token) } -> 1.4
            else -> 0.0
        }
    } ?: 0.0

    return best.takeIf { it > 0.0 }
}

private fun String.containsEntityPhrase(phrase: String): Boolean {
    if (phrase.isBlank()) return false
    return " $this ".contains(" $phrase ")
}

private fun String.normalizedEntityResolutionText(): String =
    expandCamelCaseForEntityResolution()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.expandCamelCaseForEntityResolution(): String =
    replace(Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})"), " ")

private fun MemorySemanticType.toSearchScope(): MemoryStore.SearchScope? =
    when (this) {
        MemorySemanticType.CLAIM -> MemoryStore.SearchScope.CLAIMS
        MemorySemanticType.NOTE -> MemoryStore.SearchScope.NOTES
        MemorySemanticType.TASK -> MemoryStore.SearchScope.TASKS
        MemorySemanticType.PROFILE -> MemoryStore.SearchScope.PROFILES
        MemorySemanticType.SOURCE -> MemoryStore.SearchScope.SOURCES
        MemorySemanticType.ENTITY -> MemoryStore.SearchScope.ENTITIES
        MemorySemanticType.EPISODE -> MemoryStore.SearchScope.EPISODES
    }

private fun MemoryStore.SearchScope.selectorCandidateSearchLimit(
    resultLimit: Int,
    request: MemoryReadRequest,
): Int =
    when (this) {
        MemoryStore.SearchScope.SOURCES -> (resultLimit + request.threadContext.messages.size + 4).coerceAtMost(50)
        MemoryStore.SearchScope.CLAIMS,
        MemoryStore.SearchScope.NOTES,
        MemoryStore.SearchScope.TASKS,
        MemoryStore.SearchScope.EPISODES,
        -> resultLimit.expandedForSelectorCandidates()

        else -> resultLimit
    }.coerceAtLeast(resultLimit)

private fun MemorySemanticType.defaultFilters(): MemoryStore.SearchFilters =
    when (this) {
        MemorySemanticType.CLAIM -> MemoryStore.SearchFilters(
            claimStatuses = setOf(MemoryClaim.Status.ACTIVE),
        )

        MemorySemanticType.NOTE -> MemoryStore.SearchFilters(
            noteStatuses = setOf(MemoryNote.Status.ACTIVE),
        )

        MemorySemanticType.TASK -> MemoryStore.SearchFilters()

        else -> MemoryStore.SearchFilters()
    }

private fun MemoryStore.SearchFilters.withTargetEntityIds(
    memoryType: MemorySemanticType,
    targetEntityIds: Set<MemoryEntity.Id>,
): MemoryStore.SearchFilters {
    if (targetEntityIds.isEmpty() || !memoryType.supportsEntityFilter()) return this

    val filteredEntityIds = if (entityIds.isEmpty()) {
        targetEntityIds
    } else {
        entityIds.intersect(targetEntityIds)
    }
    return copy(entityIds = filteredEntityIds)
}

private fun MemorySemanticType.supportsEntityFilter(): Boolean =
    when (this) {
        MemorySemanticType.CLAIM,
        MemorySemanticType.NOTE,
        MemorySemanticType.TASK,
        MemorySemanticType.PROFILE,
        MemorySemanticType.ENTITY,
        MemorySemanticType.EPISODE,
        -> true

        MemorySemanticType.SOURCE -> false
    }

private fun List<MemoryStore.SearchHit>.applySourceRetrievalPolicyFor(
    memoryType: MemorySemanticType,
): MemorySourceRetrievalSelection =
    if (memoryType == MemorySemanticType.SOURCE) {
        MemorySourceRetrievalPolicy.apply(this, MemorySourceRetrievalUseCase.READ_RETRIEVAL)
    } else {
        MemorySourceRetrievalSelection(
            hits = this,
            beforeSources = count { it is MemoryStore.SearchHit.SourceHit },
            afterSources = count { it is MemoryStore.SearchHit.SourceHit },
            droppedReasons = emptyMap(),
        )
    }

private fun List<MemoryStore.SearchHit>.deferRawSourceCandidatesToEvidenceHydration(
    plan: MemoryReadPlan,
    activeTypedRefs: List<MemoryItemRef>,
): RuntimeMemoryRawSourceDeferralResult {
    if (!plan.defersRawSourcesWhenTypedMemoryExists()) {
        return RuntimeMemoryRawSourceDeferralResult(hits = this)
    }

    if (activeTypedRefs.isEmpty()) {
        return RuntimeMemoryRawSourceDeferralResult(hits = this)
    }

    val droppedSources = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
    if (droppedSources.isEmpty()) {
        return RuntimeMemoryRawSourceDeferralResult(hits = this, activeTypedRefs = activeTypedRefs)
    }

    return RuntimeMemoryRawSourceDeferralResult(
        hits = filterNot { it is MemoryStore.SearchHit.SourceHit },
        droppedSources = droppedSources,
        activeTypedRefs = activeTypedRefs,
    )
}

private fun List<MemoryStore.SearchHit>.currentTruthBearingTypedRefs(): List<MemoryItemRef> =
    filter { it.isCurrentTruthBearingTypedHit() }
        .map { it.toItemRef() }
        .distinct()

private fun MemoryReadPlan.defersRawSourcesWhenTypedMemoryExists(): Boolean =
    when (answerMode) {
        MemoryReadPlan.AnswerMode.FACTUAL,
        MemoryReadPlan.AnswerMode.TASK,
        -> true

        MemoryReadPlan.AnswerMode.MIXED,
        MemoryReadPlan.AnswerMode.RATIONALE,
        -> false
    }

private fun MemoryStore.SearchHit.isCurrentTruthBearingTypedHit(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.status == MemoryClaim.Status.ACTIVE
        is MemoryStore.SearchHit.NoteHit -> note.status == MemoryNote.Status.ACTIVE
        is MemoryStore.SearchHit.TaskHit -> task.status in setOf(
            MemoryTask.Status.OPEN,
            MemoryTask.Status.IN_PROGRESS,
            MemoryTask.Status.BLOCKED,
        )

        is MemoryStore.SearchHit.EpisodeHit -> true
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.RunHit,
        -> false
    }

private fun List<MemoryStore.SearchHit>.prioritizeForReadRequest(
    retrievalRequest: MemoryReadPlan.RetrievalRequest,
): List<MemoryStore.SearchHit> {
    if (retrievalRequest.memoryType != MemorySemanticType.CLAIM) return this
    if (retrievalRequest.preferredClaimPredicates.isEmpty() && retrievalRequest.deprioritizedClaimPredicates.isEmpty()) {
        return this
    }

    return sortedWith(
        compareByDescending<MemoryStore.SearchHit> { it.claimPredicatePriority(retrievalRequest) }
            .thenByDescending { it.score }
    )
}

private fun MemoryReadPlan.RetrievalRequest.searchQuery(
    request: MemoryReadRequest,
    plan: MemoryReadPlan,
): String {
    if (memoryType != MemorySemanticType.SOURCE) return query

    val target = request.targetQueryText()
    return buildList {
        add(query)
        if (target.isNotBlank() && target !in query) add(target)
        plan.retrievalRequests
            .filter { it.memoryType != MemorySemanticType.SOURCE }
            .map { it.query }
            .distinct()
            .forEach { siblingQuery ->
                if (siblingQuery.isNotBlank() && siblingQuery !in query && siblingQuery != target) {
                    add(siblingQuery)
                }
            }
    }.joinToString("\n")
}

private fun MemoryReadPlan.shouldTrySourceFallback(hits: List<MemoryStore.SearchHit>): Boolean =
    needMemory &&
        retrievalRequests.none { it.memoryType == MemorySemanticType.SOURCE } &&
        (requireEvidenceFallback || answerMode == MemoryReadPlan.AnswerMode.RATIONALE || hits.none { it.isAnswerCandidateForRecall() })

private fun MemoryStore.SearchHit.isAnswerCandidateForRecall(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit,
        is MemoryStore.SearchHit.NoteHit,
        is MemoryStore.SearchHit.TaskHit,
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.EpisodeHit,
        is MemoryStore.SearchHit.SourceHit,
        -> true

        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.RunHit,
        -> false
    }

private fun MemoryReadRequest.sourceFallbackSearchQuery(plan: MemoryReadPlan): String =
    buildList {
        add(targetQueryText())
        plan.retrievalRequests
            .map { it.query }
            .distinct()
            .forEach(::add)
    }.joinToString("\n").trim()

private fun MemoryStore.SearchHit.claimPredicatePriority(retrievalRequest: MemoryReadPlan.RetrievalRequest): Int {
    if (this !is MemoryStore.SearchHit.ClaimHit) return 0

    val predicate = claim.predicate.lowercase()
    val preferred = retrievalRequest.preferredClaimPredicates.mapTo(mutableSetOf()) { it.lowercase() }
    val deprioritized = retrievalRequest.deprioritizedClaimPredicates.mapTo(mutableSetOf()) { it.lowercase() }
    return when {
        predicate in preferred -> 2
        predicate in deprioritized -> -1
        else -> 0
    }
}

private fun MemoryReadPlan.sourceRetrievalUseCase(): MemorySourceRetrievalUseCase =
    if (shouldRenderEvidenceInPrompt()) {
        MemorySourceRetrievalUseCase.READ_EVIDENCE
    } else {
        MemorySourceRetrievalUseCase.READ_RETRIEVAL
    }

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.limitFor(type: MemorySemanticType): Int? =
    when (type) {
        MemorySemanticType.CLAIM -> claims
        MemorySemanticType.NOTE -> notes
        MemorySemanticType.TASK -> tasks
        MemorySemanticType.SOURCE -> sources
        MemorySemanticType.EPISODE -> episodes
        MemorySemanticType.PROFILE -> profilesLimit()
        MemorySemanticType.ENTITY -> 4
    }.takeIf { it > 0 }

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.tasksLimit(default: Int): Int =
    tasks.takeIf { it > 0 } ?: default

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.profilesLimit(): Int = 2

private fun List<MemoryStore.SearchHit>.enforceBudget(
    plan: MemoryReadPlan,
    expandForSelector: Boolean = false,
): List<MemoryStore.SearchHit> {
    val profiles = mutableListOf<MemoryStore.SearchHit.ProfileHit>()
    val claims = mutableListOf<MemoryStore.SearchHit.ClaimHit>()
    val notes = mutableListOf<MemoryStore.SearchHit.NoteHit>()
    val tasks = mutableListOf<MemoryStore.SearchHit.TaskHit>()
    val sources = mutableListOf<MemoryStore.SearchHit.SourceHit>()
    val episodes = mutableListOf<MemoryStore.SearchHit.EpisodeHit>()
    val entities = mutableListOf<MemoryStore.SearchHit.EntityHit>()

    for (hit in this) {
        when (hit) {
            is MemoryStore.SearchHit.ProfileHit -> profiles += hit
            is MemoryStore.SearchHit.ClaimHit -> claims += hit
            is MemoryStore.SearchHit.NoteHit -> notes += hit
            is MemoryStore.SearchHit.TaskHit -> tasks += hit
            is MemoryStore.SearchHit.SourceHit -> sources += hit
            is MemoryStore.SearchHit.EpisodeHit -> episodes += hit
            is MemoryStore.SearchHit.EntityHit -> entities += hit
            is MemoryStore.SearchHit.RunHit -> Unit
        }
    }

    return buildList {
        addAll(profiles.take(plan.retrievalBudget.profilesLimit()))
        addAll(claims.take((plan.retrievalBudget.claims.takeIf { it > 0 } ?: 6).maybeExpandForSelector(expandForSelector)))
        addAll(notes.take((plan.retrievalBudget.notes.takeIf { it > 0 } ?: 4).maybeExpandForSelector(expandForSelector)))
        addAll(tasks.take((plan.retrievalBudget.tasks.takeIf { it > 0 } ?: 3).maybeExpandForSelector(expandForSelector)))
        addAll(sources.take(plan.retrievalBudget.sources.takeIf { it > 0 } ?: 3))
        addAll(episodes.take((plan.retrievalBudget.episodes.takeIf { it > 0 } ?: 2).maybeExpandForSelector(expandForSelector)))
        addAll(entities.take(4))
    }
}

private fun Int.maybeExpandForSelector(expand: Boolean): Int =
    if (expand) expandedForSelectorCandidates() else this

private fun Int.expandedForSelectorCandidates(): Int =
    (this * 4 + 4).coerceAtMost(50)

private fun List<MemoryStore.SearchHit>.keepPlannerRequestedProfiles(
    plan: MemoryReadPlan,
    candidateHits: List<MemoryStore.SearchHit>,
    selectorDecisions: List<MemoryReadSelectionResult.Decision>,
): RuntimeMemoryCoreProfileSelection {
    if (MemoryReadPlan.CoreBlock.PROFILE !in plan.coreBlocks) {
        return RuntimeMemoryCoreProfileSelection(
            hits = this,
            addedHits = emptyList(),
            decisions = emptyList(),
        )
    }

    val selectedRefs = mapTo(mutableSetOf()) { it.toItemRef() }
    val explicitlyRejectedRefs = selectorDecisions
        .filterNot { it.selected }
        .mapTo(mutableSetOf()) { it.ref }
    val profileHits = candidateHits
        .filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
        .filterNot { it.toItemRef() in selectedRefs }
        .filterNot { it.toItemRef() in explicitlyRejectedRefs }

    return RuntimeMemoryCoreProfileSelection(
        hits = this + profileHits,
        addedHits = profileHits,
        decisions = profileHits.mapIndexed { index, hit ->
            MemoryReadSelectionResult.Decision(
                ref = hit.toItemRef(),
                selected = true,
                rank = size + index + 1,
                reason = "Planner requested PROFILE core block; keeping projection as compact adaptation context.",
            )
        },
    )
}

private fun List<MemoryStore.SearchHit>.renderProfiles(): String =
    filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
        .joinToString("\n") { "- profile ${it.profile.id.value}: ${it.profile.profileText}" }
        .ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderTasks(): String =
    filterIsInstance<MemoryStore.SearchHit.TaskHit>()
        .joinToString("\n") {
            "- task ${it.task.id.value} [${it.task.status.name}]: title=\"${it.task.title}\"; description=\"${it.task.description ?: "none"}\""
        }
        .ifBlank { "none" }

private data class RuntimeMemorySourcePruningResult(
    val hits: List<MemoryStore.SearchHit>,
    val droppedSources: List<MemoryStore.SearchHit.SourceHit> = emptyList(),
) {
    val changed: Boolean = droppedSources.isNotEmpty()
}

private fun List<MemoryStore.SearchHit>.filterNonRequiredSourcesWhenTypedMemoryAnswers(
    plan: MemoryReadPlan,
): RuntimeMemorySourcePruningResult {
    if (plan.shouldRenderEvidenceInPrompt()) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    val shouldPrune = when (plan.answerMode) {
        MemoryReadPlan.AnswerMode.FACTUAL,
        MemoryReadPlan.AnswerMode.TASK,
        -> true

        MemoryReadPlan.AnswerMode.MIXED,
        MemoryReadPlan.AnswerMode.RATIONALE,
        -> false
    }
    if (!shouldPrune || none { it.isCurrentTruthBearingTypedHit() }) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    val dropped = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
    if (dropped.isEmpty()) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    return RuntimeMemorySourcePruningResult(
        hits = filterNot { it is MemoryStore.SearchHit.SourceHit },
        droppedSources = dropped,
    )
}

private fun List<MemoryStore.SearchHit>.renderClaims(includeEvidence: Boolean): String =
    filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
        .joinToString("\n") {
            val evidence = if (includeEvidence) "; evidence=${it.claim.evidenceRefs.renderEvidenceRefs()}" else ""
            "- claim ${it.claim.id.value} [${it.claim.status.name}] ${it.claim.predicate} family=${it.claim.predicateFamily ?: "unknown"}: ${it.claim.normalizedText}; scope=${it.claim.scope.text}$evidence"
        }
        .ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderNotes(includeEvidence: Boolean): String =
    filterIsInstance<MemoryStore.SearchHit.NoteHit>()
        .joinToString("\n") {
            val evidence = if (includeEvidence) "; evidence=${it.note.evidenceRefs.renderEvidenceRefs()}" else ""
            "- note ${it.note.id.value} [${it.note.noteType.name}/${it.note.status.name}]: ${it.note.title}; ${it.note.summary}; scope=${it.note.scope.text}$evidence"
        }
        .ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderSources(includeEvidence: Boolean, query: String): String {
    val selectedSources = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
    if (selectedSources.isEmpty() && !includeEvidence) {
        return "not requested for this answer mode"
    }

    return MemorySourceRetrievalPolicy.apply(
        hits = selectedSources,
        useCase = if (includeEvidence) MemorySourceRetrievalUseCase.READ_EVIDENCE else MemorySourceRetrievalUseCase.READ_RETRIEVAL,
    ).hits
        .filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .joinToString("\n") {
            "- source ${it.source.id.value} [${it.source.sourceLabelForMemoryPrompt()}]: ${it.source.contentText.queryFocusedExcerptForMemoryPrompt(query)}"
        }
        .ifBlank { "none" }
}

private fun String.queryFocusedExcerptForMemoryPrompt(query: String, maxChars: Int = 4_000): String {
    val text = trim()
    if (text.length <= maxChars) return text

    val terms = query
        .split(Regex("[^\\p{L}\\p{N}_-]+"))
        .map { it.trim() }
        .filter { it.length >= 4 }
        .distinctBy { it.lowercase() }
        .take(16)
    val windows = terms
        .mapNotNull { term -> text.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
        .map { index ->
            val start = (index - 500).coerceAtLeast(0)
            val end = (index + 900).coerceAtMost(text.length)
            start to end
        }
        .sortedBy { it.first }
        .fold(mutableListOf<Pair<Int, Int>>()) { acc, window ->
            val previous = acc.lastOrNull()
            if (previous != null && window.first <= previous.second + 120) {
                acc[acc.lastIndex] = previous.first to maxOf(previous.second, window.second)
            } else {
                acc += window
            }
            acc
        }

    if (windows.isEmpty()) return text.truncateForRuntimeMemoryPrompt(maxChars)

    val head = text.take(500)
    val snippets = mutableListOf<String>()
    var used = head.length
    for ((start, end) in windows) {
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        val snippet = "$prefix${text.substring(start, end).trim()}$suffix"
        if (used + snippet.length + 32 > maxChars) break
        snippets += snippet
        used += snippet.length + 32
    }

    return buildList {
        add(head)
        add("...[matching excerpts]...")
        addAll(snippets)
    }.joinToString("\n")
}

private fun List<MemoryStore.SearchHit>.renderEntities(): String =
    filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .joinToString("\n") { "- entity ${it.entity.id.value} [${it.entity.entityType.name}]: ${it.entity.canonicalName}; ${it.entity.summary ?: "no summary"}" }
        .ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderEpisodes(): String =
    let { hits ->
        val entityById = hits
            .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .associateBy { it.entity.id }
        hits.filterIsInstance<MemoryStore.SearchHit.EpisodeHit>()
            .joinToString("\n") {
                val owner = it.episode.ownerEntityId?.let(entityById::get)?.entity?.canonicalName
                "- episode ${it.episode.id.value}: owner=${owner ?: "unknown"}; situation=${it.episode.situation}; action=${it.episode.action}; result=${it.episode.result}; lesson=${it.episode.lesson}"
            }
            .ifBlank { "none" }
    }

private fun MemoryStore.SearchHit.toItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.TaskHit -> MemoryItemRef(MemoryItemRef.Type.TASK, task.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }

private fun MemoryStore.SearchHit.isEmptyProfileHit(): Boolean =
    this is MemoryStore.SearchHit.ProfileHit &&
        profile.profileText.contains("No active profile-synced memory.", ignoreCase = true)

private fun MemoryReadPlan.evidenceHydrationSourceLimit(): Int =
    when (answerMode) {
        MemoryReadPlan.AnswerMode.RATIONALE -> retrievalBudget.sources.takeIf { it > 0 } ?: 4
        MemoryReadPlan.AnswerMode.MIXED -> if (shouldIncludeSourceEvidence()) retrievalBudget.sources.takeIf { it > 0 } ?: 2 else 0
        MemoryReadPlan.AnswerMode.FACTUAL,
        MemoryReadPlan.AnswerMode.TASK,
        -> if (shouldIncludeSourceEvidence()) retrievalBudget.sources.takeIf { it > 0 } ?: 2 else 0
    }

private fun MemoryReadPlan.shouldIncludeSourceEvidence(): Boolean =
    requireEvidenceFallback || retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE }

private fun MemoryReadPlan.shouldRenderEvidenceInPrompt(): Boolean =
    evidenceHydrationSourceLimit() > 0

private fun List<MemoryStore.SearchHit>.keepLinkedEvidenceSources(
    evidenceSourceIds: List<MemorySource.Id>,
): List<MemoryStore.SearchHit> {
    if (evidenceSourceIds.isEmpty()) return this
    val allowedSourceIds = evidenceSourceIds.toSet()
    return filter { hit ->
        hit !is MemoryStore.SearchHit.SourceHit || hit.source.id in allowedSourceIds
    }
}

private fun List<MemoryStore.SearchHit>.applyActiveTypedMemorySourceSafety(
    snapshot: MemoryNamespaceSnapshot,
    candidateHits: List<MemoryStore.SearchHit>,
): RuntimeMemorySourceSafetyResult {
    val selectedSourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    if (selectedSourceIds.isEmpty()) return RuntimeMemorySourceSafetyResult(hits = this)

    val suppressedSourceIds = selectedSourceIds.intersect(snapshot.sourceIdsWithActiveTypedReplacement())
    if (suppressedSourceIds.isEmpty()) return RuntimeMemorySourceSafetyResult(hits = this)

    val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
    val restoredHits = snapshot.activeTypedReplacementHitsForSources(
        sourceIds = suppressedSourceIds,
        candidateHits = candidateHits,
    ).filterNot { it.toItemRef() in existingRefs }
    val suppressedSourceHits = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .filter { it.source.id in suppressedSourceIds }
    val repairedHits = filterNot { hit ->
        hit is MemoryStore.SearchHit.SourceHit && hit.source.id in suppressedSourceIds
    } + restoredHits

    return RuntimeMemorySourceSafetyResult(
        hits = repairedHits,
        suppressedSourceHits = suppressedSourceHits,
        restoredTypedHits = restoredHits,
    )
}

private fun List<MemoryStore.SearchHit>.withActiveTypedReplacementsForSourceCandidates(
    snapshot: MemoryNamespaceSnapshot,
): List<MemoryStore.SearchHit> {
    val sourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    if (sourceIds.isEmpty()) return this

    val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
    val replacements = snapshot.activeTypedReplacementHitsForSources(
        sourceIds = sourceIds,
        candidateHits = this,
    ).filterNot { it.toItemRef() in existingRefs }
    if (replacements.isEmpty()) return this

    return this + replacements
}

private fun List<MemoryStore.SearchHit>.withActiveTypedSupportForSourceCandidates(
    snapshot: MemoryNamespaceSnapshot,
): List<MemoryStore.SearchHit> {
    val sourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    if (sourceIds.isEmpty()) return this

    val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
    val supportedHits = snapshot.activeTypedSupportHitsForSources(sourceIds)
        .filterNot { it.toItemRef() in existingRefs }
    if (supportedHits.isEmpty()) return this

    return this + supportedHits
}

private fun MemoryNamespaceSnapshot.activeTypedSupportHitsForSources(
    sourceIds: Set<MemorySource.Id>,
): List<MemoryStore.SearchHit> =
    buildList {
        claims
            .filter { claim ->
                claim.status == MemoryClaim.Status.ACTIVE &&
                    claim.evidenceRefs.any { it.sourceId in sourceIds }
            }
            .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }
        notes
            .filter { note ->
                note.status == MemoryNote.Status.ACTIVE &&
                    note.evidenceRefs.any { it.sourceId in sourceIds }
            }
            .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 1.0) }
        tasks
            .filter { task ->
                task.archivedAt == null &&
                    task.evidenceRefs.any { it.sourceId in sourceIds }
            }
            .mapTo(this) { MemoryStore.SearchHit.TaskHit(it, score = 1.0) }
        episodes
            .filter { episode ->
                episode.archivedAt == null &&
                    episode.evidenceRefs.any { it.sourceId in sourceIds }
            }
            .mapTo(this) { MemoryStore.SearchHit.EpisodeHit(it, score = 1.0) }
    }.distinctBy { it.toItemRef() }

private fun MemoryNamespaceSnapshot.sourceIdsWithActiveTypedReplacement(): Set<MemorySource.Id> =
    buildSet {
        val activelyReplacedClaimIds = claims
            .filter { it.status == MemoryClaim.Status.ACTIVE }
            .mapNotNullTo(mutableSetOf()) { it.supersedesClaimId }
        val activeClaimIds = claims
            .filter { it.status == MemoryClaim.Status.ACTIVE }
            .mapTo(mutableSetOf()) { it.id }
        val activelyReplacedNoteIds = notes
            .filter { it.status == MemoryNote.Status.ACTIVE }
            .mapNotNullTo(mutableSetOf()) { it.supersedesNoteId }

        claims
            .filter { claim ->
                claim.status != MemoryClaim.Status.ACTIVE &&
                    (claim.id in activelyReplacedClaimIds || claim.retractedByClaimId in activeClaimIds)
            }
            .flatMapTo(this) { claim -> claim.evidenceRefs.map { it.sourceId } }
        notes
            .filter { note -> note.status != MemoryNote.Status.ACTIVE && note.id in activelyReplacedNoteIds }
            .flatMapTo(this) { note -> note.evidenceRefs.map { it.sourceId } }
    }

private fun MemoryNamespaceSnapshot.activeTypedReplacementHitsForSources(
    sourceIds: Set<MemorySource.Id>,
    candidateHits: List<MemoryStore.SearchHit>,
): List<MemoryStore.SearchHit> {
    val candidateHitsByRef = candidateHits.associateBy { it.toItemRef() }
    val replacementRefs = activeTypedReplacementRefsForSources(sourceIds)

    return replacementRefs.mapNotNull { ref ->
        candidateHitsByRef[ref] ?: when (ref.type) {
            MemoryItemRef.Type.CLAIM -> claims
                .firstOrNull { it.id.value == ref.id && it.status == MemoryClaim.Status.ACTIVE }
                ?.let { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }

            MemoryItemRef.Type.NOTE -> notes
                .firstOrNull { it.id.value == ref.id && it.status == MemoryNote.Status.ACTIVE }
                ?.let { MemoryStore.SearchHit.NoteHit(it, score = 1.0) }

            else -> null
        }
    }
}

private fun MemoryNamespaceSnapshot.activeTypedReplacementRefsForSources(
    sourceIds: Set<MemorySource.Id>,
): List<MemoryItemRef> {
    if (sourceIds.isEmpty()) return emptyList()

    val replacedClaimIds = claims
        .filter { claim ->
            claim.status != MemoryClaim.Status.ACTIVE &&
                claim.evidenceRefs.any { it.sourceId in sourceIds }
        }
        .mapTo(mutableSetOf()) { it.id }
    val activeClaimIdsByRetraction = claims
        .filter { claim -> claim.id in replacedClaimIds }
        .mapNotNullTo(mutableSetOf()) { it.retractedByClaimId }
    val replacedNoteIds = notes
        .filter { note ->
            note.status != MemoryNote.Status.ACTIVE &&
                note.evidenceRefs.any { it.sourceId in sourceIds }
        }
        .mapTo(mutableSetOf()) { it.id }

    return buildList {
        claims
            .filter { claim ->
                claim.status == MemoryClaim.Status.ACTIVE &&
                    (claim.supersedesClaimId in replacedClaimIds || claim.id in activeClaimIdsByRetraction)
            }
            .mapTo(this) { MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value) }
        notes
            .filter { note -> note.status == MemoryNote.Status.ACTIVE && note.supersedesNoteId in replacedNoteIds }
            .mapTo(this) { MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value) }
    }.distinct()
}

private fun MemoryStore.SearchHit.evidenceRefsForRecall(): List<MemoryEvidenceRef> =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.evidenceRefs
        is MemoryStore.SearchHit.NoteHit -> note.evidenceRefs
        is MemoryStore.SearchHit.TaskHit -> task.evidenceRefs
        is MemoryStore.SearchHit.EpisodeHit -> episode.evidenceRefs
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.RunHit,
        -> emptyList()
    }

private fun MemoryStore.SearchHit.linkedEntityIdsForRecall(): List<com.gromozeka.domain.model.memory.MemoryEntity.Id> =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> listOfNotNull(claim.subjectEntityId, claim.objectEntityId)
        is MemoryStore.SearchHit.NoteHit -> (listOfNotNull(note.anchorEntityId) + note.entityRefs.map { it.entityId }).distinct()
        is MemoryStore.SearchHit.TaskHit -> (listOfNotNull(task.ownerEntityId, task.assigneeEntityId) + task.relatedEntityIds).distinct()
        is MemoryStore.SearchHit.ProfileHit -> listOf(profile.ownerEntityId)
        is MemoryStore.SearchHit.EpisodeHit -> listOfNotNull(episode.ownerEntityId)
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.RunHit,
        -> emptyList()
    }

private fun List<MemoryStore.SearchHit>.excludeCurrentThreadSources(
    request: MemoryReadRequest,
): List<MemoryStore.SearchHit> =
    filterNot { hit ->
        hit is MemoryStore.SearchHit.SourceHit && hit.source.isFromThread(request.threadContext.threadId)
    }

private fun List<MemoryStore.SearchHit>.countCurrentThreadSources(
    request: MemoryReadRequest,
): Int =
    count { hit ->
        hit is MemoryStore.SearchHit.SourceHit && hit.source.isFromThread(request.threadContext.threadId)
    }

private fun MemoryReadRequest.currentThreadChatSourceIds(): Set<MemorySource.Id> =
    threadContext.messages.mapTo(mutableSetOf()) { message ->
        MemorySource.Id("chat:${message.id.value}")
    }

private fun MemorySource.isFromThread(threadId: Conversation.Thread.Id): Boolean =
    this is MemorySource.ChatTurn && this.threadId == threadId

private fun List<MemoryEvidenceRef>.renderEvidenceRefs(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(" | ") { ref ->
            val quote = ref.cachedQuote
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.truncateForRuntimeMemoryPrompt(300)
                ?.let { " quote=\"$it\"" }
                .orEmpty()
            "${ref.kind.name} source=${ref.sourceId.value}$quote"
        }
    }

private fun String.truncateForRuntimeMemoryPrompt(maxChars: Int): String {
    val trimmed = trim()
    if (trimmed.length <= maxChars) {
        return trimmed
    }
    return trimmed.take(maxChars) + "\n[truncated ${trimmed.length - maxChars} chars]"
}

private fun MemorySource.sourceLabelForMemoryPrompt(): String =
    when (this) {
        is MemorySource.ChatTurn -> "chat_turn role=${speakerRole.name} observedAt=$observedAt"
        is MemorySource.ToolOutput -> "tool_output tool=${toolName ?: "unknown"} observedAt=$observedAt"
        is MemorySource.ImportedNote -> "imported_note ref=${importRef ?: "unknown"} observedAt=$observedAt"
        is MemorySource.ExternalRecord -> "external_record ref=$recordRef observedAt=$observedAt"
    }

private fun List<MemoryStore.SearchHit>.breakdownForRuntimeMemoryLog(): String {
    if (isEmpty()) return "none"

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
    }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryStore.SearchHit>.summaryForRuntimeMemoryLog(): String {
    if (isEmpty()) return "none"

    return take(5).joinToString("|") { hit ->
        when (hit) {
            is MemoryStore.SearchHit.SourceHit -> "source:${hit.source.id.value}:${hit.source.contentText.oneLineForRuntimeMemoryLog(100)}"
            is MemoryStore.SearchHit.EntityHit -> "entity:${hit.entity.id.value}:${hit.entity.entityType.name}:${hit.entity.canonicalName.oneLineForRuntimeMemoryLog(80)}"
            is MemoryStore.SearchHit.ClaimHit -> "claim:${hit.claim.id.value}:${hit.claim.predicate}:${hit.claim.predicateFamily ?: "unknown"}:${hit.claim.normalizedText.oneLineForRuntimeMemoryLog(120)}"
            is MemoryStore.SearchHit.NoteHit -> "note:${hit.note.id.value}:${hit.note.noteType.name}:${hit.note.title.oneLineForRuntimeMemoryLog(100)}"
            is MemoryStore.SearchHit.TaskHit -> "task:${hit.task.id.value}:${hit.task.status.name}:${hit.task.title.oneLineForRuntimeMemoryLog(100)}"
            is MemoryStore.SearchHit.ProfileHit -> "profile:${hit.profile.id.value}:${hit.profile.profileText.oneLineForRuntimeMemoryLog(120)}"
            is MemoryStore.SearchHit.EpisodeHit -> "episode:${hit.episode.id.value}:${hit.episode.lesson.oneLineForRuntimeMemoryLog(120)}"
            is MemoryStore.SearchHit.RunHit -> "run:${hit.run.id.value}:${hit.run.runType.name}:${hit.run.summary.oneLineForRuntimeMemoryLog(100)}"
        }
    }
}

private fun List<MemoryStore.SearchHit>.toTraceHits(limit: Int = Int.MAX_VALUE): List<MemoryReadTrace.Hit> =
    take(limit).map { it.toTraceHit() }

private fun RuntimeMemorySourceSafetyResult.toTraceSourceSafety(): MemoryReadTrace.SourceSafety =
    MemoryReadTrace.SourceSafety(
        suppressedSources = suppressedSourceHits.toTraceHits(),
        restoredTypedHits = restoredTypedHits.toTraceHits(),
    )

private fun List<MemoryReadSelectionResult.Decision>.toTraceSelectorDecisions(
    candidateHits: List<MemoryStore.SearchHit>,
): List<MemoryReadTrace.SelectorDecision> {
    val candidateSummaryByRef = candidateHits
        .associateBy { it.toItemRef() }
        .mapValues { it.value.toTraceHit().summary }

    return map { decision ->
        MemoryReadTrace.SelectorDecision(
            ref = decision.ref,
            selected = decision.selected,
            rank = decision.rank,
            summary = candidateSummaryByRef[decision.ref].orEmpty(),
            reason = decision.reason.oneLineForRuntimeMemoryLog(220),
        )
    }
}

private fun MemoryStore.SearchHit.toTraceHit(): MemoryReadTrace.Hit =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = source.contentText.oneLineForRuntimeMemoryLog(220),
        )

        is MemoryStore.SearchHit.EntityHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = "${entity.entityType.name}:${entity.canonicalName}".oneLineForRuntimeMemoryLog(220),
        )

        is MemoryStore.SearchHit.ClaimHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = claim.normalizedText.oneLineForRuntimeMemoryLog(220),
            predicate = claim.predicate,
            status = claim.status.name,
        )

        is MemoryStore.SearchHit.NoteHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = "${note.title}: ${note.summary}".oneLineForRuntimeMemoryLog(220),
            status = note.status.name,
        )

        is MemoryStore.SearchHit.TaskHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = "${task.status.name}: ${task.title}".oneLineForRuntimeMemoryLog(220),
            status = task.status.name,
        )

        is MemoryStore.SearchHit.ProfileHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = profile.profileText.oneLineForRuntimeMemoryLog(220),
        )

        is MemoryStore.SearchHit.EpisodeHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = episode.lesson.oneLineForRuntimeMemoryLog(220),
        )

        is MemoryStore.SearchHit.RunHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = run.summary.oneLineForRuntimeMemoryLog(220),
        )
    }

private fun List<MemoryReadTrace.Hit>.summaryForRuntimeMemoryTraceLog(): String =
    if (isEmpty()) {
        "none"
    } else {
        take(8).joinToString("|") {
            "${it.ref.type.name.lowercase()}:${it.ref.id}:${it.predicate.orEmpty()}:${it.summary.oneLineForRuntimeMemoryLog(120)}"
        }
    }

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.totalDebugLimit(): Int =
    listOf(claims, notes, tasks, sources, episodes).filter { it > 0 }.sum().takeIf { it > 0 } ?: 0

private fun String.oneLineForRuntimeMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
