package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteService
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimExtractor
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryClaimReconciler
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizer
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteConstructor
import com.gromozeka.domain.model.memory.MemoryNoteReconciliationOp
import com.gromozeka.domain.model.memory.MemoryNoteReconciler
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryProfileUpdater
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemorySourceUsagePolicy
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.MemoryActionItemUpdater
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlanner
import com.gromozeka.domain.model.memory.MemoryWriteRouter
import java.security.MessageDigest
import klog.KLoggers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val memoryRunJson = Json { encodeDefaults = true }
private val memoryRuntimeSearchIdRegex =
    Regex("\\b(project|chat|memory|source|claim|note|actionItem|entity|episode|profile|run):[0-9a-fA-F][A-Za-z0-9._:-]*")

fun interface MemoryClock {
    fun now(): Instant
}

object SystemMemoryClock : MemoryClock {
    override fun now(): Instant = Clock.System.now()
}

interface MemoryIdFactory {
    fun newEntityId(): MemoryEntity.Id
    fun newClaimId(): MemoryClaim.Id
    fun newNoteId(): MemoryNote.Id
    fun newActionItemId(): MemoryActionItem.Id
    fun newEpisodeId(): MemoryEpisode.Id
    fun newRunId(): MemoryRun.Id
}

interface DirectStructuredMemoryWriteMaterializer {
    fun materialize(input: DirectStructuredMemoryWriteMaterialization): MemoryUpdateBatch
}

object NoOpMemoryProfileUpdater : MemoryProfileUpdater {
    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
    ): MemoryUpdateBatch = MemoryUpdateBatch()
}

data class DirectStructuredMemoryWriteMaterialization(
    val request: DirectStructuredMemoryWriteRequest,
    val routeDecision: MemoryRouteDecision,
    val retrievalPlan: MemoryWriteRetrievalPlan?,
    val retrievedHits: List<MemoryStore.SearchHit>,
    val entityOps: List<MemoryEntityCanonicalizationOp>,
    val noteCandidates: List<MemoryNoteCandidate>,
    val rawNoteOps: List<MemoryNoteReconciliationOp>,
    val noteOps: List<MemoryNoteReconciliationOp>,
    val claimCandidates: List<MemoryClaimCandidate>,
    val rawClaimOps: List<MemoryClaimReconciliationOp>,
    val claimOps: List<MemoryClaimReconciliationOp>,
    val rawActionItemOps: List<MemoryActionItemUpdateOp>,
    val actionItemOps: List<MemoryActionItemUpdateOp>,
    val predicateCatalog: MemoryPredicateCatalog,
    val startedAt: Instant,
    val completedAt: Instant,
)

private data class MemoryWriteBranchResults(
    val note: MemoryWriteNoteBranchResult,
    val claim: MemoryWriteClaimBranchResult,
    val actionItem: MemoryWriteActionItemBranchResult,
)

private data class MemoryWriteNoteBranchResult(
    val noteCandidates: List<MemoryNoteCandidate> = emptyList(),
    val rawNoteOps: List<MemoryNoteReconciliationOp> = emptyList(),
    val noteOps: List<MemoryNoteReconciliationOp> = emptyList(),
)

private data class MemoryWriteClaimBranchResult(
    val claimCandidates: List<MemoryClaimCandidate> = emptyList(),
    val rawClaimOps: List<MemoryClaimReconciliationOp> = emptyList(),
    val claimOps: List<MemoryClaimReconciliationOp> = emptyList(),
)

private data class MemoryWriteActionItemBranchResult(
    val rawActionItemOps: List<MemoryActionItemUpdateOp> = emptyList(),
    val actionItemOps: List<MemoryActionItemUpdateOp> = emptyList(),
)

