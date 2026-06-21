package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
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
                "mode=${plan.answerMode.name} coverage=${plan.coverageMode.name} core=${plan.coreBlocks.joinToString { it.name }} " +
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
            selectorTrace = retrieved.selectorTrace,
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
        val targetEntities = resolveTargetEntitiesForRead(request, plan)
        val includeHistoricalTypedMemory = request.targetAsksForHistoricalTypedMemory(plan)
        val queryEmbeddings = mutableMapOf<String, MemoryStore.SearchEmbedding?>()
        val hits = mutableListOf<MemoryStore.SearchHit>()
        hits += targetEntities.hits
        if (targetEntities.hits.isNotEmpty()) {
            searchSteps += MemoryReadTrace.SearchStep(
                stage = "target_entities",
                query = request.entityResolutionText(plan),
                scope = MemoryStore.SearchScope.ENTITIES.name,
                requestedLimit = 4,
                rawCount = targetEntities.hits.size,
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

                MemoryReadPlan.CoreBlock.ACTION_ITEMS -> {
                    val taskHits = store.search(
                        MemoryStore.SearchRequest(
                            query = request.targetQueryText(),
                            namespace = request.namespace,
                            scopes = setOf(MemoryStore.SearchScope.ACTION_ITEMS),
                            filters = MemoryStore.SearchFilters(
                                actionItemStatuses = setOf(
                                    MemoryActionItem.Status.OPEN,
                                    MemoryActionItem.Status.IN_PROGRESS,
                                    MemoryActionItem.Status.BLOCKED,
                                ),
                            ).withTargetEntityIds(MemorySemanticType.ACTION_ITEM, targetEntities.filterEntityIds),
                            embedding = queryEmbedding(request.targetQueryText(), queryEmbeddings),
                            limit = plan.retrievalBudget.tasksLimit(default = 2),
                        )
                    )
                    hits += taskHits
                    searchSteps += MemoryReadTrace.SearchStep(
                        stage = "core:${coreBlock.name}",
                        query = request.targetQueryText(),
                        scope = MemoryStore.SearchScope.ACTION_ITEMS.name,
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
            val searchFilters = retrievalRequest.memoryType.defaultFilters(includeHistoricalTypedMemory)
                .withTargetEntityIds(retrievalRequest.memoryType, targetEntities.filterEntityIds)

            val scopedRawRequestHits = store.search(
                MemoryStore.SearchRequest(
                    query = searchQuery,
                    namespace = request.namespace,
                    scopes = setOf(scope),
                    filters = searchFilters,
                    embedding = queryEmbedding(searchQuery, queryEmbeddings),
                    limit = searchLimit,
                )
            )
            val rawRequestHits = scopedRawRequestHits
                .withRelaxedNoteEntityFilterCandidates(
                    request = request,
                    searchQuery = searchQuery,
                    scope = scope,
                    searchFilters = searchFilters,
                    searchLimit = searchLimit,
                    queryEmbeddings = queryEmbeddings,
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
                    "relaxedEntityFilter=${rawRequestHits.size - scopedRawRequestHits.size} " +
                    "currentThreadSourcesDropped=${rawRequestHits.countCurrentThreadSources(request)} " +
                    "sourcePolicy=${requestSourceSelection.summaryForLog()} top=${requestHits.summaryForRuntimeMemoryLog()}"
            }
        }

        if (plan.shouldSweepCompleteSetSources()) {
            val sourceSweepLimit = plan.completeSetSourceSweepLimit()
            val sourceSweepSearchLimit = (sourceSweepLimit + request.threadContext.messages.size + 4).coerceAtMost(50)
            val rawSourceSweepHits = store.search(
                MemoryStore.SearchRequest(
                    query = "",
                    namespace = request.namespace,
                    scopes = setOf(MemoryStore.SearchScope.SOURCES),
                    filters = MemoryStore.SearchFilters(),
                    limit = sourceSweepSearchLimit,
                )
            )
            val sourceSweepSelection = rawSourceSweepHits
                .excludeCurrentThreadSources(request)
                .applySourceRetrievalPolicyFor(MemorySemanticType.SOURCE)
            val sourceSweepHits = sourceSweepSelection.hits
                .sortedForRuntimeMemoryRead()
                .take(sourceSweepLimit)
            hits += sourceSweepHits
            searchSteps += MemoryReadTrace.SearchStep(
                stage = "coverage:${MemorySemanticType.SOURCE.name}",
                query = "",
                scope = MemoryStore.SearchScope.SOURCES.name,
                requestedLimit = sourceSweepLimit,
                rawCount = rawSourceSweepHits.size,
                candidateCount = sourceSweepSelection.hits.size,
                selectedCount = sourceSweepHits.size,
                rawTopHits = rawSourceSweepHits.toTraceHits(limit = 5),
                selectedTopHits = sourceSweepHits.toTraceHits(limit = 5),
            )
            log.info {
                "Memory read complete-set source sweep: namespace=${request.namespace.value} " +
                    "limit=$sourceSweepLimit searchLimit=$sourceSweepSearchLimit rawHits=${rawSourceSweepHits.size} " +
                    "hits=${sourceSweepHits.size} sourcePolicy=${sourceSweepSelection.summaryForLog()} " +
                    "top=${sourceSweepHits.summaryForRuntimeMemoryLog()}"
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
            val sourceFallbackHits = sourceFallbackSelection.hits
                .sortedForRuntimeMemoryRead()
                .take(sourceFallbackLimit)
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
            .sortedForRuntimeMemoryRead(plan)
        val sourceSelection = MemorySourceRetrievalPolicy.apply(
            hits = distinctHits,
            useCase = plan.sourceRetrievalUseCase(),
        )
        val activeTypedRefsBeforeSourceSupport = sourceSelection.hits.currentTruthBearingTypedRefs()
        val sourceTypedSupportHits = sourceSelection.hits
            .withTypedEvidenceSupport(request, plan)
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
            .enforceBudget(plan, expandForSelector = true)
        val rawSelectorCandidateHits = budgetedHits
            .withTypedEvidenceSupport(request, plan)
            .enforceBudget(plan, expandForSelector = true)
            .sortedForRuntimeMemoryRead(plan)
        val selectorSnapshot = rawSelectorCandidateHits.toReadPartialSnapshot(request.namespace)
        val selectorCandidateHits = rawSelectorCandidateHits
            .filterNot { !includeHistoricalTypedMemory && it.isInactiveTypedHitForRead() }
            .sortedForRuntimeMemoryRead(plan)
        val selectionResult = selector.select(
            MemoryReadSelectionRequest(
                readRequest = request,
                plan = plan,
                candidateHits = selectorCandidateHits,
                snapshot = selectorSnapshot,
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
        val selectorDecisions = selectionResult.decisions
            .filterNot { decision -> decision.ref in coreProfileSelection.addedRefs }
            .let { decisions -> decisions + coreProfileSelection.decisions }
        val selectorSelectedRefs = selectorDecisions
            .filter { it.selected }
            .mapTo(mutableSetOf()) { it.ref }
        val selectedHitsBeforeSafety = coreProfileSelection.hits.enforceBudget(
            plan = plan,
            protectedRefs = selectorSelectedRefs,
        )
        val sourceFilteredHits = selectedHitsBeforeSafety.filterNonRequiredSourcesWhenTypedMemoryAnswers(
            plan = plan,
            protectedRefs = selectorSelectedRefs,
        )
        if (sourceFilteredHits.changed) {
            log.info {
                "Memory read source selection pruned: namespace=${request.namespace.value} " +
                    "mode=${plan.answerMode.name} requireFallback=${plan.requireEvidenceFallback} " +
                    "droppedSources=${sourceFilteredHits.droppedSources.size} " +
                    "droppedSourceIds=${sourceFilteredHits.droppedSources.joinToString("|") { it.source.id.value }}"
            }
        }
        val selectedSourceSafety = sourceFilteredHits.hits.applyActiveTypedMemorySourceSafety(
            plan = plan,
            includeHistoricalTypedMemory = includeHistoricalTypedMemory,
            snapshot = selectorSnapshot,
            candidateHits = selectorCandidateHits,
            protectedRefs = selectorSelectedRefs,
        )
        val selectedSourceTypedSupport = selectedSourceSafety.hits.withSelectedSourceTypedEvidenceSupport(
            request = request,
            plan = plan,
            includeHistoricalTypedMemory = includeHistoricalTypedMemory,
        )
        val sourceSafety = selectedSourceSafety.copy(
            hits = selectedSourceTypedSupport.hits,
            restoredTypedHits = (selectedSourceSafety.restoredTypedHits + selectedSourceTypedSupport.addedHits)
                .distinctBy { it.toItemRef() },
        )
        if (sourceSafety.changed) {
            log.info {
                "Memory read source freshness guard: namespace=${request.namespace.value} " +
                    "suppressedSources=${sourceSafety.suppressedSourceIds.size} " +
                    "suppressedSourceIds=${sourceSafety.suppressedSourceIds.joinToString { it.value }} " +
                    "restoredTypedRefs=${sourceSafety.restoredTypedRefs.joinToString { "${it.type.name.lowercase()}:${it.id}" }}"
            }
        }
        val selectedHits = sourceSafety.hits.sortedForRuntimeMemoryRead()
        val evidenceHydratedHits = hydrateEvidenceSources(request, plan, selectedHits, selectorSnapshot)
            .sortedForRuntimeMemoryRead()
        val entityHydratedHits = hydrateLinkedEntities(request, evidenceHydratedHits)
            .sortedForRuntimeMemoryRead()
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
            selectorTrace = selectionResult.selectorTrace,
            sourceSafety = sourceSafety,
        )
    }

    private suspend fun resolveTargetEntitiesForRead(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
    ): RuntimeMemoryTargetEntities {
        val query = request.entityResolutionText(plan)
        if (query.isBlank()) return RuntimeMemoryTargetEntities(emptyList())

        val queryHits = store.search(
            MemoryStore.SearchRequest(
                query = query,
                namespace = request.namespace,
                scopes = setOf(MemoryStore.SearchScope.ENTITIES),
                limit = 8,
            )
        ).filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .mapNotNull { hit ->
                hit.entity.entityResolutionScore(query.normalizedEntityResolutionText())?.let { score ->
                    MemoryStore.SearchHit.EntityHit(hit.entity, score)
                }
            }
            .sortedWith(
                compareByDescending<MemoryStore.SearchHit.EntityHit> { it.score }
                    .thenBy { it.entity.entityType.name }
                    .thenBy { it.entity.normalizedName }
                    .thenBy { it.entity.canonicalName }
                    .thenBy { it.entity.id.value }
            )
        val subjectHits = queryHits.filter { it.entity.entityType.isSubjectAnchorType() }
        val profileAnchorHits = if (plan.requestsProfileContext() && query.hasFirstPersonSingularReference()) {
            store.search(
                MemoryStore.SearchRequest(
                    query = "user profile preferences constraints",
                    namespace = request.namespace,
                    scopes = setOf(MemoryStore.SearchScope.ENTITIES),
                    limit = 8,
                )
            ).filterIsInstance<MemoryStore.SearchHit.EntityHit>()
                .filter { it.entity.entityType == MemoryEntity.Type.USER }
                .take(1)
        } else {
            emptyList()
        }
        val hits = (profileAnchorHits + subjectHits.ifEmpty { queryHits })
            .distinctBy { it.entity.id }
            .take(4)

        return RuntimeMemoryTargetEntities(hits)
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

    private suspend fun List<MemoryStore.SearchHit>.withRelaxedNoteEntityFilterCandidates(
        request: MemoryReadRequest,
        searchQuery: String,
        scope: MemoryStore.SearchScope,
        searchFilters: MemoryStore.SearchFilters,
        searchLimit: Int,
        queryEmbeddings: MutableMap<String, MemoryStore.SearchEmbedding?>,
    ): List<MemoryStore.SearchHit> {
        if (scope != MemoryStore.SearchScope.NOTES) return this
        if (searchFilters.entityIds.isEmpty()) return this
        if (size >= searchLimit) return this

        val relaxedHits = store.search(
            MemoryStore.SearchRequest(
                query = searchQuery,
                namespace = request.namespace,
                scopes = setOf(scope),
                filters = searchFilters.copy(entityIds = emptySet()),
                embedding = queryEmbedding(searchQuery, queryEmbeddings),
                limit = searchLimit,
            )
        )
        if (relaxedHits.isEmpty()) return this

        val seenRefs = mapTo(mutableSetOf()) { it.toItemRef() }
        val extraHits = relaxedHits
            .filterNot { it.toItemRef() in seenRefs }
            .filter { it.isDocumentSectionNoteHit() }
        if (extraHits.isEmpty()) return this

        log.info {
            "Memory read relaxed note entity filter: namespace=${request.namespace.value} " +
                "query=${searchQuery.oneLineForRuntimeMemoryLog(120)} scoped=${size} extra=${extraHits.size} " +
                "top=${extraHits.summaryForRuntimeMemoryLog()}"
        }
        return this + extraHits
    }

    private fun MemoryStore.SearchHit.isDocumentSectionNoteHit(): Boolean =
        this is MemoryStore.SearchHit.NoteHit &&
            note.evidenceRefs.any { it.sourceId.value.startsWith("external:document-section:") }

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

        return hits + entityHits.sortedForRuntimeMemoryRead()
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
            .sortedForRuntimeMemoryRead()

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
            .sortedForRuntimeMemoryRead()
    }

    private suspend fun List<MemoryStore.SearchHit>.withTypedEvidenceSupport(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
    ): List<MemoryStore.SearchHit> {
        val sourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .mapTo(mutableSetOf()) { it.source.id }
        if (sourceIds.isEmpty()) return this

        val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
        val supportHits = store.findTypedMemoryByEvidenceSourceIds(request.namespace, sourceIds)
            .filterNot { it.toItemRef() in existingRefs }
            .map { it.withRuntimeEvidenceSupportScore(request, plan) }
            .sortedForRuntimeMemoryRead()
        if (supportHits.isEmpty()) return this

        log.info {
            "Memory read source typed support: namespace=${request.namespace.value} sources=${sourceIds.size} " +
                "support=${supportHits.breakdownForRuntimeMemoryLog()} refs=${supportHits.summaryForRuntimeMemoryLog()}"
        }

        return this + supportHits
    }

    private suspend fun List<MemoryStore.SearchHit>.withSelectedSourceTypedEvidenceSupport(
        request: MemoryReadRequest,
        plan: MemoryReadPlan,
        includeHistoricalTypedMemory: Boolean,
    ): RuntimeMemorySelectedSourceTypedSupportResult {
        if (!plan.shouldRestoreTypedEvidenceForSelectedSources()) {
            return RuntimeMemorySelectedSourceTypedSupportResult(hits = this)
        }

        val sourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .mapTo(mutableSetOf()) { it.source.id }
        if (sourceIds.isEmpty()) return RuntimeMemorySelectedSourceTypedSupportResult(hits = this)

        val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
        val supportHits = store.findTypedMemoryByEvidenceSourceIds(request.namespace, sourceIds)
            .filter { includeHistoricalTypedMemory || !it.isInactiveTypedHitForRead() }
            .filter { includeHistoricalTypedMemory || it.isCurrentTruthBearingTypedHit() }
            .filterNot { it.toItemRef() in existingRefs }
            .map { it.withRuntimeEvidenceSupportScore(request, plan) }
            .sortedForRuntimeMemoryRead(plan)
            .take(READ_SELECTED_SOURCE_TYPED_SUPPORT_LIMIT)
        if (supportHits.isEmpty()) return RuntimeMemorySelectedSourceTypedSupportResult(hits = this)

        log.info {
            "Memory read selected source typed support: namespace=${request.namespace.value} " +
                "sources=${sourceIds.size} support=${supportHits.breakdownForRuntimeMemoryLog()} " +
                "refs=${supportHits.summaryForRuntimeMemoryLog()}"
        }

        return RuntimeMemorySelectedSourceTypedSupportResult(
            hits = this + supportHits,
            addedHits = supportHits,
        )
    }
}

private data class RuntimeMemoryRetrievedHits(
    val hits: List<MemoryStore.SearchHit>,
    val selectorCandidateHits: List<MemoryStore.SearchHit>,
    val selectorDecisions: List<MemoryReadSelectionResult.Decision>,
    val selectorTrace: MemoryReadSelectorTrace,
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

private data class RuntimeMemorySelectedSourceTypedSupportResult(
    val hits: List<MemoryStore.SearchHit>,
    val addedHits: List<MemoryStore.SearchHit> = emptyList(),
)

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
                Coverage mode: ${plan.coverageMode.name}
            """.trimIndent()
        }

        val historicalMemoryInstruction =
            if (request.targetAsksForHistoricalTypedMemory(plan)) {
                "If the user asks about an initial, previous, older, or otherwise historical state, non-current typed memory and older evidence may be the direct answer; compare it with current active memory instead of blindly preferring the current value. If a selected non-current typed claim directly gives the requested historical value, use that exact typed value instead of recomputing from vague or approximate source wording."
            } else {
                null
            }

        return """
            MEMORY-ONLY CONTEXT
            This message is not part of the real conversation and must not be stored as evidence.
            The retrieved memory below was selected for the immediately following user request.
            Treat it as the strongest available remembered context, stronger than guesses, defaults, or general world knowledge.
            Use selected active memory for the answer unless it is clearly irrelevant, insufficient, stale, internally conflicting, or contradicted by the current user message.
            Do not claim that raw sources are verified facts; prefer active claims for facts, notes for rationale, and action items for commitments.
            If raw source wording conflicts with active typed memory, trust the active typed memory for current facts.
            If selected typed memory is incomplete for an exact requested detail, use selected source evidence as fallback evidence; extract only explicit source facts and say memory is insufficient when the source still does not contain the detail.
            For recall of prior assistant recommendations, option lists, generated artifacts, or exact mentioned items, a selected source entry that explicitly satisfies every requested qualifier beats a selected typed claim or note that only matches a weaker adjacent target.
            ${historicalMemoryInstruction.orEmpty()}
            If the user asks for first/second/latest/earliest/ordering, compare explicit dates in retrieved memory before answering. For ordering or comparison between named alternatives, require explicit retrieved evidence for every compared alternative; if one alternative is missing, answer that memory is insufficient instead of ranking the known alternative. A conditional bridge in a selected reason, such as "if X is Y", is not evidence that X is Y.
            If the user asks about a named or relative date, compare that target date with both event dates and source/session dates; do not treat a different explicit date as matching the target date.
            For relative month-count questions such as "two months ago", derive the approximate calendar-offset target from the current/question date and prefer retrieved memory closest to that offset. Do not treat any earlier recent event as matching the requested offset; for example, one month ago is not two months ago when another otherwise relevant retrieved item is near the two-month target.
            For "past weekend", "last week", "yesterday", or other relative-window questions, derive the target interval from the current/question date. An event with its own local cue such as "today" resolves to the source/session date and is outside the window when that date is outside the target interval.
            For relative-duration questions that name an anchor event, such as "how many days/weeks/months ago did X when/at the time Y", compute the interval from event X to the anchor event Y when both dates are selected. Use the current/question date only when no separate anchor event is named or retrieved.
            For chained relative-time questions, combine explicit offsets instead of stopping at the last lexical hop. If memory says X happened N days/weeks/months before anchor Y, and Y happened M days/weeks/months before the question/current date, answer X as approximately N+M days/weeks/months ago when both operands are selected. A lead time such as "three months in advance" or "two weeks before" is not itself an "ago" answer unless its anchor is the question/current date.
            For questions asking how long the user had been doing an activity when an anchor event happened, compute from the explicit start/begin/first-participation date of that activity to the anchor event date when both are selected. Do not add an as-of duration or tenure value to the time between its as-of date and the anchor when selected memory also contains a conflicting explicit start date for the same activity; treat that as-of value as noisy or conflicting context.
            For date-scoped questions, a candidate whose event or source date matches the requested period is stronger than an ACTIVE typed fact from outside the period.
            For date-scoped questions, do not smear one relative date cue across unrelated events in the same source; resolve each event from its own local wording and the source/session date.
            For date-scoped questions, missing date evidence is uncertainty, not contradiction. Compare otherwise matching no-date candidates with explicit in-period and explicit out-of-period candidates instead of treating the no-date candidate as automatically wrong.
            For place-visit questions, treat user-attended venue events such as lectures, guided tours, exhibits, appointments, or behind-the-scenes tours at that venue as visit evidence when the venue and target time match. Prefer the venue/time match over a more literal "visited" or "guided tour" event from a different time period.
            For unqualified current/usual/status questions with conflicting ACTIVE facts, prefer the most recent explicit event or source date as the current answer. Use older scoped facts only when the user asks for that specific scope, such as a particular day, project, person, place, or time period.
            For current-state answers at a later target date, selected dated plans, intentions, or scheduled changes can be a matured current state when they match the same subject and slot, predate the target date, and no selected active memory contradicts completion. If a broad current-location claim and a matured plan or source give a more specific container or location inside the same place, combine them into the most specific location.
            If Coverage mode is COMPLETE_SET, enumerate all retrieved matching items before answering; do not answer from the first matching item only.
            For numeric count/list answers, first form the set of counted items from retrieved memory and make the final number match that set size. If a selected ranked reference is a plausible counted item, include it or explicitly exclude it; do not silently ignore it. An empty counted set is not evidence for zero by itself; answer zero only when retrieved memory explicitly states none/zero for the requested scope or provides a closed complete inventory for that exact scope. Otherwise say memory is insufficient or the requested item was not mentioned, and do not put a concrete zero count in the final answer.
            For project leadership, ownership, or responsibility count/list questions, a plain works_on_project or generic project association claim is not enough by itself. Count explicit responsible_for/lead/led/managed/owned claims, explicit team-leadership evidence, and solo or user-owned project evidence. Count personal/current projects only when retrieved memory says the user owns, leads, is responsible for, or is the sole actor on the project. Exclude research topics, papers, posters, broad interests, and plain "my research" or "working on research" evidence unless leadership, ownership, responsibility, or solo execution is explicit.
            For aggregate total/count questions, count only explicit numeric operands or explicit list items that the retrieved memory places in the requested aggregate. When compatible metric_observation, current_metric_value, or other explicit numeric aggregate operands are selected, they define the counted inventory; do not add separate singular possession or ownership claims as +1 unless memory explicitly states that singular item belongs to the same counted aggregate and is not already covered by a numeric operand. For historical totals across metric_observation items, count every distinct observed operand unless retrieved memory explicitly says one corrects, replaces, retracts, or repeats another same-slot measurement. Different attempt, event, condition, session, source, date, route, difficulty, or measurement context can make same-subject observations separate operands. For current aggregate totals, first find the latest explicit baseline total for the same collection, then apply later explicit additions or removals in chronological order. A direct older current_metric_value is not final when selected later memory explicitly adds or removes an item in the same aggregate.
            For increase, decrease, change, delta, difference, gain, loss, or net-movement questions, compute from compatible explicit numeric operands in retrieved memory. Use a selected baseline/previous value and a selected later/current/final value for the same metric or aggregate even when their scope wording is not identical, as long as retrieved memory does not contradict that they belong to the same measured series. Do not answer "insufficient" solely because the baseline is phrased as an initial/start value and the later value is phrased as an after-period observation.
            For imported-source events with an explicit month/day but no explicit year, past-tense wording, and a same-year normalization that would put the event after the source or question date, treat the inferred year as uncertain. For relative-window aggregate/count questions, count otherwise matching explicit numeric operands instead of excluding them solely as future or outside-window.
            For count/list questions about acquired, kept, used, completed, attended, or otherwise user-attributed items, count evidence-backed variants that satisfy the requested category even when retrieved memory uses a different lifecycle or status verb than the question. Require explicit user attribution and category fit; do not count merely adjacent examples.
            For count/list questions scoped before, prior to, until, or at a commitment/decision about a named target, use the named target as boundary context and exclude the target itself from the prior-alternative counted set unless the question explicitly asks to include the target itself. This remains true even when the named target has its own matching action before the boundary event. Put the target in the excluded set as the boundary target, not in the counted set.
            For replaced/fixed/upgraded functional-slot counts, count paired evidence that an old item was removed, discarded, donated, given away, or got rid of and a new item or upgrade took over the same ordinary function. The new item's source, such as gift, purchase, or existing ownership, is not an exclusion reason by itself.
            For indirect replacement/upgrade evidence, count one functional slot when memory connects a newly acquired, gifted, bought, adopted, or started-using item with removal, donation, give-away, or discard of an older same-role item, even when the source does not use the exact word "replace".
            Treat successor or substitute items as same-role when they serve the same ordinary user function or routine, even if their exact subtype differs. A same-source/session pattern of "newer or more capable item introduced for a routine" plus "older same-domain item removed from inventory" is replacement/upgrade evidence unless memory explicitly says the items are unrelated.
            For acquisition-style count/list questions, count concrete physical or digital items when retrieved memory places that item in the user's acquisition, possession, collection, or use history and the requested category fits. Do not require the exact action verb from the question; do not count mere mentions, interests, recommendations, or assistant-only suggestions.
            For acquisition-style count/list questions, selected ACTIVE "owns" or POSSESSION claims are direct acquisition/possession evidence when the item fits the requested category. The words purchased, bought, downloaded, acquired, or got in a how-many/list question are lifecycle hints, not transaction-detail requirements by themselves.
            Explicitly ordered, reserved, or preordered concrete items are acquisition evidence when the requested category fits; pending delivery, receipt, or pickup only excludes the item when the user specifically asks for completed receipt, delivery, pickup, or current possession after a contradictory later status.
            First-person evidence of a concrete personal copy or item can satisfy an acquisition-style broad category count even without a transaction verb. Do not use this shortcut when the question asks for purchase/download transaction details such as price, store, payment, download source, or exact date.
            Treat "how many X did I buy/download/acquire/get" as a broad category count unless the user asks for transaction details. For broad category counts, do not require exact subtype words or exact title when ordinary language makes the remembered personal item a member, copy, or instance of the requested category.
            For furniture or furnishing count/list questions, treat large movable household furnishings used for sleeping, seating, storage, surfaces, or workspace functions as category-fitting even when memory names only the concrete subtype. Do not count small decor, textiles, accessories, or decorative covers unless the question asks for them.
            For broad counts of works or content items, a physical or digital carrier, copy, file, or personal item of that work can count as the work itself when user attribution is explicit. The item's material or format is not a separate category requirement unless the question asks for format-specific details.
            For broad counts of music works or releases, a user-owned or downloaded vinyl record, CD, cassette, digital copy, or music download can count as an album/EP/release item unless retrieved memory explicitly identifies it as only a single track, playlist, non-audio merchandise, or the question asks for a narrower format or subtype.
            For broad health-related device count/list questions, count user-attributed monitoring, treatment, assistive, accessibility, therapeutic, and health-support devices when retrieved memory indicates ownership, wearing, reliance, regular use, usage frequency, or usage duration. Do not narrow "health-related" to only tracking or treatment devices unless the user asks for that narrower subtype.
            In count/list questions, broad category labels are category-fit tests, not exact-word qualifiers. Do not exclude a plausible counted item solely because memory names a member, carrier, copy, or concrete instance instead of repeating the category label.
            For broad category counts, a missing exact subtype word is not a contradiction. Exclude a selected user-attributed copy/member/carrier only when retrieved memory gives an explicit conflicting subtype or category, or when the question asks for exact subtype/transaction details.
            For specifically qualified questions, satisfy every explicit qualifier in the question. When different retrieved memories satisfy different parts of the question, do not answer from a partial match; choose the item that satisfies all required qualifiers, or say memory is insufficient/conflicting. Do not answer with a value for a different object, person, role, job title, position, event, route, project, artifact, item, or relationship merely because it is adjacent or similar; a caveat that the qualifier differs is not enough. A shared anchor can bridge retrieved memories only when it explicitly preserves the same fully-qualified target; it cannot weaken or rewrite the target's modifiers. If any requested qualifier is missing or changed, the final answer must be an insufficiency answer, not a concrete adjacent answer with a warning. Do not infer missing academic level, course ownership, job role, job title, position, project identity, artifact type, item ingredient, component, material, feature, participant identity, route, source, owner, medium, or relation from a merely related remembered topic. When refusing because only adjacent or mismatched evidence was retrieved, do not compute or include the mismatched value unless the user explicitly asks about related memories. Do not map an unnamed role or relative to a named person unless retrieved memory explicitly says they are the same person.
            For arithmetic, date-difference, and ordering questions with named operands, every named operand must match the requested object or event. A different named object is a missing operand, not an approximate match; answer that memory is insufficient instead of computing from the mismatched object.
            For formal education duration from one stage to another stage's completion, use the whole retrieved formal education timeline. Include intermediate formal credentials, attendance, transfer, and completion milestones when they bridge the asked span.
            For aggregate questions about events the user participated in, treat explicit user involvement broadly: attended, participated, helped organize, contributed to, or was part of the team can all qualify unless the question explicitly restricts the answer to personally raised, personally paid, or individually performed amounts.
            For category-scoped count/list questions, treat explicit venue, organizer, community, and stated context as category signals. A community-hosted service activity can qualify for that community/category even when the concrete task is volunteering, planning, sorting, packing, or another support action, unless the question asks for a narrower subtype.
            For room or area item count/list questions, category fit includes fixtures, storage, furniture, tools, mats, devices, appliances, and other concrete objects when retrieved memory explicitly places them in that room/area or their ordinary function fits that room/area. Do not narrow a room item category to only fixtures or utensils unless the user says so.
            For replaced/fixed/upgraded household-item counts, count one functional slot when memory says the user fixed it, replaced it with a newer item, got rid of, donated, or gave away the old item as part of an upgrade, or adopted a new item that takes over the old item's function. Do not count both the old item and its replacement unless the user asks for inventory.
            If the user asks for an exact quote, exact wording, source, or when something was said, prefer the complete source text from Retrieved evidence; evidence quote fields are short excerpts and may be incomplete.
            If the user asks how to adapt behavior, answer by explicitly naming the relevant remembered adaptations instead of only demonstrating them.

            Namespace: ${request.namespace.value}
            Answer mode: ${plan.answerMode.name}
            Coverage mode: ${plan.coverageMode.name}
            Require evidence fallback: ${plan.requireEvidenceFallback}

            Retrieved profile:
            ${hits.renderProfiles()}

            Retrieved action items:
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

private fun MemoryReadPlan.requestsProfileContext(): Boolean =
    MemoryReadPlan.CoreBlock.PROFILE in coreBlocks ||
        retrievalRequests.any { it.memoryType == MemorySemanticType.PROFILE }

private fun String.hasFirstPersonSingularReference(): Boolean =
    Regex("""\b(i|me|my|mine|myself)\b""").containsMatchIn(this)

private fun MemoryStore.SearchHit.withRuntimeEvidenceSupportScore(
    request: MemoryReadRequest,
    plan: MemoryReadPlan,
): MemoryStore.SearchHit =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> copy(
            score = runtimeEvidenceSupportScore(
                request = request,
                plan = plan,
                textParts = listOf(
                    claim.normalizedText,
                    claim.contextText.orEmpty(),
                    claim.predicate,
                    claim.predicateFamily.orEmpty(),
                    claim.scope.text,
                ),
            )
        )

        is MemoryStore.SearchHit.NoteHit -> copy(
            score = runtimeEvidenceSupportScore(
                request = request,
                plan = plan,
                textParts = listOf(
                    note.title,
                    note.summary,
                    note.scope.text,
                    note.noteType.name,
                    note.keywords.joinToString(" "),
                    note.tags.joinToString(" "),
                ),
            )
        )

        is MemoryStore.SearchHit.ActionItemHit -> copy(
            score = runtimeEvidenceSupportScore(
                request = request,
                plan = plan,
                textParts = listOf(
                    actionItem.title,
                    actionItem.description.orEmpty(),
                    actionItem.status.name,
                    actionItem.priority.name,
                    actionItem.scope.text,
                ),
            )
        )

        is MemoryStore.SearchHit.EpisodeHit -> copy(
            score = runtimeEvidenceSupportScore(
                request = request,
                plan = plan,
                textParts = listOf(
                    episode.situation,
                    episode.action,
                    episode.result,
                    episode.lesson,
                    episode.tags.joinToString(" "),
                ),
            )
        )

        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.RunHit,
        -> this
    }

private fun runtimeEvidenceSupportScore(
    request: MemoryReadRequest,
    plan: MemoryReadPlan,
    textParts: List<String>,
): Double {
    val queryTerms = request.runtimeEvidenceSupportQuery(plan).runtimeEvidenceSupportTerms()
    if (queryTerms.isEmpty()) return 0.35

    val normalizedText = textParts.joinToString(" ").normalizedEntityResolutionText()
    val textTerms = normalizedText.runtimeEvidenceSupportTerms().toSet()
    val matchedTerms = queryTerms.count { it in textTerms }
    val matchedAnchors = queryTerms.count { it in runtimeEvidenceSupportAnchorTerms && it in textTerms }
    val lexicalScore = matchedTerms.toDouble() / queryTerms.size * 0.45
    val anchorScore = matchedAnchors * 0.22
    val purposeScore = if (normalizedText.hasRuntimeEvidencePurposeSignal()) 0.18 else 0.0
    val negativePenalty = if (normalizedText.hasRuntimeEvidenceNegativeSignal()) 0.18 else 0.0
    return (0.35 + lexicalScore + anchorScore + purposeScore - negativePenalty).coerceIn(0.05, 0.98)
}

private fun MemoryReadRequest.runtimeEvidenceSupportQuery(plan: MemoryReadPlan): String =
    buildList {
        add(targetQueryText())
        plan.retrievalRequests
            .map { it.query }
            .distinct()
            .forEach(::add)
    }.joinToString("\n").trim()

private val runtimeEvidenceSupportStopWords = setOf(
    "about",
    "according",
    "also",
    "and",
    "brief",
    "for",
    "from",
    "how",
    "keep",
    "please",
    "short",
    "that",
    "the",
    "this",
    "what",
    "when",
    "where",
    "which",
    "with",
)

private val runtimeEvidenceSupportAnchorTerms = setOf(
    "action",
    "claim",
    "entity",
    "episode",
    "memory",
    "note",
    "profile",
    "source",
    "task",
)

private fun String.runtimeEvidenceSupportTerms(): List<String> =
    split(" ")
        .map { it.trim() }
        .filter { it.length >= 3 }
        .filterNot { it in runtimeEvidenceSupportStopWords }
        .distinct()

private fun String.hasRuntimeEvidencePurposeSignal(): Boolean =
    listOf(
        " evidence ",
        " layer ",
        " primary ",
        " purpose ",
        " role ",
        " stores ",
        " supports ",
        " used ",
    ).any { phrase -> " $this ".contains(phrase) }

private fun String.hasRuntimeEvidenceNegativeSignal(): Boolean =
    listOf(
        " is not ",
        " not ",
        " never ",
        " without ",
    ).any { phrase -> " $this ".contains(phrase) }

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
    replace(Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})"), " ")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun MemorySemanticType.toSearchScope(): MemoryStore.SearchScope? =
    when (this) {
        MemorySemanticType.CLAIM -> MemoryStore.SearchScope.CLAIMS
        MemorySemanticType.NOTE -> MemoryStore.SearchScope.NOTES
        MemorySemanticType.ACTION_ITEM -> MemoryStore.SearchScope.ACTION_ITEMS
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
        MemoryStore.SearchScope.ACTION_ITEMS,
        MemoryStore.SearchScope.EPISODES,
        -> resultLimit.expandedForSelectorCandidates()

        else -> resultLimit
    }.coerceAtLeast(resultLimit)

private fun MemorySemanticType.defaultFilters(includeHistoricalTypedMemory: Boolean): MemoryStore.SearchFilters =
    when (this) {
        MemorySemanticType.CLAIM -> MemoryStore.SearchFilters(
            claimStatuses = if (includeHistoricalTypedMemory) emptySet() else setOf(MemoryClaim.Status.ACTIVE),
        )

        MemorySemanticType.NOTE -> MemoryStore.SearchFilters(
            noteStatuses = if (includeHistoricalTypedMemory) emptySet() else setOf(MemoryNote.Status.ACTIVE),
        )

        MemorySemanticType.ACTION_ITEM -> MemoryStore.SearchFilters()

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
        MemorySemanticType.ACTION_ITEM,
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

private fun List<MemoryStore.SearchHit>.sortedForRuntimeMemoryRead(): List<MemoryStore.SearchHit> =
    sortedWith(runtimeMemorySearchHitComparator)

private fun List<MemoryStore.SearchHit>.sortedForRuntimeMemoryRead(plan: MemoryReadPlan): List<MemoryStore.SearchHit> =
    sortedWith(
        compareByDescending<MemoryStore.SearchHit> { it.claimPredicatePriority(plan) }
            .then(runtimeMemorySearchHitComparator)
    )

private val runtimeMemorySearchHitComparator: Comparator<MemoryStore.SearchHit> =
    compareByDescending<MemoryStore.SearchHit> { it.score }
        .thenByDescending { it.runtimeImportance() }
        .thenBy { it.runtimeTypeRank() }
        .thenBy { it.runtimeStableSortKey() }
        .thenBy { it.toItemRef().id }

private fun MemoryStore.SearchHit.runtimeImportance(): Int =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.importance
        is MemoryStore.SearchHit.NoteHit -> note.importance
        is MemoryStore.SearchHit.ActionItemHit -> when (actionItem.priority) {
            MemoryActionItem.Priority.HIGH -> 9
            MemoryActionItem.Priority.NORMAL -> 5
            MemoryActionItem.Priority.LOW -> 1
        }
        is MemoryStore.SearchHit.EpisodeHit -> (episode.successScore ?: 0.0).times(10).toInt()
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.RunHit,
        -> 0
    }

private fun MemoryStore.SearchHit.runtimeTypeRank(): Int =
    when (this) {
        is MemoryStore.SearchHit.ProfileHit -> 0
        is MemoryStore.SearchHit.ClaimHit -> 1
        is MemoryStore.SearchHit.NoteHit -> 2
        is MemoryStore.SearchHit.ActionItemHit -> 3
        is MemoryStore.SearchHit.SourceHit -> 4
        is MemoryStore.SearchHit.EpisodeHit -> 5
        is MemoryStore.SearchHit.EntityHit -> 6
        is MemoryStore.SearchHit.RunHit -> 7
    }

private fun MemoryStore.SearchHit.runtimeStableSortKey(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> source.runtimeStableSortKey()
        is MemoryStore.SearchHit.EntityHit -> listOf(
            entity.entityType.name,
            entity.normalizedName,
            entity.canonicalName,
            entity.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.ClaimHit -> listOf(
            claim.predicate,
            claim.normalizedText,
            claim.contextText.orEmpty(),
            claim.scope.runtimeStableSortKey(),
            claim.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.NoteHit -> listOf(
            note.noteType.name,
            note.title,
            note.summary,
            note.scope.runtimeStableSortKey(),
            note.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.ActionItemHit -> listOf(
            actionItem.status.name,
            actionItem.priority.name,
            actionItem.title,
            actionItem.description.orEmpty(),
            actionItem.scope.runtimeStableSortKey(),
            actionItem.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.ProfileHit -> listOf(
            profile.ownerEntityId.value,
            profile.profileText,
            profile.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.EpisodeHit -> listOf(
            episode.situation,
            episode.action,
            episode.result,
            episode.lesson,
            episode.id.value,
        ).joinToString("|")
        is MemoryStore.SearchHit.RunHit -> listOf(
            run.runType.name,
            run.status.name,
            run.summary,
            run.id.value,
        ).joinToString("|")
    }.lowercase()

private fun MemorySource.runtimeStableSortKey(): String =
    when (this) {
        is MemorySource.ChatTurn -> listOf(
            "chat",
            conversationId.value,
            threadId?.value.orEmpty(),
            sourceMessageId?.value.orEmpty(),
            speakerRole.name,
            contentHash,
            id.value,
        ).joinToString("|")
        is MemorySource.ToolOutput -> listOf(
            "tool",
            conversationId?.value.orEmpty(),
            threadId?.value.orEmpty(),
            sourceMessageId?.value.orEmpty(),
            toolName.orEmpty(),
            contentHash,
            id.value,
        ).joinToString("|")
        is MemorySource.ImportedNote -> listOf(
            "imported",
            importRef.orEmpty(),
            contentHash,
            id.value,
        ).joinToString("|")
        is MemorySource.ExternalRecord -> listOf(
            "external",
            recordRef,
            contentHash,
            id.value,
        ).joinToString("|")
    }

private fun MemoryScope.runtimeStableSortKey(): String =
    when (this) {
        is MemoryScope.Global -> listOf("global", text, basis.name)
        is MemoryScope.Project -> listOf("project", projectId.value, text, basis.name)
        is MemoryScope.Conversation -> listOf(
            "conversation",
            conversationId.value,
            projectId?.value.orEmpty(),
            text,
            basis.name,
        )
        is MemoryScope.Entity -> listOf("entity", subjectEntityId.value, text, basis.name)
        is MemoryScope.Environment -> listOf("environment", environment, text, basis.name)
        is MemoryScope.Document -> listOf("document", documentRef, text, basis.name)
    }.joinToString("|")

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
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET || shouldIncludeSourceEvidence()) {
        false
    } else {
        when (answerMode) {
            MemoryReadPlan.AnswerMode.FACTUAL,
            MemoryReadPlan.AnswerMode.ACTION_ITEM,
            -> true

            MemoryReadPlan.AnswerMode.MIXED,
            MemoryReadPlan.AnswerMode.RATIONALE,
            -> false
        }
    }

private fun MemoryStore.SearchHit.isCurrentTruthBearingTypedHit(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.status == MemoryClaim.Status.ACTIVE
        is MemoryStore.SearchHit.NoteHit -> note.status == MemoryNote.Status.ACTIVE
        is MemoryStore.SearchHit.ActionItemHit -> actionItem.status in setOf(
            MemoryActionItem.Status.OPEN,
            MemoryActionItem.Status.IN_PROGRESS,
            MemoryActionItem.Status.BLOCKED,
        )

        is MemoryStore.SearchHit.EpisodeHit -> true
        is MemoryStore.SearchHit.ProfileHit,
        is MemoryStore.SearchHit.EntityHit,
        is MemoryStore.SearchHit.SourceHit,
        is MemoryStore.SearchHit.RunHit,
        -> false
    }

private fun MemoryStore.SearchHit.isInactiveTypedHitForRead(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> claim.status != MemoryClaim.Status.ACTIVE
        is MemoryStore.SearchHit.NoteHit -> note.status != MemoryNote.Status.ACTIVE
        else -> false
    }

private fun MemoryReadRequest.targetAsksForHistoricalTypedMemory(plan: MemoryReadPlan): Boolean =
    buildList {
        add(targetQueryText())
        plan.retrievalRequests.forEach { request ->
            add(request.query)
            add(request.why)
        }
    }.joinToString("\n").hasHistoricalMemoryIntent()

private fun String.hasHistoricalMemoryIntent(): Boolean {
    val normalized = lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .let { " ${it.trim()} " }
    return HISTORICAL_MEMORY_INTENT_PHRASES.any { phrase -> normalized.contains(phrase) }
}

private fun List<MemoryStore.SearchHit>.prioritizeForReadRequest(
    retrievalRequest: MemoryReadPlan.RetrievalRequest,
): List<MemoryStore.SearchHit> {
    if (retrievalRequest.memoryType != MemorySemanticType.CLAIM) return sortedForRuntimeMemoryRead()
    if (retrievalRequest.preferredClaimPredicates.isEmpty() && retrievalRequest.deprioritizedClaimPredicates.isEmpty()) {
        return sortedForRuntimeMemoryRead()
    }

    return sortedWith(
        compareByDescending<MemoryStore.SearchHit> { it.claimPredicatePriority(retrievalRequest) }
            .thenByDescending { it.score }
            .thenByDescending { it.runtimeImportance() }
            .thenBy { it.runtimeTypeRank() }
            .thenBy { it.runtimeStableSortKey() }
            .thenBy { it.toItemRef().id }
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

private fun MemoryReadPlan.shouldSweepCompleteSetSources(): Boolean =
    needMemory &&
        coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET &&
        (requireEvidenceFallback || retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE })

private fun MemoryReadPlan.completeSetSourceSweepLimit(): Int =
    (retrievalBudget.sources.takeIf { it > 0 } ?: 6)
        .coerceIn(4, 12)

private fun MemoryStore.SearchHit.isAnswerCandidateForRecall(): Boolean =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit,
        is MemoryStore.SearchHit.NoteHit,
        is MemoryStore.SearchHit.ActionItemHit,
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
        plan.retrievalRequests
            .map { it.query }
            .distinct()
            .forEach(::add)
        add(targetQueryText())
    }.joinToString("\n").trim()

private fun MemoryStore.SearchHit.claimPredicatePriority(retrievalRequest: MemoryReadPlan.RetrievalRequest): Int {
    if (this !is MemoryStore.SearchHit.ClaimHit) return 0

    val predicates = claim.predicateNamesForReadPriority()
    val preferred = retrievalRequest.preferredClaimPredicates.mapTo(mutableSetOf()) { it.lowercase() }
    val deprioritized = retrievalRequest.deprioritizedClaimPredicates.mapTo(mutableSetOf()) { it.lowercase() }
    return when {
        predicates.any { it in preferred } -> 2
        predicates.any { it in deprioritized } -> -1
        else -> 0
    }
}

private fun MemoryStore.SearchHit.claimPredicatePriority(plan: MemoryReadPlan): Int {
    if (this !is MemoryStore.SearchHit.ClaimHit) return 0

    val claimRequests = plan.retrievalRequests.filter { it.memoryType == MemorySemanticType.CLAIM }
    if (claimRequests.isEmpty()) return 0

    return claimRequests.maxOf { claimPredicatePriority(it) }
}

private fun MemoryClaim.predicateNamesForReadPriority(): Set<String> =
    buildSet {
        add(predicate.lowercase())
        predicateFamily?.lowercase()?.let(::add)
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
        MemorySemanticType.ACTION_ITEM -> actionItems
        MemorySemanticType.SOURCE -> sources
        MemorySemanticType.EPISODE -> episodes
        MemorySemanticType.PROFILE -> profilesLimit()
        MemorySemanticType.ENTITY -> 4
    }.takeIf { it > 0 }

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.tasksLimit(default: Int): Int =
    actionItems.takeIf { it > 0 } ?: default

private fun com.gromozeka.domain.model.memory.MemoryRetrievalBudget.profilesLimit(): Int = 2

private fun List<MemoryStore.SearchHit>.enforceBudget(
    plan: MemoryReadPlan,
    expandForSelector: Boolean = false,
    protectedRefs: Set<MemoryItemRef> = emptySet(),
): List<MemoryStore.SearchHit> {
    val profiles = mutableListOf<MemoryStore.SearchHit.ProfileHit>()
    val claims = mutableListOf<MemoryStore.SearchHit.ClaimHit>()
    val notes = mutableListOf<MemoryStore.SearchHit.NoteHit>()
    val actionItems = mutableListOf<MemoryStore.SearchHit.ActionItemHit>()
    val sources = mutableListOf<MemoryStore.SearchHit.SourceHit>()
    val episodes = mutableListOf<MemoryStore.SearchHit.EpisodeHit>()
    val entities = mutableListOf<MemoryStore.SearchHit.EntityHit>()

    for (hit in this) {
        when (hit) {
            is MemoryStore.SearchHit.ProfileHit -> profiles += hit
            is MemoryStore.SearchHit.ClaimHit -> claims += hit
            is MemoryStore.SearchHit.NoteHit -> notes += hit
            is MemoryStore.SearchHit.ActionItemHit -> actionItems += hit
            is MemoryStore.SearchHit.SourceHit -> sources += hit
            is MemoryStore.SearchHit.EpisodeHit -> episodes += hit
            is MemoryStore.SearchHit.EntityHit -> entities += hit
            is MemoryStore.SearchHit.RunHit -> Unit
        }
    }

    return buildList {
        addAll(profiles.sortedForRuntimeMemoryRead().takeWithProtectedRefs(plan.retrievalBudget.profilesLimit(), protectedRefs))
        addAll(
            claims.sortedForRuntimeMemoryRead(plan).takeWithProtectedRefs(
                limit = plan.retrievalBudget.claims.budgetLimit(default = 6, expandForSelector = expandForSelector),
                protectedRefs = protectedRefs,
            )
        )
        addAll(
            notes.sortedForRuntimeMemoryRead().takeWithProtectedRefs(
                limit = plan.retrievalBudget.notes.budgetLimit(default = 4, expandForSelector = expandForSelector),
                protectedRefs = protectedRefs,
            )
        )
        addAll(
            actionItems.sortedForRuntimeMemoryRead().takeWithProtectedRefs(
                limit = plan.retrievalBudget.actionItems.budgetLimit(default = 3, expandForSelector = expandForSelector),
                protectedRefs = protectedRefs,
            )
        )
        addAll(
            sources.sortedForRuntimeMemoryRead().takeWithProtectedRefs(
                limit = plan.retrievalBudget.sources.budgetLimit(default = 3, expandForSelector = expandForSelector),
                protectedRefs = protectedRefs,
            )
        )
        addAll(
            episodes.sortedForRuntimeMemoryRead().takeWithProtectedRefs(
                limit = plan.retrievalBudget.episodes.budgetLimit(default = 2, expandForSelector = expandForSelector),
                protectedRefs = protectedRefs,
            )
        )
        addAll(entities.sortedForRuntimeMemoryRead().takeWithProtectedRefs(4, protectedRefs))
    }
}

private fun <T : MemoryStore.SearchHit> List<T>.takeWithProtectedRefs(
    limit: Int,
    protectedRefs: Set<MemoryItemRef>,
): List<T> {
    if (protectedRefs.isEmpty()) return take(limit)

    val protectedHits = filter { it.toItemRef() in protectedRefs }
    val protectedHitRefs = protectedHits.mapTo(mutableSetOf()) { it.toItemRef() }
    val remainingLimit = (limit - protectedHits.size).coerceAtLeast(0)

    return (protectedHits + filterNot { it.toItemRef() in protectedHitRefs }.take(remainingLimit))
        .distinctBy { it.toItemRef() }
}

private fun Int.budgetLimit(default: Int, expandForSelector: Boolean): Int {
    val requested = takeIf { it > 0 } ?: return default
    return requested.maybeExpandForSelector(expandForSelector)
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
    filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
        .joinToString("\n") {
            "- action_item ${it.actionItem.id.value} [${it.actionItem.status.name}]: title=\"${it.actionItem.title}\"; description=\"${it.actionItem.description ?: "none"}\""
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
    protectedRefs: Set<MemoryItemRef> = emptySet(),
): RuntimeMemorySourcePruningResult {
    if (plan.coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    if (plan.shouldRenderEvidenceInPrompt()) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    val shouldPrune = when (plan.answerMode) {
        MemoryReadPlan.AnswerMode.FACTUAL,
        MemoryReadPlan.AnswerMode.ACTION_ITEM,
        -> true

        MemoryReadPlan.AnswerMode.MIXED,
        MemoryReadPlan.AnswerMode.RATIONALE,
        -> false
    }
    if (!shouldPrune || none { it.isCurrentTruthBearingTypedHit() }) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    val dropped = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .filterNot { it.toItemRef() in protectedRefs }
    if (dropped.isEmpty()) {
        return RuntimeMemorySourcePruningResult(hits = this)
    }

    return RuntimeMemorySourcePruningResult(
        hits = filterNot { it is MemoryStore.SearchHit.SourceHit && it.toItemRef() !in protectedRefs },
        droppedSources = dropped,
    )
}

private fun List<MemoryStore.SearchHit>.renderClaims(includeEvidence: Boolean): String =
    filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
        .joinToString("\n") {
            val context = it.claim.contextText
                ?.trim()
                ?.takeIf { contextText -> contextText.isNotBlank() && contextText != it.claim.normalizedText }
                ?.truncateForRuntimeMemoryPrompt(MAX_RUNTIME_CLAIM_CONTEXT_CHARS)
                ?.let { contextText -> "; context=\"$contextText\"" }
                .orEmpty()
            val evidence = if (includeEvidence) "; evidence=${it.claim.evidenceRefs.renderEvidenceRefs()}" else ""
            val policy = it.claim.predicatePolicy
                ?.let { policy ->
                    " semantics=${policy.semanticKinds.joinToString("|") { semanticKind -> semanticKind.name }} aggregate_effect=${policy.aggregateEffect.name}"
                }
                .orEmpty()
            "- claim ${it.claim.id.value} [${it.claim.status.name}] ${it.claim.predicate} family=${it.claim.predicateFamily ?: "unknown"}$policy: ${it.claim.normalizedText}; scope=${it.claim.scope.text}$context$evidence"
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

private fun String.queryFocusedExcerptForMemoryPrompt(query: String, maxChars: Int = 4_000): String =
    RuntimeMemorySourceExcerpt.queryFocused(
        text = this,
        query = query,
        maxChars = maxChars,
        fullTextMaxChars = MAX_RUNTIME_FULL_SOURCE_CHARS,
    )

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
        is MemoryStore.SearchHit.ActionItemHit -> MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }

private fun MemoryStore.SearchHit.isEmptyProfileHit(): Boolean =
    this is MemoryStore.SearchHit.ProfileHit &&
        profile.profileText.contains("No active profile-synced memory.", ignoreCase = true)

private fun MemoryReadPlan.evidenceHydrationSourceLimit(): Int =
    if (coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET && shouldIncludeSourceEvidence()) {
        retrievalBudget.sources.takeIf { it > 0 } ?: 6
    } else {
        when (answerMode) {
            MemoryReadPlan.AnswerMode.RATIONALE -> retrievalBudget.sources.takeIf { it > 0 } ?: 4
            MemoryReadPlan.AnswerMode.MIXED -> if (shouldIncludeSourceEvidence()) retrievalBudget.sources.takeIf { it > 0 } ?: 2 else 0
            MemoryReadPlan.AnswerMode.FACTUAL,
            MemoryReadPlan.AnswerMode.ACTION_ITEM,
            -> if (shouldIncludeSourceEvidence()) retrievalBudget.sources.takeIf { it > 0 } ?: 2 else 0
        }
    }

private fun MemoryReadPlan.shouldIncludeSourceEvidence(): Boolean =
    requireEvidenceFallback || retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE }

private fun MemoryReadPlan.shouldRenderEvidenceInPrompt(): Boolean =
    evidenceHydrationSourceLimit() > 0

private fun MemoryReadPlan.shouldRestoreTypedEvidenceForSelectedSources(): Boolean =
    shouldRenderEvidenceInPrompt() && (
        coverageMode == MemoryReadPlan.CoverageMode.COMPLETE_SET ||
            answerMode == MemoryReadPlan.AnswerMode.FACTUAL ||
            answerMode == MemoryReadPlan.AnswerMode.ACTION_ITEM
        )

private fun List<MemoryStore.SearchHit>.keepLinkedEvidenceSources(
    evidenceSourceIds: List<MemorySource.Id>,
): List<MemoryStore.SearchHit> {
    if (evidenceSourceIds.isEmpty()) return this
    val selectedSourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    val allowedSourceIds = evidenceSourceIds.toSet() + selectedSourceIds
    return filter { hit ->
        hit !is MemoryStore.SearchHit.SourceHit || hit.source.id in allowedSourceIds
    }
}

private fun List<MemoryStore.SearchHit>.toReadPartialSnapshot(namespace: MemoryNamespace): MemoryNamespaceSnapshot =
    MemoryNamespaceSnapshot(
        predicateDefinitions = emptyList(),
        sources = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .map { it.source }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        runs = filterIsInstance<MemoryStore.SearchHit.RunHit>()
            .map { it.run }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        entities = filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .map { it.entity }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        claims = filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        notes = filterIsInstance<MemoryStore.SearchHit.NoteHit>()
            .map { it.note }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        actionItems = filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
            .map { it.actionItem }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        profiles = filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
            .map { it.profile }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
        episodes = filterIsInstance<MemoryStore.SearchHit.EpisodeHit>()
            .map { it.episode }
            .filter { it.namespace == namespace }
            .distinctBy { it.id },
    )

private fun List<MemoryStore.SearchHit>.applyActiveTypedMemorySourceSafety(
    plan: MemoryReadPlan,
    includeHistoricalTypedMemory: Boolean,
    snapshot: MemoryNamespaceSnapshot,
    candidateHits: List<MemoryStore.SearchHit>,
    protectedRefs: Set<MemoryItemRef> = emptySet(),
): RuntimeMemorySourceSafetyResult {
    val selectedSourceIds = filterIsInstance<MemoryStore.SearchHit.SourceHit>()
        .mapTo(mutableSetOf()) { it.source.id }
    if (selectedSourceIds.isEmpty()) return RuntimeMemorySourceSafetyResult(hits = this)

    val protectsExplicitSourceEvidence = plan.shouldRenderEvidenceInPrompt() && when (plan.answerMode) {
        MemoryReadPlan.AnswerMode.FACTUAL,
        MemoryReadPlan.AnswerMode.ACTION_ITEM,
        -> true

        MemoryReadPlan.AnswerMode.MIXED,
        MemoryReadPlan.AnswerMode.RATIONALE,
        -> false
    }
    val protectedSourceIds = if (protectsExplicitSourceEvidence) {
        protectedRefs
            .filter { it.type == MemoryItemRef.Type.SOURCE }
            .mapTo(mutableSetOf()) { MemorySource.Id(it.id) }
    } else {
        emptySet()
    }
    val replacementSourceIds = selectedSourceIds.intersect(snapshot.sourceIdsWithActiveTypedReplacement())
    if (replacementSourceIds.isEmpty()) return RuntimeMemorySourceSafetyResult(hits = this)
    val suppressedSourceIds = replacementSourceIds.filterNotTo(mutableSetOf()) { it in protectedSourceIds }

    val existingRefs = mapTo(mutableSetOf()) { it.toItemRef() }
    val restoredHits = snapshot.activeTypedReplacementHitsForSources(
        sourceIds = replacementSourceIds,
        candidateHits = candidateHits,
    ).filterNot { it.toItemRef() in existingRefs }
    val suppressRawSources =
        plan.coverageMode != MemoryReadPlan.CoverageMode.COMPLETE_SET && !includeHistoricalTypedMemory
    val suppressedSourceHits = if (suppressRawSources) {
        filterIsInstance<MemoryStore.SearchHit.SourceHit>()
            .filter { it.source.id in suppressedSourceIds }
    } else {
        emptyList()
    }
    val repairedHits = if (suppressRawSources) {
        filterNot { hit ->
            hit is MemoryStore.SearchHit.SourceHit && hit.source.id in suppressedSourceIds
        } + restoredHits
    } else {
        this + restoredHits
    }

    return RuntimeMemorySourceSafetyResult(
        hits = repairedHits,
        suppressedSourceHits = suppressedSourceHits,
        restoredTypedHits = restoredHits,
    )
}

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
        is MemoryStore.SearchHit.ActionItemHit -> actionItem.evidenceRefs
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
        is MemoryStore.SearchHit.ActionItemHit -> (listOfNotNull(actionItem.ownerEntityId, actionItem.assigneeEntityId) + actionItem.relatedEntityIds).distinct()
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

private const val MAX_RUNTIME_CLAIM_CONTEXT_CHARS = 700
private const val MAX_RUNTIME_FULL_SOURCE_CHARS = 12_000
private const val READ_SELECTED_SOURCE_TYPED_SUPPORT_LIMIT = 16

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
            is MemoryStore.SearchHit.ActionItemHit -> "actionItem"
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
            is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${hit.actionItem.id.value}:${hit.actionItem.status.name}:${hit.actionItem.title.oneLineForRuntimeMemoryLog(100)}"
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
            evidenceSourceIds = listOf(source.id),
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
            evidenceSourceIds = claim.evidenceRefs.map { it.sourceId }.distinct(),
        )

        is MemoryStore.SearchHit.NoteHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = "${note.title}: ${note.summary}".oneLineForRuntimeMemoryLog(220),
            status = note.status.name,
            evidenceSourceIds = note.evidenceRefs.map { it.sourceId }.distinct(),
        )

        is MemoryStore.SearchHit.ActionItemHit -> MemoryReadTrace.Hit(
            ref = toItemRef(),
            score = score,
            summary = "${actionItem.status.name}: ${actionItem.title}".oneLineForRuntimeMemoryLog(220),
            status = actionItem.status.name,
            evidenceSourceIds = actionItem.evidenceRefs.map { it.sourceId }.distinct(),
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
            evidenceSourceIds = episode.evidenceRefs.map { it.sourceId }.distinct(),
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
    listOf(claims, notes, actionItems, sources, episodes).filter { it > 0 }.sum().takeIf { it > 0 } ?: 0

private val HISTORICAL_MEMORY_INTENT_PHRASES = setOf(
    " at first ",
    " back then ",
    " before ",
    " earlier ",
    " earliest ",
    " first ",
    " formerly ",
    " initial ",
    " initially ",
    " originally ",
    " previous ",
    " previously ",
    " prior ",
    " used to ",
)

private fun String.oneLineForRuntimeMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
