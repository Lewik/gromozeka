package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimExtractor
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryClaimReconciler
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizer
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteConstructor
import com.gromozeka.domain.model.memory.MemoryNoteReconciliationOp
import com.gromozeka.domain.model.memory.MemoryNoteReconciler
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.MemoryActionItemUpdater
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlanner
import com.gromozeka.domain.model.memory.MemoryWriteRouter
import kotlinx.datetime.Instant

class SequentialMemoryIdFactory(
    private val prefix: String = "memory",
) : MemoryIdFactory {
    private var next = 0

    override fun newEntityId(): MemoryEntity.Id = MemoryEntity.Id(nextId("entity"))

    override fun newClaimId(): MemoryClaim.Id = MemoryClaim.Id(nextId("claim"))

    override fun newNoteId(): MemoryNote.Id = MemoryNote.Id(nextId("note"))

    override fun newActionItemId(): MemoryActionItem.Id = MemoryActionItem.Id(nextId("actionItem"))

    override fun newEpisodeId(): MemoryEpisode.Id = MemoryEpisode.Id(nextId("episode"))

    override fun newRunId(): MemoryRun.Id = MemoryRun.Id(nextId("run"))

    private fun nextId(kind: String): String {
        next += 1
        return "$prefix-$kind-$next"
    }
}

class FixedMemoryClock(
    private val instant: Instant,
) : MemoryClock {
    override fun now(): Instant = instant
}

class FixedMemoryWriteRouter(
    private val decision: MemoryRouteDecision = MemoryRouteDecision(
        decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
        memoryTypes = setOf(MemorySemanticType.CLAIM, MemorySemanticType.ENTITY),
        salience = 1.0,
        reason = "Scripted direct structured write",
    ),
) : MemoryWriteRouter {
    override suspend fun route(request: DirectStructuredMemoryWriteRequest): MemoryRouteDecision = decision
}

class FixedMemoryWriteRetrievalPlanner(
    private val plan: MemoryWriteRetrievalPlan = MemoryWriteRetrievalPlan(
        needRetrieval = true,
        memoryTypes = setOf(MemorySemanticType.CLAIM, MemorySemanticType.ENTITY, MemorySemanticType.SOURCE),
        retrievalBudget = MemoryRetrievalBudget(claims = 5, sources = 5),
    ),
) : MemoryWriteRetrievalPlanner {
    override suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryWriteRetrievalPlan = plan
}

class FixedMemoryEntityCanonicalizer(
    private val entityOps: List<MemoryEntityCanonicalizationOp> = emptyList(),
) : MemoryEntityCanonicalizer {
    override suspend fun canonicalize(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryEntityCanonicalizationOp> = entityOps
}

class FixedMemoryClaimExtractor(
    private val claimCandidates: List<MemoryClaimCandidate> = emptyList(),
) : MemoryClaimExtractor {
    override suspend fun extract(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate> = claimCandidates
}

object InsertOnlyMemoryClaimReconciler : MemoryClaimReconciler {
    override suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimReconciliationOp> {
        return claimCandidates.map {
            MemoryClaimReconciliationOp(
                action = MemoryReconciliationAction.INSERT,
                candidate = it,
                reason = "Scripted first-slice insert",
            )
        }
    }
}

class FixedMemoryNoteConstructor(
    private val noteCandidates: List<MemoryNoteCandidate> = emptyList(),
) : MemoryNoteConstructor {
    override suspend fun construct(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryNoteCandidate> = noteCandidates
}

object InsertOnlyMemoryNoteReconciler : MemoryNoteReconciler {
    override suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        noteCandidates: List<MemoryNoteCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryNoteReconciliationOp> {
        return noteCandidates.map {
            MemoryNoteReconciliationOp(
                action = MemoryReconciliationAction.INSERT,
                candidate = it,
                reason = "Scripted note insert",
            )
        }
    }
}

class FixedMemoryActionItemUpdater(
    private val actionItemOps: List<MemoryActionItemUpdateOp> = emptyList(),
) : MemoryActionItemUpdater {
    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryActionItemUpdateOp> = actionItemOps
}