class DirectStructuredMemoryWritePipeline(
    private val store: MemoryStore,
    private val router: MemoryWriteRouter,
    private val retrievalPlanner: MemoryWriteRetrievalPlanner,
    private val entityCanonicalizer: MemoryEntityCanonicalizer,
    private val noteConstructor: MemoryNoteConstructor,
    private val noteReconciler: MemoryNoteReconciler,
    private val claimExtractor: MemoryClaimExtractor,
    private val claimReconciler: MemoryClaimReconciler,
    private val actionItemUpdater: MemoryActionItemUpdater,
    private val materializer: DirectStructuredMemoryWriteMaterializer,
    private val profileUpdater: MemoryProfileUpdater = NoOpMemoryProfileUpdater,
    private val forgetPipeline: ExplicitMemoryForgetPipeline? = null,
    private val embeddingIndexer: MemoryEmbeddingIndexer = NoOpMemoryEmbeddingIndexer,
    private val clock: MemoryClock = SystemMemoryClock,
    branchParallelism: Int = MemoryPipelineParallelism.writeBranchParallelism(),
) : DirectStructuredMemoryWriteService {
    private val log = KLoggers.logger(this)
    private val branchParallelism = MemoryPipelineParallelism.normalizeWriteBranchParallelism(branchParallelism)

    override suspend fun write(request: DirectStructuredMemoryWriteRequest): DirectStructuredMemoryWriteResult {
        require(request.source.namespace == request.namespace) {
            "Source namespace ${request.source.namespace.value} does not match request namespace ${request.namespace.value}"
        }

        log.info {
            "Memory write pipeline start: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "trigger=${request.triggerMode.name} sourceType=${request.source.sourceTypeForLog()} " +
                "parentRun=${request.parentRunId?.value ?: "none"} " +
                "sourceRole=${request.source.sourceRoleForLog()} contentChars=${request.source.contentText.length} " +
                "contentPreview=${request.source.contentText.oneLineForLog(500)}"
        }

        val previouslyCompleted = findCompletedStructuredWriteForSameSource(request)
        if (previouslyCompleted != null) {
            log.info {
                "Memory write pipeline skipped as already processed: namespace=${request.namespace.value} " +
                    "source=${request.source.id.value} previousRun=${previouslyCompleted.run.id.value} " +
                    "runType=${previouslyCompleted.run.runType.name} contentHash=${request.source.contentHash}"
            }

            return DirectStructuredMemoryWriteResult(
                sourceBatch = MemoryUpdateBatch(),
                routeDecision = MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.NOOP,
                    sourcePolicy = previouslyCompleted.source.usagePolicy,
                    sourceSearchText = previouslyCompleted.source.searchText,
                    reason = "Source ${request.source.id.value} was already processed by run ${previouslyCompleted.run.id.value}.",
                ),
                predicateCatalog = emptyList(),
                retrievalPlan = null,
                retrievedHits = emptyList(),
                entityOps = emptyList(),
                noteCandidates = emptyList(),
                rawNoteOps = emptyList(),
                noteOps = emptyList(),
                claimCandidates = emptyList(),
                rawClaimOps = emptyList(),
                claimOps = emptyList(),
                rawActionItemOps = emptyList(),
                actionItemOps = emptyList(),
                memoryBatch = MemoryUpdateBatch(),
            )
        }

        val sourceForCapture = request.source.withAssistantSourcePolicy()
        val requestForCapture = request.copy(source = sourceForCapture)
        val sourceBatch = MemoryUpdateBatch(sources = listOf(sourceForCapture))
        store.apply(sourceBatch)

        log.info {
            "Memory source captured: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "observedAt=${sourceForCapture.observedAt} createdAt=${sourceForCapture.createdAt} " +
                "hash=${sourceForCapture.contentHash} usagePolicy=${sourceForCapture.usagePolicy.sourcePolicyForLog()}"
        }

        if (sourceForCapture.isAssistantChatTurn()) {
            val routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.NOOP,
                sourcePolicy = sourceForCapture.usagePolicy,
                reason = "Assistant chat turns are captured as sources but are not materialized as structured memory in the hot path.",
            )
            log.info {
                "Memory write pipeline completed as assistant-source-only: namespace=${request.namespace.value} " +
                    "source=${request.source.id.value} usagePolicy=${sourceForCapture.usagePolicy.sourcePolicyForLog()}"
            }

            return DirectStructuredMemoryWriteResult(
                sourceBatch = sourceBatch,
                routeDecision = routeDecision,
                predicateCatalog = emptyList(),
                retrievalPlan = null,
                retrievedHits = emptyList(),
                entityOps = emptyList(),
                noteCandidates = emptyList(),
                rawNoteOps = emptyList(),
                noteOps = emptyList(),
                claimCandidates = emptyList(),
                rawClaimOps = emptyList(),
                claimOps = emptyList(),
                rawActionItemOps = emptyList(),
                actionItemOps = emptyList(),
                memoryBatch = MemoryUpdateBatch(),
            )
        }

        val routeDecision = requestForCapture.source.documentIngestRouteDecision()
            ?: router.route(requestForCapture)
                .withForcedMemoryWriteFallback(requestForCapture.source)
                .withSafeNoopSourcePolicy()
        val effectiveSource = sourceForCapture
            .withUsagePolicy(routeDecision.sourcePolicy)
            .withSearchText(routeDecision.sourceSearchText)
        val effectiveRequest = requestForCapture.copy(source = effectiveSource)
        val structuredRouteDecision = if (routeDecision.decision == MemoryRouteDecision.Decision.FORGET_REQUEST) {
            routeDecision
        } else if (effectiveSource.usagePolicy.allowStructuredExtraction) {
            routeDecision
        } else {
            routeDecision.copy(
                decision = MemoryRouteDecision.Decision.NOOP,
                memoryTypes = emptySet(),
                reason = "Source policy blocked structured extraction: ${routeDecision.reason}",
            )
        }
        val effectiveSourceBatch = embeddingIndexer.withEmbeddings(MemoryUpdateBatch(sources = listOf(effectiveSource)))
        if (effectiveSource != sourceForCapture || effectiveSourceBatch.embeddings.isNotEmpty()) {
            store.apply(effectiveSourceBatch)
        }
        if (effectiveSource != sourceForCapture) {
            log.info {
                "Memory source policy updated: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "allowStructuredExtraction=${effectiveSource.usagePolicy.allowStructuredExtraction} " +
                    "allowRecall=${effectiveSource.usagePolicy.allowRecall} " +
                    "allowEvidenceHydration=${effectiveSource.usagePolicy.allowEvidenceHydration} " +
                    "searchTextChars=${effectiveSource.searchText?.length ?: 0} " +
                    "reason=${effectiveSource.usagePolicy.reason.oneLineForLog(240)}"
            }
        }

        log.info {
            "Memory router decision: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "decision=${structuredRouteDecision.decision.name} types=${structuredRouteDecision.memoryTypes.joinToString { it.name }} " +
                "salience=${routeDecision.salience} sourcePolicy=${routeDecision.sourcePolicy.sourcePolicyForLog()} " +
                "reason=${structuredRouteDecision.reason}"
        }

        if (structuredRouteDecision.decision == MemoryRouteDecision.Decision.FORGET_REQUEST) {
            val pipeline = forgetPipeline
                ?: error("Memory forget pipeline is not configured for explicit forget request")
            val forgetResult = pipeline.run(effectiveRequest, structuredRouteDecision)

            log.info {
                "Memory write pipeline completed as FORGET_REQUEST: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "candidates=${forgetResult.candidates.size} actions=${forgetResult.forgetPlan.forgetActions.size} " +
                    "appliedSources=${forgetResult.memoryBatch.sources.size} appliedRuns=${forgetResult.memoryBatch.runs.size} " +
                    "appliedClaims=${forgetResult.memoryBatch.claims.size} appliedNotes=${forgetResult.memoryBatch.notes.size} " +
                    "appliedTasks=${forgetResult.memoryBatch.actionItems.size} appliedProfiles=${forgetResult.memoryBatch.profiles.size}"
            }

            return DirectStructuredMemoryWriteResult(
                sourceBatch = sourceBatch,
                routeDecision = structuredRouteDecision,
                predicateCatalog = emptyList(),
                retrievalPlan = null,
                retrievedHits = forgetResult.candidates,
                entityOps = emptyList(),
                noteCandidates = emptyList(),
                rawNoteOps = emptyList(),
                noteOps = emptyList(),
                claimCandidates = emptyList(),
                rawClaimOps = emptyList(),
                claimOps = emptyList(),
                rawActionItemOps = emptyList(),
                actionItemOps = emptyList(),
                memoryBatch = forgetResult.memoryBatch,
            )
        }

        if (structuredRouteDecision.decision == MemoryRouteDecision.Decision.NOOP) {
            val completedAt = clock.now()
            val noopRunBatch = materializer.materialize(
                DirectStructuredMemoryWriteMaterialization(
                    request = effectiveRequest,
                    routeDecision = structuredRouteDecision,
                    retrievalPlan = null,
                    retrievedHits = emptyList(),
                    entityOps = emptyList(),
                    noteCandidates = emptyList(),
                    rawNoteOps = emptyList(),
                    noteOps = emptyList(),
                    claimCandidates = emptyList(),
                    rawClaimOps = emptyList(),
                    claimOps = emptyList(),
                    rawActionItemOps = emptyList(),
                    actionItemOps = emptyList(),
                    predicateCatalog = emptyList(),
                    startedAt = request.source.createdAt,
                    completedAt = completedAt,
                )
            )
            store.apply(noopRunBatch)

            log.info {
                "Memory write pipeline completed as NOOP: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "runs=${noopRunBatch.runs.size}"
            }

            return DirectStructuredMemoryWriteResult(
                sourceBatch = sourceBatch,
                routeDecision = structuredRouteDecision,
                predicateCatalog = emptyList(),
                retrievalPlan = null,
                retrievedHits = emptyList(),
                entityOps = emptyList(),
                noteCandidates = emptyList(),
                rawNoteOps = emptyList(),
                noteOps = emptyList(),
                claimCandidates = emptyList(),
                rawClaimOps = emptyList(),
                claimOps = emptyList(),
                rawActionItemOps = emptyList(),
                actionItemOps = emptyList(),
                memoryBatch = noopRunBatch,
            )
        }

        val predicateCatalog = if (structuredRouteDecision.shouldRunClaimBranch()) {
            store.loadPredicateCatalog(request.namespace).also { catalog ->
                log.info {
                    "Memory predicate catalog loaded: namespace=${request.namespace.value} definitions=${catalog.size} " +
                        "predicates=${catalog.joinToString("|") { "${it.predicate}:${it.cardinality.name}/${it.temporalPolicy.name}/${it.conflictPolicy.name}" }.oneLineForLog(1_500)}"
                }
            }
        } else {
            log.info {
                "Memory predicate catalog skipped: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "decision=${structuredRouteDecision.decision.name} types=${structuredRouteDecision.memoryTypes.joinToString { it.name }}"
            }
            emptyList()
        }

        val retrievalPlan = effectiveRequest.documentIngestRetrievalPlan(structuredRouteDecision)
            ?: retrievalPlanner.plan(effectiveRequest, structuredRouteDecision, predicateCatalog)
        val retrievedHits = retrieve(effectiveRequest, retrievalPlan)

        log.info {
            "Memory write retrieval plan: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "need=${retrievalPlan.needRetrieval} types=${retrievalPlan.memoryTypes.joinToString { it.name }} " +
                "entityQueries=${retrievalPlan.entityQueries.joinToString("|")} " +
                "textQueries=${retrievalPlan.textQueries.joinToString("|")} " +
                "predicateHints=${retrievalPlan.predicateHints.joinToString("|")} " +
                "searchScopes=${retrievalPlan.searchScopes().joinToString { it.name }} " +
                "searchQuery=${retrievalPlan.searchQuery(request.source).oneLineForLog(500)} " +
                "budget=${retrievalPlan.retrievalBudget} hits=${retrievedHits.size} hitBreakdown=${retrievedHits.breakdown()}"
        }

        log.info {
            "Memory write retrieved hits: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "hits=${retrievedHits.hitSummary()}"
        }

        val entityOps = entityCanonicalizer.canonicalize(
            request = effectiveRequest,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
        ).withNamespaceSubject(effectiveRequest)

        log.info {
            "Memory entity canonicalizer result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${entityOps.size} actionBreakdown=${entityOps.actionBreakdown()} " +
                "entities=${entityOps.entitySummary()}"
        }

        log.info {
            "Memory entity canonicalizer details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${entityOps.entityDetailsForLog()}"
        }

        val branchResults = runWriteBranches(
            effectiveRequest = effectiveRequest,
            structuredRouteDecision = structuredRouteDecision,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
            predicateCatalog = predicateCatalog,
        )
        val noteCandidates = branchResults.note.noteCandidates
        val rawNoteOps = branchResults.note.rawNoteOps
        val noteOps = branchResults.note.noteOps
        val claimCandidates = branchResults.claim.claimCandidates
        val rawClaimOps = branchResults.claim.rawClaimOps
        val claimOps = branchResults.claim.claimOps
        val rawActionItemOps = branchResults.actionItem.rawActionItemOps
        val actionItemOps = branchResults.actionItem.actionItemOps

        val completedAt = clock.now()
        val materialization = DirectStructuredMemoryWriteMaterialization(
            request = effectiveRequest,
            routeDecision = structuredRouteDecision,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
            noteCandidates = noteCandidates,
            rawNoteOps = rawNoteOps,
            noteOps = noteOps,
            claimCandidates = claimCandidates,
            rawClaimOps = rawClaimOps,
            claimOps = claimOps,
            rawActionItemOps = rawActionItemOps,
            actionItemOps = actionItemOps,
            predicateCatalog = predicateCatalog,
            startedAt = request.source.createdAt,
            completedAt = completedAt,
        )
        val materializedMemoryBatch = materializer.materialize(materialization)
        val staleNotesBatch = staleNotesForSupersededClaims(
            memoryBatch = materializedMemoryBatch,
            namespace = request.namespace,
            completedAt = completedAt,
        )
        val structuredMemoryBatch = materializedMemoryBatch + staleNotesBatch

        if (staleNotesBatch.notes.isNotEmpty()) {
            log.info {
                "Memory claim supersede cascaded stale notes: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "notes=${staleNotesBatch.notes.size} noteIds=${staleNotesBatch.notes.joinToString("|") { it.id.value }}"
            }
        }

        log.info {
            "Memory materializer batch: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "predicates=${structuredMemoryBatch.predicateDefinitions.size} runs=${structuredMemoryBatch.runs.size} entities=${structuredMemoryBatch.entities.size} claims=${structuredMemoryBatch.claims.size} " +
                "notes=${structuredMemoryBatch.notes.size} actionItems=${structuredMemoryBatch.actionItems.size} profiles=${structuredMemoryBatch.profiles.size} " +
                "batch=${structuredMemoryBatch.batchDetailsForLog()}"
        }

        val indexedStructuredMemoryBatch = embeddingIndexer.withEmbeddings(structuredMemoryBatch)
        store.apply(indexedStructuredMemoryBatch)

        val profileBatch = profileUpdater.update(
            request = effectiveRequest,
            appliedBatch = indexedStructuredMemoryBatch,
            completedAt = completedAt,
        )

        val indexedProfileBatch = embeddingIndexer.withEmbeddings(profileBatch)
        if (indexedProfileBatch.isNotEmpty()) {
            store.apply(indexedProfileBatch)
            log.info {
                "Memory profile batch applied: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "profiles=${indexedProfileBatch.profiles.size} embeddings=${indexedProfileBatch.embeddings.size} " +
                    "batch=${indexedProfileBatch.batchDetailsForLog()}"
            }
        }

        val memoryBatch = indexedStructuredMemoryBatch + indexedProfileBatch

        log.info {
            "Memory write pipeline completed: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "route=${structuredRouteDecision.decision.name} retrievalHits=${retrievedHits.size} entityOps=${entityOps.size} " +
                "noteCandidates=${noteCandidates.size} noteOps=${noteOps.size} claimCandidates=${claimCandidates.size} claimOps=${claimOps.size} actionItemOps=${actionItemOps.size} " +
                "appliedSources=${sourceBatch.sources.size} appliedRuns=${memoryBatch.runs.size} appliedEntities=${memoryBatch.entities.size} " +
                "appliedNotes=${memoryBatch.notes.size} appliedClaims=${memoryBatch.claims.size} appliedTasks=${memoryBatch.actionItems.size} " +
                "appliedProfiles=${memoryBatch.profiles.size} appliedEmbeddings=${memoryBatch.embeddings.size}"
        }

        return DirectStructuredMemoryWriteResult(
            sourceBatch = sourceBatch,
            routeDecision = structuredRouteDecision,
            predicateCatalog = predicateCatalog,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
            noteCandidates = noteCandidates,
            rawNoteOps = rawNoteOps,
            noteOps = noteOps,
            claimCandidates = claimCandidates,
            rawClaimOps = rawClaimOps,
            claimOps = claimOps,
            rawActionItemOps = rawActionItemOps,
            actionItemOps = actionItemOps,
            memoryBatch = memoryBatch,
        )
    }

    private suspend fun runWriteBranches(
        effectiveRequest: DirectStructuredMemoryWriteRequest,
        structuredRouteDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryWriteBranchResults {
        log.info {
            "Memory write branches start: namespace=${effectiveRequest.namespace.value} source=${effectiveRequest.source.id.value} " +
                "parallelism=$branchParallelism"
        }

        if (branchParallelism == 1) {
            return MemoryWriteBranchResults(
                note = if (structuredRouteDecision.shouldRunNoteBranch()) {
                    runNoteBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps)
                } else {
                    MemoryWriteNoteBranchResult()
                },
                claim = if (structuredRouteDecision.shouldRunClaimBranch()) {
                    runClaimBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps, predicateCatalog)
                } else {
                    MemoryWriteClaimBranchResult()
                },
                actionItem = if (structuredRouteDecision.shouldRunActionItemBranch()) {
                    runActionItemBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps)
                } else {
                    MemoryWriteActionItemBranchResult()
                },
            )
        }

        return coroutineScope {
            val semaphore = Semaphore(branchParallelism)
            val note = if (structuredRouteDecision.shouldRunNoteBranch()) {
                async {
                    semaphore.withPermit {
                        runNoteBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps)
                    }
                }
            } else {
                null
            }
            val claim = if (structuredRouteDecision.shouldRunClaimBranch()) {
                async {
                    semaphore.withPermit {
                        runClaimBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps, predicateCatalog)
                    }
                }
            } else {
                null
            }
            val actionItem = if (structuredRouteDecision.shouldRunActionItemBranch()) {
                async {
                    semaphore.withPermit {
                        runActionItemBranch(effectiveRequest, structuredRouteDecision, retrievalPlan, retrievedHits, entityOps)
                    }
                }
            } else {
                null
            }

            MemoryWriteBranchResults(
                note = note?.await() ?: MemoryWriteNoteBranchResult(),
                claim = claim?.await() ?: MemoryWriteClaimBranchResult(),
                actionItem = actionItem?.await() ?: MemoryWriteActionItemBranchResult(),
            )
        }
    }

    private suspend fun runNoteBranch(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): MemoryWriteNoteBranchResult {
        val noteCandidates = noteConstructor.construct(
            request = request,
            routeDecision = routeDecision,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
        )

        log.info {
            "Memory note constructor result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "notes=${noteCandidates.size} types=${noteCandidates.noteTypeSummary()} notesPreview=${noteCandidates.noteSummary()}"
        }

        log.info {
            "Memory note constructor details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "notes=${noteCandidates.noteDetailsForLog()}"
        }

        val rawNoteOps = reconcileNoteCandidates(
            request = request,
            noteCandidates = noteCandidates,
            retrievedHits = retrievedHits,
        )
        val noteOps = MemoryDedupPolicy.deduplicateWriteNoteOps(rawNoteOps, retrievedHits)

        if (noteOps != rawNoteOps) {
            log.info {
                "Memory note dedup guard adjusted ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "before=${rawNoteOps.noteReconciliationActionBreakdown()} after=${noteOps.noteReconciliationActionBreakdown()} " +
                    "changes=${rawNoteOps.noteDedupChangesForLog(noteOps)}"
            }
        }

        log.info {
            "Memory note reconciler result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${noteOps.size} actionBreakdown=${noteOps.noteReconciliationActionBreakdown()} opsPreview=${noteOps.noteReconciliationSummary()}"
        }

        log.info {
            "Memory note reconciler details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${noteOps.noteReconciliationDetailsForLog()}"
        }

        return MemoryWriteNoteBranchResult(
            noteCandidates = noteCandidates,
            rawNoteOps = rawNoteOps,
            noteOps = noteOps,
        )
    }

    private suspend fun runClaimBranch(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryWriteClaimBranchResult {
        val claimCandidates = claimExtractor.extract(
            request = request,
            routeDecision = routeDecision,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
            predicateCatalog = predicateCatalog,
        )

        log.info {
            "Memory claim extractor result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "claims=${claimCandidates.size} predicates=${claimCandidates.predicateSummary()} " +
                "claimsPreview=${claimCandidates.claimSummary()}"
        }

        log.info {
            "Memory claim extractor details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "claims=${claimCandidates.claimDetailsForLog()}"
        }

        val rawClaimOps = reconcileClaimCandidates(
            request = request,
            claimCandidates = claimCandidates,
            retrievedHits = retrievedHits,
            predicateCatalog = predicateCatalog,
        )
        val claimOps = MemoryDedupPolicy.deduplicateWriteClaimOps(rawClaimOps, retrievedHits)

        if (claimOps != rawClaimOps) {
            log.info {
                "Memory claim dedup guard adjusted ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "before=${rawClaimOps.reconciliationActionBreakdown()} after=${claimOps.reconciliationActionBreakdown()} " +
                    "changes=${rawClaimOps.claimDedupChangesForLog(claimOps)}"
            }
        }

        log.info {
            "Memory claim reconciler result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${claimOps.size} actionBreakdown=${claimOps.reconciliationActionBreakdown()} opsPreview=${claimOps.reconciliationSummary()}"
        }

        log.info {
            "Memory claim reconciler details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${claimOps.reconciliationDetailsForLog()}"
        }

        return MemoryWriteClaimBranchResult(
            claimCandidates = claimCandidates,
            rawClaimOps = rawClaimOps,
            claimOps = claimOps,
        )
    }

    private suspend fun runActionItemBranch(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): MemoryWriteActionItemBranchResult {
        val rawActionItemOps = actionItemUpdater.update(
            request = request,
            routeDecision = routeDecision,
            retrievalPlan = retrievalPlan,
            retrievedHits = retrievedHits,
            entityOps = entityOps,
        )
        val actionItemOps = MemoryDedupPolicy.deduplicateWriteTaskOps(rawActionItemOps, retrievedHits)

        if (actionItemOps != rawActionItemOps) {
            log.info {
                "Memory actionItem dedup guard adjusted ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "before=${rawActionItemOps.taskActionBreakdown()} after=${actionItemOps.taskActionBreakdown()} " +
                    "changes=${rawActionItemOps.taskDedupChangesForLog(actionItemOps)}"
            }
        }

        log.info {
            "Memory actionItem updater result: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${actionItemOps.size} actionBreakdown=${actionItemOps.taskActionBreakdown()} opsPreview=${actionItemOps.taskSummary()}"
        }

        log.info {
            "Memory actionItem updater details: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${actionItemOps.taskDetailsForLog()}"
        }

        return MemoryWriteActionItemBranchResult(
            rawActionItemOps = rawActionItemOps,
            actionItemOps = actionItemOps,
        )
    }

    private suspend fun reconcileNoteCandidates(
        request: DirectStructuredMemoryWriteRequest,
        noteCandidates: List<MemoryNoteCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryNoteReconciliationOp> {
        val batchSize = MemoryLlmStageLimits.NOTE_RECONCILER_CANDIDATE_BATCH_SIZE
        if (noteCandidates.size <= batchSize) {
            return noteReconciler.reconcile(request, noteCandidates, retrievedHits)
        }

        log.info {
            "Memory note reconciler batching: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${noteCandidates.size} batchSize=$batchSize"
        }

        return noteCandidates.chunked(batchSize).flatMapIndexed { index, batch ->
            log.info {
                "Memory note reconciler batch start: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "batch=${index + 1} candidates=${batch.size}/${noteCandidates.size}"
            }
            noteReconciler.reconcile(request, batch, retrievedHits)
        }
    }

    private suspend fun reconcileClaimCandidates(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimReconciliationOp> {
        val batchSize = MemoryLlmStageLimits.CLAIM_RECONCILER_CANDIDATE_BATCH_SIZE
        if (claimCandidates.size <= batchSize) {
            return claimReconciler.reconcile(request, claimCandidates, retrievedHits, predicateCatalog)
        }

        log.info {
            "Memory claim reconciler batching: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${claimCandidates.size} batchSize=$batchSize"
        }

        return claimCandidates.chunked(batchSize).flatMapIndexed { index, batch ->
            log.info {
                "Memory claim reconciler batch start: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "batch=${index + 1} candidates=${batch.size}/${claimCandidates.size}"
            }
            claimReconciler.reconcile(request, batch, retrievedHits, predicateCatalog)
        }
    }

    private suspend fun staleNotesForSupersededClaims(
        memoryBatch: MemoryUpdateBatch,
        namespace: MemoryNamespace,
        completedAt: Instant,
    ): MemoryUpdateBatch {
        val supersededClaims = memoryBatch.claims
            .filter { it.status == MemoryClaim.Status.SUPERSEDED }
        if (supersededClaims.isEmpty()) {
            return MemoryUpdateBatch()
        }

        val alreadyUpdatedNoteIds = memoryBatch.notes
            .mapTo(mutableSetOf()) { it.id }
        val snapshot = store.loadNamespaceSnapshot(namespace)
        val entitiesById = snapshot.entities.associateBy { it.id }
        val staleNotes = snapshot.notes
            .filter { it.id !in alreadyUpdatedNoteIds }
            .filter { note -> supersededClaims.any { claim -> note.isSupportedBySupersededClaim(claim, entitiesById) } }
            .map { note ->
                note.copy(
                    status = MemoryNote.Status.STALE,
                    validTo = note.validTo ?: completedAt,
                    updatedAt = completedAt,
                )
            }

        return MemoryUpdateBatch(notes = staleNotes)
    }

    private suspend fun findCompletedStructuredWriteForSameSource(
        request: DirectStructuredMemoryWriteRequest,
    ): CompletedStructuredWrite? {
        val existingSource = store.findSourcesByIds(listOf(request.source.id))
            .firstOrNull { it.namespace == request.namespace }
            ?.takeIf { it.contentHash == request.source.contentHash }
            ?: return null
        val completedRun = store.loadNamespaceSnapshot(request.namespace, includeArchived = true)
            .runs
            .filter { it.status == MemoryRun.Status.SUCCESS }
            .filter { it.runType in idempotentWriteRunTypes }
            .filter { request.source.id in it.sourceIds }
            .maxByOrNull { it.completedAt ?: it.createdAt }
            ?: return null

        return CompletedStructuredWrite(existingSource, completedRun)
    }

    private data class CompletedStructuredWrite(
        val source: MemorySource,
        val run: MemoryRun,
    )

    private val idempotentWriteRunTypes = setOf(
        MemoryRun.Type.RECONCILE_CLAIMS,
        MemoryRun.Type.RECONCILE_NOTES,
        MemoryRun.Type.UPDATE_ACTION_ITEMS,
        MemoryRun.Type.FORGET_MEMORY,
    )

private val documentIngestMemoryTypes = setOf(
    MemorySemanticType.CLAIM,
    MemorySemanticType.NOTE,
    MemorySemanticType.SOURCE,
    MemorySemanticType.ENTITY,
)

private fun MemorySource.documentIngestRouteDecision(): MemoryRouteDecision? {
    if (!isDocumentIngestSource()) {
        return null
    }

    val reason = "Document ingest uses deterministic document truth route; stable document facts are extracted as claims, notes/sources keep richer context, and action items stay explicit-only."
    return MemoryRouteDecision(
        decision = MemoryRouteDecision.Decision.MIXED,
        memoryTypes = documentIngestMemoryTypes,
        salience = if (isForcedMemoryWriteSource()) 0.95 else 0.85,
        sourcePolicy = MemorySourceUsagePolicy.STANDARD.copy(reason = reason),
        sourceSearchText = defaultIngestSearchText(),
        reason = reason,
    )
}

private fun MemoryRouteDecision.shouldRunNoteBranch(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        MemorySemanticType.NOTE in memoryTypes

private fun MemoryRouteDecision.shouldRunClaimBranch(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        (MemorySemanticType.CLAIM in memoryTypes || MemorySemanticType.PROFILE in memoryTypes)

private fun MemoryRouteDecision.shouldRunActionItemBranch(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        MemorySemanticType.ACTION_ITEM in memoryTypes

private fun DirectStructuredMemoryWriteRequest.documentIngestRetrievalPlan(
    routeDecision: MemoryRouteDecision,
): MemoryWriteRetrievalPlan? {
    if (!source.isDocumentIngestSource()) {
        return null
    }
    if (routeDecision.decision == MemoryRouteDecision.Decision.NOOP ||
        routeDecision.decision == MemoryRouteDecision.Decision.FORGET_REQUEST
    ) {
        return null
    }

    val sourceSearchText = source.searchText ?: source.defaultIngestSearchText()
    return MemoryWriteRetrievalPlan(
        needRetrieval = true,
        entityQueries = source.documentIngestSearchHints(),
        textQueries = listOf(sourceSearchText).filter { it.isNotBlank() },
        memoryTypes = documentIngestMemoryTypes,
        retrievalBudget = MemoryRetrievalBudget(claims = 8, notes = 8, sources = 6),
    )
}

private fun MemorySource.isAssistantChatTurn(): Boolean =
    this is MemorySource.ChatTurn && speakerRole == MemorySource.ActorRole.ASSISTANT

private fun MemorySource.withAssistantSourcePolicy(): MemorySource {
    if (!isAssistantChatTurn()) return this

    return withUsagePolicy(
        MemorySourceUsagePolicy.AUDIT_ONLY.copy(
            reason = "Assistant chat turn is source-only audit: assistant replies are not user evidence or durable memory input.",
        )
    )
}

private fun MemoryRouteDecision.withSafeNoopSourcePolicy(): MemoryRouteDecision {
    if (decision != MemoryRouteDecision.Decision.NOOP || sourcePolicy != MemorySourceUsagePolicy.STANDARD) {
        return this
    }

    return copy(
        sourcePolicy = MemorySourceUsagePolicy.AUDIT_ONLY.copy(
            reason = "NOOP source defaulted to audit-only because router did not explicitly allow source-only recall.",
        ),
    )
}

private fun MemoryRouteDecision.withForcedMemoryWriteFallback(source: MemorySource): MemoryRouteDecision {
    if (decision != MemoryRouteDecision.Decision.NOOP) {
        return this
    }

    if (!source.isForcedMemoryWriteSource()) {
        return this
    }

    val fallbackReason = "Forced memory write overrode router NOOP."

    return copy(
        decision = MemoryRouteDecision.Decision.NOTE_WRITE,
        memoryTypes = setOf(MemorySemanticType.NOTE, MemorySemanticType.SOURCE),
        salience = salience.coerceAtLeast(0.95),
        sourcePolicy = MemorySourceUsagePolicy.STANDARD.copy(reason = fallbackReason),
        sourceSearchText = sourceSearchText ?: source.defaultIngestSearchText(),
        reason = "$fallbackReason Original router reason: $reason",
    )
}

private fun MemorySource.withUsagePolicy(policy: MemorySourceUsagePolicy): MemorySource =
    when (this) {
        is MemorySource.ChatTurn -> copy(usagePolicy = policy)
        is MemorySource.ToolOutput -> copy(usagePolicy = policy)
        is MemorySource.ImportedNote -> copy(usagePolicy = policy)
        is MemorySource.ExternalRecord -> copy(usagePolicy = policy)
    }

private fun MemorySource.withSearchText(searchText: String?): MemorySource {
    val normalizedSearchText = searchText
        ?.trim()
        ?.take(4_000)
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.equals(contentText.trim(), ignoreCase = true) }

    return when (this) {
        is MemorySource.ChatTurn -> copy(searchText = normalizedSearchText)
        is MemorySource.ToolOutput -> copy(searchText = normalizedSearchText)
        is MemorySource.ImportedNote -> copy(searchText = normalizedSearchText)
        is MemorySource.ExternalRecord -> copy(searchText = normalizedSearchText)
    }
}

private fun MemorySourceUsagePolicy.sourcePolicyForLog(): String =
    "structured=$allowStructuredExtraction,recall=$allowRecall,evidence=$allowEvidenceHydration,reason=${reason.oneLineForLog(180)}"

private fun List<MemoryStore.SearchHit>.breakdown(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.label() }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun MemoryStore.SearchHit.label(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> "source"
        is MemoryStore.SearchHit.EntityHit -> "entity"
        is MemoryStore.SearchHit.ClaimHit -> "claim"
        is MemoryStore.SearchHit.NoteHit -> "note"
        is MemoryStore.SearchHit.ActionItemHit -> "actionItem"
        is MemoryStore.SearchHit.ProfileHit -> "profile"
        is MemoryStore.SearchHit.EpisodeHit -> "episode"
        is MemoryStore.SearchHit.RunHit -> "run"
    }

private fun List<MemoryStore.SearchHit>.hitSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { hit ->
        when (hit) {
            is MemoryStore.SearchHit.SourceHit -> "source:${hit.source.id.value}:${hit.source.contentText.oneLineForLog(180)}"
            is MemoryStore.SearchHit.EntityHit -> "entity:${hit.entity.id.value}:${hit.entity.entityType.name}:${hit.entity.canonicalName}"
            is MemoryStore.SearchHit.ClaimHit -> "claim:${hit.claim.id.value}:${hit.claim.predicate}:${hit.claim.predicateFamily ?: "unknown"}:${hit.claim.normalizedText.oneLineForLog(180)}"
            is MemoryStore.SearchHit.NoteHit -> "note:${hit.note.id.value}:${hit.note.noteType.name}:${hit.note.title.oneLineForLog(120)}"
            is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${hit.actionItem.id.value}:${hit.actionItem.status.name}:${hit.actionItem.title.oneLineForLog(120)}"
            is MemoryStore.SearchHit.ProfileHit -> "profile:${hit.profile.id.value}:${hit.profile.profileText.oneLineForLog(180)}"
            is MemoryStore.SearchHit.EpisodeHit -> "episode:${hit.episode.id.value}:${hit.episode.lesson.oneLineForLog(180)}"
            is MemoryStore.SearchHit.RunHit -> "run:${hit.run.id.value}:${hit.run.runType.name}:${hit.run.status.name}:${hit.run.summary.oneLineForLog(120)}"
        }
    }
}

private fun List<MemoryEntityCanonicalizationOp>.actionBreakdown(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryEntityCanonicalizationOp>.withNamespaceSubject(
    request: DirectStructuredMemoryWriteRequest,
): List<MemoryEntityCanonicalizationOp> {
    val namespaceSubjectId = request.namespace.namespaceSubjectEntityId()
    if (any { it.entityId == namespaceSubjectId }) {
        return this
    }

    return this + MemoryEntityCanonicalizationOp(
        mention = "current project",
        action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
        entityId = namespaceSubjectId,
        newEntity = MemoryEntityCanonicalizationOp.NewEntity(
            entityType = MemoryEntity.Type.PROJECT,
            canonicalName = "Current project",
            summary = "The current project in this memory namespace.",
        ),
        aliasText = "this project",
        confidence = 1.0,
        reason = "Default current-project subject for claims without a more specific resolved subject",
    )
}

private fun MemoryNamespace.namespaceSubjectEntityId(): MemoryEntity.Id =
    MemoryEntity.Id("entity:${value.sha256ForMemory().take(16)}")

private fun List<MemoryEntityCanonicalizationOp>.entitySummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        val name = op.newEntity?.canonicalName ?: op.aliasText ?: op.mention
        "${op.action.name}:${op.mention}->${op.entityId?.value ?: "null"}:$name"
    }
}

private fun List<MemoryEntityCanonicalizationOp>.entityDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "mention=${op.mention.oneLineForLog(120)} action=${op.action.name} entityId=${op.entityId?.value ?: "null"} " +
            "newType=${op.newEntity?.entityType?.name ?: "null"} newName=${op.newEntity?.canonicalName?.oneLineForLog(120) ?: "null"} " +
            "alias=${op.aliasText?.oneLineForLog(120) ?: "null"} confidence=${op.confidence} reason=${op.reason.oneLineForLog(220)}"
    }
}

private fun List<MemoryNoteCandidate>.noteTypeSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.noteType.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryNoteCandidate>.noteSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { note ->
        "${note.noteType.name}:${note.title.oneLineForLog(140)}"
    }
}

