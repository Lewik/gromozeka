package com.gromozeka.domain.model.memory

import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant

data class MemoryThreadContext(
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val targetMessageId: Conversation.Message.Id,
    val messages: List<Conversation.Message>,
)

/**
 * Input for the first executable memory write slice.
 *
 * The source is already captured as durable evidence. The pipeline decides
 * whether it should become structured memory and which updates must be applied.
 * Thread context is interpretation-only: the target source remains the only
 * write target and evidence anchor.
 */
data class DirectStructuredMemoryWriteRequest(
    val namespace: MemoryNamespace,
    val source: MemorySource,
    val threadContext: MemoryThreadContext? = null,
    val triggerMode: MemoryRun.TriggerMode = MemoryRun.TriggerMode.HOT_PATH,
    val parentRunId: MemoryRun.Id? = null,
)

/**
 * Observable output of the direct structured write slice.
 *
 * This result is intentionally verbose: it exposes every planned stage so the
 * first implementation can be debugged without reverse-engineering hidden state.
 */
data class DirectStructuredMemoryWriteResult(
    val sourceBatch: MemoryUpdateBatch,
    val routeDecision: MemoryRouteDecision,
    val predicateCatalog: MemoryPredicateCatalog = emptyList(),
    val retrievalPlan: MemoryWriteRetrievalPlan?,
    val retrievedHits: List<MemoryStore.SearchHit>,
    val entityOps: List<MemoryEntityCanonicalizationOp>,
    val noteCandidates: List<MemoryNoteCandidate> = emptyList(),
    val rawNoteOps: List<MemoryNoteReconciliationOp> = emptyList(),
    val noteOps: List<MemoryNoteReconciliationOp> = emptyList(),
    val claimCandidates: List<MemoryClaimCandidate>,
    val rawClaimOps: List<MemoryClaimReconciliationOp> = emptyList(),
    val claimOps: List<MemoryClaimReconciliationOp>,
    val rawActionItemOps: List<MemoryActionItemUpdateOp> = emptyList(),
    val actionItemOps: List<MemoryActionItemUpdateOp> = emptyList(),
    val memoryBatch: MemoryUpdateBatch,
)

/**
 * Use-case contract for the direct structured write pipeline.
 */
interface DirectStructuredMemoryWriteService {
    suspend fun write(request: DirectStructuredMemoryWriteRequest): DirectStructuredMemoryWriteResult
}

/**
 * Decides whether a captured source deserves structured memory work.
 */
interface MemoryWriteRouter {
    suspend fun route(request: DirectStructuredMemoryWriteRequest): MemoryRouteDecision
}

/**
 * Plans the effects of an explicit forget/delete request on existing memory.
 */
interface MemoryForgetPlanner {
    suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryForgetPlan
}

/**
 * Plans write-time retrieval before extraction.
 *
 * This is the guardrail that prevents writing new claims without first checking
 * nearby memory for duplicates, contradictions, and scope boundaries.
 */
interface MemoryWriteRetrievalPlanner {
    suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryWriteRetrievalPlan
}

/**
 * Links source mentions to canonical memory entities.
 */
interface MemoryEntityCanonicalizer {
    suspend fun canonicalize(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryEntityCanonicalizationOp>
}

/**
 * Extracts claim candidates from source text after routing, retrieval, and entity linking.
 */
interface MemoryClaimExtractor {
    suspend fun extract(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate>
}

/**
 * Turns claim candidates into explicit insert/update/supersede/retract/noop operations.
 */
interface MemoryClaimReconciler {
    suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimReconciliationOp>
}

/**
 * Builds contextual note candidates from source material after write-time retrieval.
 */
interface MemoryNoteConstructor {
    suspend fun construct(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryNoteCandidate>
}

/**
 * Turns note candidates into explicit insert/update/supersede/retract/noop operations.
 */
interface MemoryNoteReconciler {
    suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        noteCandidates: List<MemoryNoteCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryNoteReconciliationOp>
}

/**
 * Creates or updates operational action item memory from explicit commitments and lifecycle changes.
 */
interface MemoryActionItemUpdater {
    suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryActionItemUpdateOp>
}

/**
 * Rebuilds compact profile projections after source-of-truth memory changed.
 */
interface MemoryProfileUpdater {
    suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        appliedBatch: MemoryUpdateBatch,
        completedAt: Instant,
    ): MemoryUpdateBatch
}