private fun List<MemoryNoteCandidate>.noteDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { note ->
        "type=${note.noteType.name} status=${note.status.name} maturity=${note.maturity.name} " +
            "importance=${note.importance} confidence=${note.confidence} validFrom=${note.validFrom} validTo=${note.validTo} " +
            "scope=${note.scope.scopeForLog()} title=${note.title.oneLineForLog(180)} summary=${note.summary.oneLineForLog(260)} " +
            "entities=${note.entityRefs.joinToString(",") { "${it.entityId.value}:${it.role.name}" }.ifBlank { "none" }} " +
            "keywords=${note.keywords.joinToString(",").oneLineForLog(180)} tags=${note.tags.joinToString(",").oneLineForLog(180)} " +
            "evidenceKind=${note.evidenceKind.name} evidenceQuote=${note.evidenceQuote?.oneLineForLog(240) ?: "null"} " +
            "evidenceReason=${note.evidenceReason.oneLineForLog(220)} rationale=${note.rationale.oneLineForLog(220)}"
    }
}

private fun List<MemoryClaimCandidate>.predicateSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.predicate }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryClaimCandidate>.claimSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { claim ->
        "${claim.subjectEntityId.value}:${claim.predicate}:${claim.normalizedText.take(180)}"
    }
}

private fun List<MemoryClaimCandidate>.claimDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { claim ->
        "subject=${claim.subjectEntityId.value} predicate=${claim.predicate} family=${claim.predicateFamily ?: "null"} " +
            "policy=${claim.predicatePolicy?.let { "${it.cardinality.name}/${it.temporalPolicy.name}/${it.conflictPolicy.name}" } ?: "null"} " +
            "objectEntity=${claim.objectEntityId?.value ?: "null"} objectValue=${claim.objectValue?.compactForLog() ?: "null"} " +
            "importance=${claim.importance} confidence=${claim.confidence} validFrom=${claim.validFrom} validTo=${claim.validTo} " +
            "scope=${claim.scope.scopeForLog()} normalized=${claim.normalizedText.oneLineForLog(240)} " +
            "context=${claim.contextText?.oneLineForLog(180) ?: "null"} qualifiers=${claim.qualifiers.compactForLog()} " +
            "evidenceKind=${claim.evidenceKind.name} evidenceQuote=${claim.evidenceQuote?.oneLineForLog(240) ?: "null"} " +
            "evidenceReason=${claim.evidenceReason.oneLineForLog(220)} " +
            "reason=${claim.reason.oneLineForLog(220)}"
    }
}

private fun List<MemoryClaimReconciliationOp>.reconciliationActionBreakdown(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryClaimReconciliationOp>.reconciliationSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "${op.action.name}:target=${op.targetClaimId?.value ?: "null"}:${op.candidate?.predicate ?: "no-candidate"}:${op.reason.oneLineForLog(180)}"
    }
}

private fun List<MemoryClaimReconciliationOp>.reconciliationDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "action=${op.action.name} target=${op.targetClaimId?.value ?: "null"} " +
            "candidate=${op.candidate?.let { "${it.subjectEntityId.value}:${it.predicate}:${it.normalizedText.oneLineForLog(180)}" } ?: "null"} " +
            "patchStatus=${op.updatedClaim?.status?.name ?: "null"} patchValidFrom=${op.updatedClaim?.validFrom} " +
            "patchValidTo=${op.updatedClaim?.validTo} reason=${op.reason.oneLineForLog(240)}"
    }
}

private fun List<MemoryClaimReconciliationOp>.claimDedupChangesForLog(
    deduplicatedOps: List<MemoryClaimReconciliationOp>,
): String =
    zip(deduplicatedOps)
        .mapIndexedNotNull { index, (before, after) ->
            if (before == after) return@mapIndexedNotNull null
            "[$index] ${before.action.name}->${after.action.name} target=${after.targetClaimId?.value ?: "null"} " +
                "candidate=${after.candidate?.let { "${it.subjectEntityId.value}:${it.predicate}:${it.normalizedText.oneLineForLog(140)}" } ?: "null"} " +
                "reason=${after.reason.oneLineForLog(220)}"
        }
        .joinToString("|")
        .ifBlank { "none" }

private fun List<MemoryNoteReconciliationOp>.noteDedupChangesForLog(
    deduplicatedOps: List<MemoryNoteReconciliationOp>,
): String =
    zip(deduplicatedOps)
        .mapIndexedNotNull { index, (before, after) ->
            if (before == after) return@mapIndexedNotNull null
            "[$index] ${before.action.name}->${after.action.name} target=${after.targetNoteId?.value ?: "null"} " +
                "candidate=${after.candidate?.let { "${it.noteType.name}:${it.title.oneLineForLog(140)}" } ?: "null"} " +
                "reason=${after.reason.oneLineForLog(220)}"
        }
        .joinToString("|")
        .ifBlank { "none" }

private fun List<MemoryNoteReconciliationOp>.noteReconciliationActionBreakdown(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryNoteReconciliationOp>.noteReconciliationSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "${op.action.name}:target=${op.targetNoteId?.value ?: "null"}:${op.candidate?.title?.oneLineForLog(120) ?: "no-candidate"}:${op.reason.oneLineForLog(180)}"
    }
}

private fun List<MemoryNoteReconciliationOp>.noteReconciliationDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "action=${op.action.name} target=${op.targetNoteId?.value ?: "null"} " +
            "candidate=${op.candidate?.let { "${it.noteType.name}:${it.title.oneLineForLog(180)}" } ?: "null"} " +
            "patchTitle=${op.updatedNote?.title?.oneLineForLog(120) ?: "null"} patchStatus=${op.updatedNote?.status?.name ?: "null"} " +
            "patchMaturity=${op.updatedNote?.maturity?.name ?: "null"} links=${op.linksToCreate.joinToString(",") { "${it.toNoteId.value}:${it.linkType.name}:${it.linkWeight}" }.ifBlank { "none" }} " +
            "reason=${op.reason.oneLineForLog(240)}"
    }
}

private fun List<MemoryActionItemUpdateOp>.taskDedupChangesForLog(
    deduplicatedOps: List<MemoryActionItemUpdateOp>,
): String =
    zip(deduplicatedOps)
        .mapIndexedNotNull { index, (before, after) ->
            if (before == after) return@mapIndexedNotNull null
            "[$index] ${before.action.name}->${after.action.name} target=${after.targetActionItemId?.value ?: "null"} " +
                "actionItem=${after.actionItem?.title?.oneLineForLog(140) ?: "null"} reason=${after.reason.oneLineForLog(220)}"
        }
        .joinToString("|")
        .ifBlank { "none" }

private fun List<MemoryActionItemUpdateOp>.taskActionBreakdown(): String {
    if (isEmpty()) {
        return "none"
    }

    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryActionItemUpdateOp>.taskSummary(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        "${op.action.name}:target=${op.targetActionItemId?.value ?: "null"}:${op.actionItem?.title?.oneLineForLog(120) ?: "no-actionItem"}:${op.reason.oneLineForLog(180)}"
    }
}

private fun List<MemoryActionItemUpdateOp>.taskDetailsForLog(): String {
    if (isEmpty()) {
        return "none"
    }

    return joinToString("|") { op ->
        val actionItem = op.actionItem
        "action=${op.action.name} target=${op.targetActionItemId?.value ?: "null"} " +
            "title=${actionItem?.title?.oneLineForLog(160) ?: "null"} status=${actionItem?.status?.name ?: "null"} priority=${actionItem?.priority?.name ?: "null"} " +
            "dueAt=${actionItem?.dueAt} scope=${actionItem?.scope?.scopeForLog() ?: "null"} " +
            "related=${actionItem?.relatedEntityIds?.joinToString(",") { it.value } ?: "none"} " +
            "evidenceQuote=${actionItem?.evidenceQuote?.oneLineForLog(240) ?: "null"} reason=${op.reason.oneLineForLog(240)}"
    }
}

private fun MemoryUpdateBatch.batchDetailsForLog(): String {
    val predicates = predicateDefinitions.joinToString("|") {
        "${it.predicate}:${it.cardinality.name}/${it.temporalPolicy.name}/${it.conflictPolicy.name}"
    }.ifBlank { "none" }
    val runs = runs.joinToString("|") { "${it.id.value}:${it.runType.name}:${it.status.name}:${it.summary.oneLineForLog(140)}" }.ifBlank { "none" }
    val entities = entities.joinToString("|") { "${it.id.value}:${it.entityType.name}:${it.canonicalName.oneLineForLog(120)}" }.ifBlank { "none" }
    val claims = claims.joinToString("|") { "${it.id.value}:${it.status.name}:${it.subjectEntityId.value}:${it.predicate}:${it.predicateFamily ?: "unknown"}:${it.normalizedText.oneLineForLog(180)}" }.ifBlank { "none" }
    val notes = notes.joinToString("|") { "${it.id.value}:${it.status.name}:${it.noteType.name}:${it.title.oneLineForLog(140)}" }.ifBlank { "none" }
    val actionItems = actionItems.joinToString("|") { "${it.id.value}:${it.status.name}:${it.title.oneLineForLog(140)}" }.ifBlank { "none" }
    val profilesSummary = profiles.joinToString("|") { "${it.id.value}:${it.ownerEntityId.value}:${it.profileText.oneLineForLog(140)}" }.ifBlank { "none" }
    return "predicates=[$predicates] runs=[$runs] entities=[$entities] notes=[$notes] claims=[$claims] actionItems=[$actionItems] profiles=[$profilesSummary] embeddings=${embeddings.size}"
}

private operator fun MemoryUpdateBatch.plus(other: MemoryUpdateBatch): MemoryUpdateBatch =
    MemoryUpdateBatch(
        predicateDefinitions = predicateDefinitions + other.predicateDefinitions,
        sources = sources + other.sources,
        runs = runs + other.runs,
        entities = entities + other.entities,
        claims = claims + other.claims,
        notes = notes + other.notes,
        actionItems = actionItems + other.actionItems,
        profiles = profiles + other.profiles,
        episodes = episodes + other.episodes,
        embeddings = embeddings + other.embeddings,
    )

private fun MemoryUpdateBatch.isNotEmpty(): Boolean =
    predicateDefinitions.isNotEmpty() ||
        sources.isNotEmpty() ||
        runs.isNotEmpty() ||
        entities.isNotEmpty() ||
        claims.isNotEmpty() ||
        notes.isNotEmpty() ||
        actionItems.isNotEmpty() ||
        profiles.isNotEmpty() ||
        episodes.isNotEmpty() ||
        embeddings.isNotEmpty()

    private suspend fun retrieve(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
    ): List<MemoryStore.SearchHit> {
        val entityHits = retrieveCandidateEntities(request, retrievalPlan)

        if (!retrievalPlan.needRetrieval) {
            return entityHits
        }

        val mainHits = store.search(
            MemoryStore.SearchRequest(
                query = retrievalPlan.searchQuery(request.source),
                namespace = request.namespace,
                scopes = retrievalPlan.searchScopes(),
                filters = MemoryStore.SearchFilters(),
                timeWindow = retrievalPlan.timeWindow,
                embedding = embeddingIndexer.searchEmbedding(retrievalPlan.searchQuery(request.source)),
                limit = retrievalPlan.limit(),
            ),
        )

        val unfilteredHits = (entityHits + mainHits).distinctBy { it.itemKey() }
        val sourceSelection = MemorySourceRetrievalPolicy.apply(
            hits = unfilteredHits,
            useCase = MemorySourceRetrievalUseCase.WRITE_GROUNDING,
        )

        if (sourceSelection.changed) {
            log.info {
                "Memory write source retrieval policy: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "${sourceSelection.summaryForLog()}"
            }
        }

        return sourceSelection.hits
    }

    private suspend fun retrieveCandidateEntities(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
    ): List<MemoryStore.SearchHit.EntityHit> {
        val lookupNameOrder = retrievalPlan.entityLookupQueries(request.source)
            .map { it.normalizeMemoryText() }
            .filter { it.isNotBlank() }
            .distinct()
        val lookupNames = lookupNameOrder.toSet()

        val exactHits = store.findEntitiesByNormalizedNames(request.namespace, lookupNames)
            .filter { it.status == MemoryEntity.Status.ACTIVE }
            .map { MemoryStore.SearchHit.EntityHit(it, score = 1.0) }
            .sortedWith(writeEntityCandidateComparator(lookupNameOrder))

        val searchQuery = retrievalPlan.entitySearchQuery(request.source)
        val searchHits = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            store.search(
                MemoryStore.SearchRequest(
                    query = searchQuery,
                    namespace = request.namespace,
                    scopes = setOf(MemoryStore.SearchScope.ENTITIES),
                    embedding = embeddingIndexer.searchEmbedding(searchQuery),
                    limit = maxOf(ENTITY_CANDIDATE_LIMIT, lookupNames.size * 2),
                ),
            ).filterIsInstance<MemoryStore.SearchHit.EntityHit>()
                .filter { it.entity.status == MemoryEntity.Status.ACTIVE }
        }

        val hits = (exactHits + searchHits)
            .distinctBy { it.entity.id }

        log.info {
            "Memory write entity candidates: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "lookupNames=${lookupNames.joinToString("|")} searchQuery=${searchQuery.oneLineForLog(300)} " +
                "exact=${exactHits.size} searched=${searchHits.size} result=${hits.size} " +
                "entities=${hits.joinToString("|") { "${it.entity.id.value}:${it.entity.entityType.name}:${it.entity.canonicalName.oneLineForLog(120)}" }.ifBlank { "none" }}"
        }

        return hits
    }
}

private fun MemorySource.sourceTypeForLog(): String =
    when (this) {
        is MemorySource.ChatTurn -> "chat_turn"
        is MemorySource.ToolOutput -> "tool_output"
        is MemorySource.ImportedNote -> "imported_note"
        is MemorySource.ExternalRecord -> "external_record"
    }

private fun MemorySource.sourceRoleForLog(): String =
    when (this) {
        is MemorySource.ChatTurn -> speakerRole.name
        is MemorySource.ToolOutput -> "TOOL"
        is MemorySource.ImportedNote -> "IMPORT"
        is MemorySource.ExternalRecord -> "EXTERNAL"
    }

private fun MemoryNote.isSupportedBySupersededClaim(
    claim: MemoryClaim,
    entitiesById: Map<MemoryEntity.Id, MemoryEntity>,
): Boolean {
    if (status != MemoryNote.Status.ACTIVE || archivedAt != null) {
        return false
    }
    if (claim.originNoteId == id) {
        return true
    }

    val objectMatches = claim.objectEntityId
        ?.let { objectEntityId ->
            referencesEntity(objectEntityId) ||
                entitiesById[objectEntityId]?.let(::mentionsEntity) == true
        }
        ?: claim.objectValue?.let(::mentionsObjectValue) == true
    if (!objectMatches) {
        return false
    }

    val subjectMatches = referencesEntity(claim.subjectEntityId)
    val evidenceMatches = evidenceRefs
        .mapTo(mutableSetOf()) { it.sourceId }
        .intersect(claim.evidenceRefs.mapTo(mutableSetOf()) { it.sourceId })
        .isNotEmpty()

    return subjectMatches || evidenceMatches
}

private fun MemoryNote.referencesEntity(entityId: MemoryEntity.Id): Boolean =
    anchorEntityId == entityId ||
        entityRefs.any { it.entityId == entityId } ||
        (scope as? MemoryScope.Entity)?.subjectEntityId == entityId

private fun MemoryNote.mentionsEntity(entity: MemoryEntity): Boolean {
    val names = (listOf(entity.canonicalName, entity.normalizedName) + entity.aliases.map { it.text })
        .filter { it.isNotBlank() }
    val haystack = noteSemanticTextForMatching()
    return names.any { it.lowercase() in haystack }
}

private fun MemoryNote.mentionsObjectValue(objectValue: JsonElement): Boolean {
    val text = objectValue.compactForLog(Int.MAX_VALUE).lowercase().trim()
    return text.isNotBlank() && text in noteSemanticTextForMatching()
}

private fun MemoryNote.noteSemanticTextForMatching(): String =
    listOf(
        title,
        summary,
        scope.text,
        keywords.joinToString("\n"),
        tags.joinToString("\n"),
    ).joinToString("\n").lowercase()

private fun MemoryScope.scopeForLog(): String =
    when (this) {
        is MemoryScope.Global -> "global(text=${text.oneLineForLog(120)},basis=${basis.name})"
        is MemoryScope.Project -> "project(projectId=${projectId.value},text=${text.oneLineForLog(120)},basis=${basis.name})"
        is MemoryScope.Conversation -> "conversation(conversationId=${conversationId.value},projectId=${projectId?.value ?: "null"},text=${text.oneLineForLog(120)},basis=${basis.name})"
        is MemoryScope.Entity -> "entity(subject=${subjectEntityId.value},text=${text.oneLineForLog(120)},basis=${basis.name})"
        is MemoryScope.Environment -> "environment(environment=${environment.oneLineForLog(120)},text=${text.oneLineForLog(120)},basis=${basis.name})"
        is MemoryScope.Document -> "document(documentRef=${documentRef.oneLineForLog(120)},text=${text.oneLineForLog(120)},basis=${basis.name})"
    }

private fun kotlinx.serialization.json.JsonElement.compactForLog(maxChars: Int = 220): String {
    val value = if (this is JsonPrimitive && isString) {
        content
    } else {
        toString()
    }
    return value.oneLineForLog(maxChars)
}

private fun String.oneLineForLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) {
        return oneLine
    }
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

class DefaultDirectStructuredMemoryWriteMaterializer(
    private val idFactory: MemoryIdFactory,
) : DirectStructuredMemoryWriteMaterializer {
    override fun materialize(input: DirectStructuredMemoryWriteMaterialization): MemoryUpdateBatch {
        val runId = idFactory.newRunId()
        val targetClaimById = input.retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .associateBy { it.id }
        val referencedEntityIds = input.claimOps
            .filter { it.action != MemoryReconciliationAction.NOOP }
            .mapNotNull { it.candidate }
            .flatMap { candidate -> listOfNotNull(candidate.subjectEntityId, candidate.objectEntityId) }
            .toSet() + input.claimOps
            .filter { it.action != MemoryReconciliationAction.NOOP }
            .mapNotNull { it.targetClaimId?.let(targetClaimById::get) }
            .flatMap { claim -> listOfNotNull(claim.subjectEntityId, claim.objectEntityId) }
            .toSet() + input.noteOps
            .filter { it.action != MemoryReconciliationAction.NOOP }
            .mapNotNull { it.candidate }
            .flatMap { candidate -> candidate.entityRefs.map { it.entityId } }
            .toSet() + input.actionItemOps
            .filter { it.action != MemoryActionItemUpdateOp.Action.NOOP }
            .mapNotNull { it.actionItem }
            .flatMap { actionItem ->
                buildList {
                    addAll(listOfNotNull(actionItem.ownerEntityId, actionItem.assigneeEntityId))
                    addAll(actionItem.relatedEntityIds)
                }
            }
            .toSet()
        val predicateDefinitions = input.claimOps
            .filter { it.action != MemoryReconciliationAction.NOOP }
            .mapNotNull { it.candidate?.predicatePolicy }
            .map { it.scopedTo(input.request.namespace) }
            .distinctBy { it.id }
        val entities = input.entityOps
            .mapNotNull { it.toEntity(input, idFactory) }
            .filter { it.id in referencedEntityIds }
            .plus(input.existingReferencedEntityPatches(referencedEntityIds))
            .mergeEntitiesById()
        val notes = input.noteOps.flatMap { it.toNotes(input, runId, idFactory) }
        val claims = input.claimOps.flatMap { it.toClaims(input, runId, idFactory) }
        val actionItems = input.actionItemOps.flatMap { it.toActionItems(input, runId, idFactory) }
        val run = MemoryRun(
            id = runId,
            namespace = input.request.namespace,
            runType = input.runType(
                notes = notes,
                claims = claims,
                actionItems = actionItems,
            ),
            triggerMode = input.request.triggerMode,
            parentRunId = input.request.parentRunId,
            summary = input.summary(
                notes = notes,
                claims = claims,
                actionItems = actionItems,
                entities = entities,
            ),
            sourceIds = listOf(input.request.source.id),
            retrievedItemRefs = input.retrievedHits.map { it.toItemRef() },
            retrievalBudget = input.retrievalPlan?.retrievalBudget,
            inputHash = input.inputHash(),
            output = input.toRunOutput(
                entities = entities,
                notes = notes,
                claims = claims,
                actionItems = actionItems,
                predicateDefinitions = predicateDefinitions,
            ),
            appliedOps = input.toAppliedOps(
                entities = entities,
                notes = notes,
                claims = claims,
                actionItems = actionItems,
                predicateDefinitions = predicateDefinitions,
            ),
            llmCalls = currentMemoryRunLlmCalls(),
            latencyMs = input.completedAt.toEpochMilliseconds() - input.startedAt.toEpochMilliseconds(),
            status = MemoryRun.Status.SUCCESS,
            createdAt = input.startedAt,
            startedAt = input.startedAt,
            completedAt = input.completedAt,
        )

        return MemoryUpdateBatch(
            predicateDefinitions = predicateDefinitions,
            runs = listOf(run),
            entities = entities,
            notes = notes,
            claims = claims,
            actionItems = actionItems,
        )
    }

    private fun MemoryEntityCanonicalizationOp.toEntity(
        input: DirectStructuredMemoryWriteMaterialization,
        idFactory: MemoryIdFactory,
    ): MemoryEntity? {
        if (action != MemoryEntityCanonicalizationOp.Action.CREATE_NEW) {
            return null
        }

        val entityDraft = requireNotNull(newEntity) {
            "CREATE_NEW entity op for '$mention' must include newEntity"
        }
        val entityId = entityId ?: idFactory.newEntityId()
        val now = input.completedAt

        return MemoryEntity(
            id = entityId,
            namespace = input.request.namespace,
            entityType = entityDraft.entityType,
            observedTypes = setOf(entityDraft.entityType),
            canonicalName = entityDraft.canonicalName,
            normalizedName = entityDraft.canonicalName.normalizeMemoryText(),
            summary = entityDraft.identityOnlySummary(),
            aliases = aliasText?.let {
                listOf(
                    MemoryEntity.Alias(
                        text = it,
                        normalizedText = it.normalizeMemoryText(),
                        sourceId = input.request.source.id,
                        confidence = confidence,
                        createdAt = now,
                    ),
                )
            } ?: emptyList(),
            firstSeenAt = input.request.source.observedAt,
            lastSeenAt = input.request.source.observedAt,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun List<MemoryEntity>.mergeEntitiesById(): List<MemoryEntity> =
        groupBy { it.id }.values.map { entities ->
            val primary = entities.first()
            if (entities.size == 1) {
                return@map primary.copy(observedTypes = primary.observedTypes + primary.entityType)
            }

            val extraAliases = entities.drop(1).map { entity ->
                MemoryEntity.Alias(
                    text = entity.canonicalName,
                    normalizedText = entity.normalizedName,
                    confidence = 1.0,
                    createdAt = entity.createdAt,
                )
            }
            primary.copy(
                observedTypes = primary.mergedObservedTypesWith(entities.drop(1)),
                summary = primary.summary ?: entities.drop(1).firstNotNullOfOrNull { it.summary },
                aliases = (primary.aliases + entities.drop(1).flatMap { it.aliases } + extraAliases)
                    .distinctBy { it.normalizedText },
                firstSeenAt = entities.minOf { it.firstSeenAt },
                lastSeenAt = entities.maxOf { it.lastSeenAt },
                updatedAt = entities.maxOf { it.updatedAt },
            )
        }

    private fun DirectStructuredMemoryWriteMaterialization.existingReferencedEntityPatches(
        referencedEntityIds: Set<MemoryEntity.Id>,
    ): List<MemoryEntity> {
        if (referencedEntityIds.isEmpty()) return emptyList()

        val createdEntityIds = entityOps
            .filter { it.action == MemoryEntityCanonicalizationOp.Action.CREATE_NEW }
            .mapNotNullTo(mutableSetOf()) { it.entityId }
        return retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .map { it.entity }
            .filter { it.id in referencedEntityIds && it.id !in createdEntityIds }
            .distinctBy { it.id }
            .map { entity ->
                entity.copy(
                    summary = entity.identityOnlySummary(),
                    lastSeenAt = request.source.observedAt,
                    updatedAt = completedAt,
                )
            }
    }

    private fun MemoryNoteReconciliationOp.toNotes(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
    ): List<MemoryNote> {
        return when (action) {
            MemoryReconciliationAction.INSERT -> {
                val candidate = requireNotNull(candidate) {
                    "INSERT note op must include candidate"
                }
                listOf(candidate.toNote(input, runId, idFactory, linksToCreate))
            }

            MemoryReconciliationAction.UPDATE -> listOf(patchedTargetNote(input, defaultStatus = null))
            MemoryReconciliationAction.RETRACT -> listOf(patchedTargetNote(input, defaultStatus = MemoryNote.Status.RETRACTED))
            MemoryReconciliationAction.SUPERSEDE -> {
                val target = patchedTargetNote(input, defaultStatus = MemoryNote.Status.SUPERSEDED)
                val candidate = requireNotNull(candidate) {
                    "SUPERSEDE note op must include candidate"
                }
                listOf(
                    target,
                    candidate.toNote(input, runId, idFactory, linksToCreate, supersedesNoteId = target.id),
                )
            }

            MemoryReconciliationAction.NOOP -> emptyList()
        }
    }

    private fun MemoryNoteReconciliationOp.patchedTargetNote(
        input: DirectStructuredMemoryWriteMaterialization,
        defaultStatus: MemoryNote.Status?,
    ): MemoryNote {
        val targetId = requireNotNull(targetNoteId) {
            "$action note op must include targetNoteId"
        }
        val target = input.retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.NoteHit>()
            .map { it.note }
            .firstOrNull { it.id == targetId }

        requireNotNull(target) {
            "$action target note ${targetId.value} was not present in retrieved hits"
        }

        val newLinks = linksToCreate.map {
            MemoryNote.Link(
                targetNoteId = it.toNoteId,
                linkType = it.linkType,
                linkWeight = it.linkWeight,
            )
        }

        return target.copy(
            title = updatedNote?.title ?: target.title,
            summary = updatedNote?.summary ?: target.summary,
            scope = updatedNote?.scope ?: target.scope,
            status = updatedNote?.status ?: defaultStatus ?: target.status,
            maturity = updatedNote?.maturity ?: target.maturity,
            maturityScore = updatedNote?.maturityScore ?: target.maturityScore,
            linkedNotes = (target.linkedNotes + newLinks).distinctBy { "${it.targetNoteId.value}:${it.linkType.name}" },
            updatedAt = input.completedAt,
        )
    }

    private fun MemoryNoteCandidate.toNote(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
        linksToCreate: List<MemoryNoteReconciliationOp.LinkDraft>,
        supersedesNoteId: MemoryNote.Id? = null,
    ): MemoryNote {
        val quote = requireNotNull(evidenceQuote?.takeIf { it.isNotBlank() }) {
            "INSERT note candidate must include evidenceQuote"
        }
        require(MemoryEvidenceQuoteMatcher.matches(input.request.source.contentText, quote)) {
            "INSERT note evidenceQuote must be copied from the source text"
        }

        val now = input.completedAt
        val links = linksToCreate.map {
            MemoryNote.Link(
                targetNoteId = it.toNoteId,
                linkType = it.linkType,
                linkWeight = it.linkWeight,
            )
        }

        return MemoryNote(
            id = idFactory.newNoteId(),
            namespace = input.request.namespace,
            noteType = noteType,
            title = title,
            summary = summary,
            scope = scope,
            status = status,
            maturity = maturity,
            maturityScore = if (maturity == MemoryNote.Maturity.FRESH) 0.0 else confidence,
            anchorEntityId = entityRefs.firstOrNull {
                it.role == MemoryNote.EntityRef.Role.PRIMARY ||
                    it.role == MemoryNote.EntityRef.Role.SUBJECT ||
                    it.role == MemoryNote.EntityRef.Role.OWNER
            }?.entityId,
            entityRefs = entityRefs,
            keywords = keywords,
            tags = tags,
            candidateClaimHints = candidateClaims,
            confidence = confidence,
            importance = importance,
            validFrom = validFrom,
            validTo = validTo,
            supersedesNoteId = supersedesNoteId,
            createdFromRunId = runId,
            evidenceRefs = listOf(
                MemoryEvidenceRef(
                    sourceId = input.request.source.id,
                    kind = evidenceKind,
                    cachedQuote = quote.take(500),
                ),
            ),
            linkedNotes = links,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun MemoryClaimReconciliationOp.toClaims(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
    ): List<MemoryClaim> {
        return when (action) {
            MemoryReconciliationAction.INSERT -> {
                val candidate = requireNotNull(candidate) {
                    "INSERT claim op must include candidate"
                }
                listOf(candidate.toClaim(input, runId, idFactory))
            }

            MemoryReconciliationAction.UPDATE -> listOf(patchedTargetClaim(input, defaultStatus = null))
            MemoryReconciliationAction.RETRACT -> listOf(patchedTargetClaim(input, defaultStatus = MemoryClaim.Status.RETRACTED))
            MemoryReconciliationAction.SUPERSEDE -> {
                val target = patchedTargetClaim(input, defaultStatus = MemoryClaim.Status.SUPERSEDED)
                val candidate = requireNotNull(candidate) {
                    "SUPERSEDE claim op must include candidate"
                }
                listOf(
                    target,
                    candidate.toClaim(input, runId, idFactory, supersedesClaimId = target.id),
                )
            }

            MemoryReconciliationAction.NOOP -> emptyList()
        }
    }

    private fun MemoryClaimReconciliationOp.patchedTargetClaim(
        input: DirectStructuredMemoryWriteMaterialization,
        defaultStatus: MemoryClaim.Status?,
    ): MemoryClaim {
        val targetId = requireNotNull(targetClaimId) {
            "$action claim op must include targetClaimId"
        }
        val target = input.retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .firstOrNull { it.id == targetId }

        requireNotNull(target) {
            "$action target claim ${targetId.value} was not present in retrieved hits"
        }

        return target.copy(
            status = updatedClaim?.status ?: defaultStatus ?: target.status,
            validFrom = updatedClaim?.validFrom ?: target.validFrom,
            validTo = updatedClaim?.validTo ?: target.validTo,
            updatedAt = input.completedAt,
        )
    }

    private fun MemoryClaimCandidate.toClaim(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
        supersedesClaimId: MemoryClaim.Id? = null,
    ): MemoryClaim {
        require((objectEntityId != null) != (objectValue != null)) {
            "Claim candidate must have exactly one of objectEntityId or objectValue"
        }
        val quote = requireNotNull(evidenceQuote?.takeIf { it.isNotBlank() }) {
            "INSERT claim candidate must include evidenceQuote"
        }
        require(MemoryEvidenceQuoteMatcher.matches(input.request.source.contentText, quote)) {
            "INSERT claim evidenceQuote must be copied from the source text"
        }

        val now = input.completedAt
        return MemoryClaim(
            id = idFactory.newClaimId(),
            namespace = input.request.namespace,
            subjectEntityId = subjectEntityId,
            predicate = predicate,
            predicateFamily = predicateFamily,
            predicatePolicy = predicatePolicy,
            objectEntityId = objectEntityId,
            objectValue = objectValue,
            normalizedText = normalizedText,
            contextText = contextText,
            scope = scope,
            qualifiers = qualifiers,
            confidence = confidence,
            importance = importance,
            status = MemoryClaim.Status.ACTIVE,
            validFrom = validFrom,
            validTo = validTo,
            originNoteId = originNoteId,
            firstSeenAt = input.request.source.observedAt,
            lastSeenAt = input.request.source.observedAt,
            supersedesClaimId = supersedesClaimId,
            createdFromRunId = runId,
            evidenceRefs = listOf(
                MemoryEvidenceRef(
                    sourceId = input.request.source.id,
                    kind = evidenceKind,
                    cachedQuote = quote.take(500),
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun MemoryActionItemUpdateOp.toActionItems(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
    ): List<MemoryActionItem> =
        when (action) {
            MemoryActionItemUpdateOp.Action.INSERT -> {
                val draft = requireNotNull(actionItem) {
                    "INSERT actionItem op must include actionItem draft"
                }
                listOf(draft.toActionItem(input, runId, idFactory))
            }

            MemoryActionItemUpdateOp.Action.UPDATE -> listOf(patchedTargetActionItem(input, defaultStatus = null))
            MemoryActionItemUpdateOp.Action.CLOSE -> listOf(patchedTargetActionItem(input, defaultStatus = MemoryActionItem.Status.DONE))
            MemoryActionItemUpdateOp.Action.CANCEL -> listOf(patchedTargetActionItem(input, defaultStatus = MemoryActionItem.Status.CANCELLED))
            MemoryActionItemUpdateOp.Action.NOOP -> emptyList()
        }

    private fun MemoryActionItemUpdateOp.patchedTargetActionItem(
        input: DirectStructuredMemoryWriteMaterialization,
        defaultStatus: MemoryActionItem.Status?,
    ): MemoryActionItem {
        val targetId = requireNotNull(targetActionItemId) {
            "$action actionItem op must include targetActionItemId"
        }
        val target = input.retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
            .map { it.actionItem }
            .firstOrNull { it.id == targetId }

        requireNotNull(target) {
            "$action target actionItem ${targetId.value} was not present in retrieved hits"
        }

        val draft = actionItem
        val status = defaultStatus ?: draft?.status ?: target.status
        return target.copy(
            ownerEntityId = draft?.ownerEntityId ?: target.ownerEntityId,
            assigneeEntityId = draft?.assigneeEntityId ?: target.assigneeEntityId,
            title = draft?.title?.takeIf { it.isNotBlank() } ?: target.title,
            description = draft?.description ?: target.description,
            status = status,
            priority = draft?.priority ?: target.priority,
            dueAt = draft?.dueAt ?: target.dueAt,
            scope = draft?.scope ?: target.scope,
            acceptanceCriteria = draft?.acceptanceCriteria?.takeIf { it.isNotEmpty() } ?: target.acceptanceCriteria,
            blockers = draft?.blockers ?: target.blockers,
            relatedEntityIds = (target.relatedEntityIds + (draft?.relatedEntityIds ?: emptyList())).distinct(),
            confidence = maxOf(target.confidence, draft?.confidence ?: 0.0),
            evidenceRefs = (target.evidenceRefs + draft.toActionItemEvidenceRef(input)).distinctBy {
                "${it.sourceId.value}:${it.cachedQuote}"
            },
            updatedAt = input.completedAt,
            closedAt = if (status == MemoryActionItem.Status.DONE || status == MemoryActionItem.Status.CANCELLED) {
                input.completedAt
            } else {
                target.closedAt
            },
        )
    }

    private fun MemoryActionItemUpdateOp.Draft.toActionItem(
        input: DirectStructuredMemoryWriteMaterialization,
        runId: MemoryRun.Id,
        idFactory: MemoryIdFactory,
    ): MemoryActionItem {
        val quote = requireNotNull(evidenceQuote?.takeIf { it.isNotBlank() }) {
            "INSERT actionItem draft must include evidenceQuote"
        }
        require(MemoryEvidenceQuoteMatcher.matches(input.request.source.contentText, quote)) {
            "INSERT actionItem evidenceQuote must be copied from the source text"
        }

        val now = input.completedAt
        return MemoryActionItem(
            id = idFactory.newActionItemId(),
            namespace = input.request.namespace,
            ownerEntityId = ownerEntityId,
            assigneeEntityId = assigneeEntityId,
            title = title,
            description = description,
            status = status,
            priority = priority,
            dueAt = dueAt,
            scope = scope,
            acceptanceCriteria = acceptanceCriteria,
            blockers = blockers,
            relatedEntityIds = relatedEntityIds.distinct(),
            originNoteId = originNoteId,
            createdFromRunId = runId,
            confidence = confidence,
            evidenceRefs = listOf(
                MemoryEvidenceRef(
                    sourceId = input.request.source.id,
                    kind = evidenceKind,
                    cachedQuote = quote.take(500),
                ),
            ),
            createdAt = now,
            updatedAt = now,
            closedAt = if (status == MemoryActionItem.Status.DONE || status == MemoryActionItem.Status.CANCELLED) now else null,
        )
    }

    private fun MemoryActionItemUpdateOp.Draft?.toActionItemEvidenceRef(
        input: DirectStructuredMemoryWriteMaterialization,
    ): List<MemoryEvidenceRef> {
        val quote = this?.evidenceQuote?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!MemoryEvidenceQuoteMatcher.matches(input.request.source.contentText, quote)) {
            return emptyList()
        }
        return listOf(
            MemoryEvidenceRef(
                sourceId = input.request.source.id,
                kind = evidenceKind,
                cachedQuote = quote.take(500),
            )
        )
    }
}

private fun DirectStructuredMemoryWriteMaterialization.toRunOutput(
    entities: List<MemoryEntity>,
    notes: List<MemoryNote>,
    claims: List<MemoryClaim>,
    actionItems: List<MemoryActionItem>,
    predicateDefinitions: List<MemoryPredicateDefinition>,
): JsonElement =
    buildJsonObject {
        put("kind", "direct_structured_memory_write")
        put("sourceId", request.source.id.value)
        put("sourceHash", request.source.contentHash)
        put("sourceChars", request.source.contentText.length)
        put("triggerMode", request.triggerMode.name)
        request.parentRunId?.let { put("parentRunId", it.value) }
        put("routeDecision", memoryRunJson.encodeToJsonElement(routeDecision))
        retrievalPlan?.let { put("retrievalPlan", memoryRunJson.encodeToJsonElement(it)) }
        put("predicateCatalogSize", predicateCatalog.size)
        putJsonArray("retrievedItemRefs") {
            retrievedHits.map { it.toItemRef() }.forEach { add(memoryRunJson.encodeToJsonElement(it)) }
        }
        put("entityOps", memoryRunJson.encodeToJsonElement(entityOps))
        put("noteCandidates", memoryRunJson.encodeToJsonElement(noteCandidates))
        put("rawNoteOps", memoryRunJson.encodeToJsonElement(rawNoteOps))
        put("noteOps", memoryRunJson.encodeToJsonElement(noteOps))
        put("claimCandidates", memoryRunJson.encodeToJsonElement(claimCandidates))
        put("rawClaimOps", memoryRunJson.encodeToJsonElement(rawClaimOps))
        put("claimOps", memoryRunJson.encodeToJsonElement(claimOps))
        put("rawActionItemOps", memoryRunJson.encodeToJsonElement(rawActionItemOps))
        put("actionItemOps", memoryRunJson.encodeToJsonElement(actionItemOps))
        putJsonArray("appliedPredicateDefinitionIds") {
            predicateDefinitions.forEach { add(JsonPrimitive(it.id.value)) }
        }
        putJsonArray("appliedEntityIds") {
            entities.forEach { add(JsonPrimitive(it.id.value)) }
        }
        putJsonArray("appliedNoteIds") {
            notes.forEach { add(JsonPrimitive(it.id.value)) }
        }
        putJsonArray("appliedClaimIds") {
            claims.forEach { add(JsonPrimitive(it.id.value)) }
        }
        putJsonArray("appliedTaskIds") {
            actionItems.forEach { add(JsonPrimitive(it.id.value)) }
        }
    }

private fun DirectStructuredMemoryWriteMaterialization.toAppliedOps(
    entities: List<MemoryEntity>,
    notes: List<MemoryNote>,
    claims: List<MemoryClaim>,
    actionItems: List<MemoryActionItem>,
    predicateDefinitions: List<MemoryPredicateDefinition>,
): JsonArray =
    buildJsonArray {
        add(
            buildJsonObject {
                put("op", "route")
                put("decision", routeDecision.decision.name)
                put("memoryTypes", routeDecision.memoryTypes.joinToString("|") { it.name })
                put("reason", routeDecision.reason)
            }
        )
        predicateDefinitions.forEach { definition ->
            add(
                buildJsonObject {
                    put("op", "upsert_predicate_definition")
                    put("id", definition.id.value)
                    put("predicate", definition.predicate)
                }
            )
        }
        entities.forEach { entity ->
            add(
                buildJsonObject {
                    put("op", "upsert_entity")
                    put("id", entity.id.value)
                    put("type", entity.entityType.name)
                    put("name", entity.canonicalName)
                }
            )
        }
        notes.forEach { note ->
            add(
                buildJsonObject {
                    put("op", "upsert_note")
                    put("id", note.id.value)
                    put("type", note.noteType.name)
                    put("status", note.status.name)
                    put("title", note.title)
                }
            )
        }
        claims.forEach { claim ->
            add(
                buildJsonObject {
                    put("op", "upsert_claim")
                    put("id", claim.id.value)
                    put("subjectEntityId", claim.subjectEntityId.value)
                    put("predicate", claim.predicate)
                    claim.objectEntityId?.let { put("objectEntityId", it.value) }
                    put("status", claim.status.name)
                    put("text", claim.normalizedText)
                }
            )
        }
        actionItems.forEach { actionItem ->
            add(
                buildJsonObject {
                    put("op", "upsert_task")
                    put("id", actionItem.id.value)
                    put("status", actionItem.status.name)
                    put("title", actionItem.title)
                }
            )
        }
    }

private fun MemoryRouteDecision.shouldRunDirectStructuredWrite(): Boolean {
    return decision == MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE ||
        decision == MemoryRouteDecision.Decision.MIXED
}

private fun MemoryRouteDecision.shouldRunNoteWrite(): Boolean {
    return decision == MemoryRouteDecision.Decision.NOTE_WRITE ||
        decision == MemoryRouteDecision.Decision.MIXED
}

private fun MemoryWriteRetrievalPlan.searchQuery(source: MemorySource): String {
    return (entityQueries + textQueries)
        .semanticSearchTerms()
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { source.contentText }
}

private fun MemoryWriteRetrievalPlan.entityLookupQueries(source: MemorySource): List<String> {
    return buildList {
        addAll(entityQueries.semanticSearchTerms())
        if (source.requiresStableUserEntity()) {
            add("user")
        }
    }.distinct()
}

private fun MemoryWriteRetrievalPlan.entitySearchQuery(source: MemorySource): String {
    return (entityLookupQueries(source) + source.contentText)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun List<String>.semanticSearchTerms(): List<String> =
    map { it.withoutRuntimeSearchIds() }
        .filter { it.isNotBlank() }
        .distinct()

private fun String.withoutRuntimeSearchIds(): String =
    replace(memoryRuntimeSearchIdRegex, " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun MemoryWriteRetrievalPlan.searchScopes(): Set<MemoryStore.SearchScope> {
    val scopes = memoryTypes.mapNotNull { it.toSearchScope() }.toSet()
    val entityAwareScopes = if (entityQueries.isNotEmpty()) {
        scopes + MemoryStore.SearchScope.ENTITIES
    } else {
        scopes
    }
    return entityAwareScopes.ifEmpty { setOf(MemoryStore.SearchScope.ALL) }
}

private fun MemoryWriteRetrievalPlan.limit(): Int {
    val configuredLimit = listOf(
        retrievalBudget.claims,
        retrievalBudget.notes,
        retrievalBudget.actionItems,
        retrievalBudget.sources,
        retrievalBudget.episodes,
    ).filter { it > 0 }.sum()

    return configuredLimit.takeIf { it > 0 } ?: 10
}

private fun MemorySemanticType.toSearchScope(): MemoryStore.SearchScope? {
    return when (this) {
        MemorySemanticType.CLAIM -> MemoryStore.SearchScope.CLAIMS
        MemorySemanticType.NOTE -> MemoryStore.SearchScope.NOTES
        MemorySemanticType.ACTION_ITEM -> MemoryStore.SearchScope.ACTION_ITEMS
        MemorySemanticType.PROFILE -> MemoryStore.SearchScope.PROFILES
        MemorySemanticType.SOURCE -> MemoryStore.SearchScope.SOURCES
        MemorySemanticType.ENTITY -> MemoryStore.SearchScope.ENTITIES
        MemorySemanticType.EPISODE -> MemoryStore.SearchScope.EPISODES
    }
}

private fun MemoryStore.SearchHit.toItemRef(): MemoryItemRef {
    return when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.ActionItemHit -> MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }
}

private fun MemoryStore.SearchHit.itemKey(): String =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> "source:${source.id.value}"
        is MemoryStore.SearchHit.EntityHit -> "entity:${entity.id.value}"
        is MemoryStore.SearchHit.ClaimHit -> "claim:${claim.id.value}"
        is MemoryStore.SearchHit.NoteHit -> "note:${note.id.value}"
        is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${actionItem.id.value}"
        is MemoryStore.SearchHit.ProfileHit -> "profile:${profile.id.value}"
        is MemoryStore.SearchHit.EpisodeHit -> "episode:${episode.id.value}"
        is MemoryStore.SearchHit.RunHit -> "run:${run.id.value}"
    }

private fun writeEntityCandidateComparator(
    lookupNames: List<String>,
): Comparator<MemoryStore.SearchHit.EntityHit> =
    compareByDescending<MemoryStore.SearchHit.EntityHit> { it.score }
        .thenBy { it.entity.writeEntityCandidateLookupRank(lookupNames) }
        .thenBy { it.entity.entityType.name }
        .thenBy { it.entity.canonicalName.stableWriteEntityCandidateSortText() }
        .thenBy { it.entity.id.value }

private fun MemoryEntity.writeEntityCandidateLookupRank(lookupNames: List<String>): Int {
    val rank = lookupNames.indexOfFirst { lookupName ->
        normalizedName == lookupName || aliases.any { it.normalizedText == lookupName }
    }
    return rank.takeIf { it >= 0 } ?: Int.MAX_VALUE
}

private fun String.stableWriteEntityCandidateSortText(): String =
    lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

private fun MemorySource.requiresStableUserEntity(): Boolean =
    this is MemorySource.ChatTurn && speakerRole == MemorySource.ActorRole.USER

private fun DirectStructuredMemoryWriteMaterialization.summary(
    notes: List<MemoryNote>,
    claims: List<MemoryClaim>,
    actionItems: List<MemoryActionItem>,
    entities: List<MemoryEntity>,
): String {
    return when {
        routeDecision.shouldRunDirectStructuredWrite() || routeDecision.shouldRunNoteWrite() ->
            structuredWriteSummary(notes, claims, actionItems, entities)

        else -> "Direct structured write skipped by router: ${routeDecision.decision}"
    }
}

private fun DirectStructuredMemoryWriteMaterialization.structuredWriteSummary(
    notes: List<MemoryNote>,
    claims: List<MemoryClaim>,
    actionItems: List<MemoryActionItem>,
    entities: List<MemoryEntity>,
): String {
    val noops = listOf(
        "notes=${noteOps.count { it.action == MemoryReconciliationAction.NOOP }}",
        "claims=${claimOps.count { it.action == MemoryReconciliationAction.NOOP }}",
        "actionItems=${actionItemOps.count { it.action == MemoryActionItemUpdateOp.Action.NOOP }}",
    ).joinToString(", ")
    val applied = "notes=${notes.size}, claims=${claims.size}, actionItems=${actionItems.size}, entities=${entities.size}"

    return if (notes.isEmpty() && claims.isEmpty() && actionItems.isEmpty() && entities.isEmpty()) {
        "Direct structured write applied no durable changes; noop ops: $noops"
    } else {
        "Direct structured write applied: $applied; noop ops: $noops"
    }
}

private fun DirectStructuredMemoryWriteMaterialization.runType(
    notes: List<MemoryNote>,
    claims: List<MemoryClaim>,
    actionItems: List<MemoryActionItem>,
): MemoryRun.Type =
    when {
        actionItems.isNotEmpty() && notes.isEmpty() && claims.isEmpty() -> MemoryRun.Type.UPDATE_ACTION_ITEMS

        notes.isNotEmpty() && claims.isEmpty() -> MemoryRun.Type.RECONCILE_NOTES

        claims.isNotEmpty() || claimOps.isNotEmpty() -> MemoryRun.Type.RECONCILE_CLAIMS

        noteOps.isNotEmpty() -> MemoryRun.Type.RECONCILE_NOTES

        actionItemOps.isNotEmpty() -> MemoryRun.Type.UPDATE_ACTION_ITEMS

        else -> MemoryRun.Type.ROUTE
    }

private fun DirectStructuredMemoryWriteMaterialization.inputHash(): String =
    listOf(
        request.namespace.value,
        request.source.id.value,
        request.source.contentHash,
        routeDecision.decision.name,
        routeDecision.memoryTypes.map { it.name }.sorted().joinToString(","),
        retrievalPlan?.inputHashText().orEmpty(),
    ).joinToString("\n").sha256ForMemory()

private fun MemoryWriteRetrievalPlan.inputHashText(): String =
    listOf(
        needRetrieval.toString(),
        entityQueries.joinToString("\u001F"),
        textQueries.joinToString("\u001F"),
        predicateHints.joinToString("\u001F"),
        memoryTypes.map { it.name }.sorted().joinToString(","),
        timeWindow?.toString().orEmpty(),
        retrievalBudget.toString(),
    ).joinToString("\u001E")

private fun String.sha256ForMemory(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private fun String.normalizeMemoryText(): String {
    return trim().lowercase()
}

private const val ENTITY_CANDIDATE_LIMIT = 8
