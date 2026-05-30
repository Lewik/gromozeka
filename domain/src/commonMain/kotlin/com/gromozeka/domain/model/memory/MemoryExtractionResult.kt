package com.gromozeka.domain.model.memory

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * First routing decision after raw source capture.
 *
 * It decides whether the source contains memory-worthy material and which
 * construction paths should run next.
 */
@Serializable
data class MemoryRouteDecision(
    val decision: Decision = Decision.NOOP,
    val memoryTypes: Set<MemorySemanticType> = emptySet(),
    val salience: Double = 0.0,
    val sourcePolicy: MemorySourceUsagePolicy = MemorySourceUsagePolicy.STANDARD,
    val sourceSearchText: String? = null,
    val reason: String = "",
) {
    @Serializable
    enum class Decision {
        NOOP,
        DIRECT_STRUCTURED_WRITE,
        NOTE_WRITE,
        MIXED,
        FORGET_REQUEST,
    }
}

/**
 * Broad class of memory object used by planners before concrete objects exist.
 */
@Serializable
enum class MemorySemanticType {
    CLAIM,
    NOTE,
    ACTION_ITEM,
    PROFILE,
    SOURCE,
    ENTITY,
    EPISODE,
}

/**
 * Search plan for write-time grounding.
 *
 * Before writing new memory, the system should retrieve nearby entities, claims,
 * notes, and action items to avoid duplicates and detect contradictions.
 */
@Serializable
data class MemoryWriteRetrievalPlan(
    val needRetrieval: Boolean = true,
    val entityQueries: List<String> = emptyList(),
    val textQueries: List<String> = emptyList(),
    val predicateHints: List<String> = emptyList(),
    val memoryTypes: Set<MemorySemanticType> = emptySet(),
    val timeWindow: MemoryTimeWindow? = null,
    val retrievalBudget: MemoryRetrievalBudget = MemoryRetrievalBudget(),
)

/**
 * Entity linking operation for mentions found in new source material.
 *
 * This is the bridge from raw text mentions to stable MemoryEntity anchors.
 */
@Serializable
data class MemoryEntityCanonicalizationOp(
    val mention: String,
    val action: Action,
    val entityId: MemoryEntity.Id? = null,
    val newEntity: NewEntity? = null,
    val aliasText: String? = null,
    val confidence: Double = 0.0,
    val reason: String = "",
) {
    @Serializable
    enum class Action {
        LINK_EXISTING,
        CREATE_NEW,
        ADD_ALIAS,
        NOOP,
    }

    @Serializable
    data class NewEntity(
        val entityType: MemoryEntity.Type = MemoryEntity.Type.OTHER,
        val canonicalName: String,
        val summary: String? = null,
    )
}

/**
 * Proposed atomic claim before reconciliation with existing memory.
 */
@Serializable
data class MemoryClaimCandidate(
    val subjectEntityId: MemoryEntity.Id,
    val predicate: String,
    val predicateFamily: String? = null,
    val predicatePolicy: MemoryPredicateDefinition? = null,
    val objectEntityId: MemoryEntity.Id? = null,
    val objectValue: JsonElement? = null,
    val normalizedText: String,
    val scope: MemoryScope,
    val contextText: String? = null,
    val qualifiers: JsonObject = JsonObject(emptyMap()),
    val confidence: Double = 0.0,
    val importance: Int = 5,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val evidenceQuote: String? = null,
    val evidenceKind: MemoryEvidenceRef.Kind = MemoryEvidenceRef.Kind.DIRECT,
    val evidenceReason: String = "",
    val originNoteId: MemoryNote.Id? = null,
    val reason: String = "",
)

/**
 * Proposed note before reconciliation with existing notes and claims.
 */
@Serializable
data class MemoryNoteCandidate(
    val title: String,
    val summary: String,
    val scope: MemoryScope,
    val noteType: MemoryNote.Type = MemoryNote.Type.CONTEXT,
    val status: MemoryNote.Status = MemoryNote.Status.ACTIVE,
    val entityRefs: List<MemoryNote.EntityRef> = emptyList(),
    val keywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val candidateClaims: JsonArray = JsonArray(emptyList()),
    val confidence: Double = 0.0,
    val importance: Int = 5,
    val maturity: MemoryNote.Maturity = MemoryNote.Maturity.FRESH,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val evidenceQuote: String? = null,
    val evidenceKind: MemoryEvidenceRef.Kind = MemoryEvidenceRef.Kind.SUMMARIZED,
    val evidenceReason: String = "",
    val rationale: String = "",
)

/**
 * Shared write verb for reconciling proposed memory with persisted memory.
 */
@Serializable
enum class MemoryReconciliationAction {
    INSERT,
    UPDATE,
    SUPERSEDE,
    RETRACT,
    NOOP,
}

/**
 * Final claim-level write decision.
 *
 * Reconciliation chooses whether to insert a candidate, update an existing claim,
 * supersede older knowledge, retract invalid knowledge, or do nothing.
 */
@Serializable
data class MemoryClaimReconciliationOp(
    val action: MemoryReconciliationAction,
    val targetClaimId: MemoryClaim.Id? = null,
    val candidate: MemoryClaimCandidate? = null,
    val updatedClaim: Patch? = null,
    val reason: String = "",
) {
    @Serializable
    data class Patch(
        val status: MemoryClaim.Status? = null,
        val validFrom: Instant? = null,
        val validTo: Instant? = null,
    )
}

/**
 * Final note-level write decision.
 *
 * Notes can be inserted, updated, linked to other notes, superseded, retracted,
 * or ignored if they add no durable value.
 */
@Serializable
data class MemoryNoteReconciliationOp(
    val action: MemoryReconciliationAction,
    val targetNoteId: MemoryNote.Id? = null,
    val candidate: MemoryNoteCandidate? = null,
    val updatedNote: Patch? = null,
    val linksToCreate: List<LinkDraft> = emptyList(),
    val reason: String = "",
) {
    @Serializable
    data class Patch(
        val title: String? = null,
        val summary: String? = null,
        val scope: MemoryScope? = null,
        val status: MemoryNote.Status? = null,
        val maturity: MemoryNote.Maturity? = null,
        val maturityScore: Double? = null,
    )

    @Serializable
    data class LinkDraft(
        val toNoteId: MemoryNote.Id,
        val linkType: MemoryNote.Link.Type,
        val linkWeight: Double = 1.0,
    )
}

/**
 * Write decision for action-item-like memory.
 */
@Serializable
data class MemoryActionItemUpdateOp(
    val action: Action,
    val targetActionItemId: MemoryActionItem.Id? = null,
    val actionItem: Draft? = null,
    val reason: String = "",
) {
    @Serializable
    enum class Action {
        INSERT,
        UPDATE,
        CLOSE,
        CANCEL,
        NOOP,
    }

    @Serializable
    data class Draft(
        val title: String,
        val scope: MemoryScope,
        val description: String? = null,
        val ownerEntityId: MemoryEntity.Id? = null,
        val assigneeEntityId: MemoryEntity.Id? = null,
        val status: MemoryActionItem.Status = MemoryActionItem.Status.OPEN,
        val priority: MemoryActionItem.Priority = MemoryActionItem.Priority.NORMAL,
        val dueAt: Instant? = null,
        val acceptanceCriteria: List<String> = emptyList(),
        val blockers: List<String> = emptyList(),
        val relatedEntityIds: List<MemoryEntity.Id> = emptyList(),
        val confidence: Double = 0.0,
        val evidenceQuote: String? = null,
        val evidenceKind: MemoryEvidenceRef.Kind = MemoryEvidenceRef.Kind.DIRECT,
        val evidenceReason: String = "",
        val originNoteId: MemoryNote.Id? = null,
    )
}

/**
 * Proposed replacement projection for an entity profile.
 */
@Serializable
data class MemoryProfileProjection(
    val profileJson: JsonObject = JsonObject(emptyMap()),
    val profileText: String,
    val reason: String = "",
)

/**
 * Proposed experience record extracted from notes or source material.
 */
@Serializable
data class MemoryEpisodeCandidate(
    val ownerEntityId: MemoryEntity.Id? = null,
    val originNoteId: MemoryNote.Id? = null,
    val situation: String,
    val action: String,
    val result: String,
    val lesson: String,
    val tags: List<String> = emptyList(),
    val successScore: Double? = null,
    val reason: String = "",
)

/**
 * Grouped construction output before reconciliation and persistence.
 */
@Serializable
data class MemoryWriteCandidates(
    val entityOps: List<MemoryEntityCanonicalizationOp> = emptyList(),
    val claimCandidates: List<MemoryClaimCandidate> = emptyList(),
    val noteCandidates: List<MemoryNoteCandidate> = emptyList(),
    val actionItemActions: List<MemoryActionItemUpdateOp> = emptyList(),
    val profileProjection: MemoryProfileProjection? = null,
)

/**
 * Lifecycle operation for an existing note during consolidation.
 */
@Serializable
data class MemoryNoteLifecycleOp(
    val noteId: MemoryNote.Id,
    val action: Action,
    val reason: String = "",
) {
    @Serializable
    enum class Action {
        KEEP_ACTIVE,
        MARK_RESOLVED,
        MARK_STALE,
        SUPERSEDE,
        MARK_CONSOLIDATED,
    }
}

/**
 * Output of note consolidation maintenance.
 *
 * Consolidation turns temporary or broad notes into more durable claims, action items,
 * profiles, episodes, and lifecycle changes for the original notes.
 */
@Serializable
data class NoteConsolidationResult(
    val claimCandidates: List<MemoryClaimCandidate> = emptyList(),
    val actionItemActions: List<MemoryActionItemUpdateOp> = emptyList(),
    val profileProjection: MemoryProfileProjection? = null,
    val episodeCandidates: List<MemoryEpisodeCandidate> = emptyList(),
    val noteActions: List<MemoryNoteLifecycleOp> = emptyList(),
    val summary: String = "",
)

/**
 * Conservative maintenance plan for memory hygiene.
 *
 * Repair should prefer explicit planned actions over invisible automatic cleanup.
 */
@Serializable
data class MemoryRepairPlan(
    val repairActions: List<Action> = emptyList(),
    val summary: String = "",
) {
    @Serializable
    data class Action(
        val action: Type,
        val targetType: MemoryItemRef.Type,
        val targetIds: List<String> = emptyList(),
        val reason: String = "",
    ) {
        @Serializable
        enum class Type {
            MERGE_DUPLICATES,
            SUPERSEDE_ITEM,
            ARCHIVE_ITEM,
            REFRESH_PROFILE,
            NOOP,
        }
    }
}

/**
 * Conservative maintenance plan for canonical entity hygiene.
 *
 * Entity maintenance changes anchors, so actions must be explicit about the
 * winning entity and the losing entities that will be relinked into it.
 */
@Serializable
data class MemoryEntityMaintenancePlan(
    val actions: List<Action> = emptyList(),
    val summary: String = "",
) {
    @Serializable
    data class Action(
        val action: Type,
        val winnerEntityId: String? = null,
        val loserEntityIds: List<String> = emptyList(),
        val targetEntityIds: List<String> = emptyList(),
        val aliasTexts: List<String> = emptyList(),
        val summaryText: String? = null,
        val reason: String = "",
    ) {
        @Serializable
        enum class Type {
            MERGE,
            ADD_ALIAS,
            UPDATE_SUMMARY,
            KEEP_SEPARATE,
            NOOP,
        }
    }
}

/**
 * Conservative retention plan for bounded active memory.
 *
 * Retention must not change truth status. It may keep items active, archive
 * inactive/resolved items, or no-op when policy does not allow a safe change.
 */
@Serializable
data class MemoryRetentionPlan(
    val retentionActions: List<Action> = emptyList(),
    val summary: String = "",
) {
    @Serializable
    data class Action(
        val action: Type,
        val targetType: MemoryItemRef.Type,
        val targetIds: List<String> = emptyList(),
        val reason: String = "",
    ) {
        @Serializable
        enum class Type {
            KEEP,
            ARCHIVE_ITEM,
            NOOP,
        }
    }
}

/**
 * Plan for an explicit user request to forget remembered information.
 *
 * Forgetting is not truth repair: it removes memory from normal recall by
 * archiving interpreted objects and soft-deleting source evidence where safe.
 */
@Serializable
data class MemoryForgetPlan(
    val forgetActions: List<Action> = emptyList(),
    val summary: String = "",
) {
    @Serializable
    data class Action(
        val action: Type,
        val targetType: MemoryItemRef.Type,
        val targetIds: List<String> = emptyList(),
        val reason: String = "",
    ) {
        @Serializable
        enum class Type {
            ARCHIVE_ITEM,
            SOFT_DELETE_SOURCE,
            NOOP,
        }
    }
}

/**
 * Runtime recall plan.
 *
 * This is not the final answer. It describes which memory blocks and retrieval
 * requests should be materialized into the model context before answering.
 */
@Serializable
data class MemoryReadPlan(
    val needMemory: Boolean = true,
    val answerMode: AnswerMode = AnswerMode.MIXED,
    val coreBlocks: Set<CoreBlock> = emptySet(),
    val retrievalBudget: MemoryRetrievalBudget = MemoryRetrievalBudget(),
    val retrievalRequests: List<RetrievalRequest> = emptyList(),
    val requireEvidenceFallback: Boolean = false,
) {
    @Serializable
    enum class AnswerMode {
        FACTUAL,
        RATIONALE,
        ACTION_ITEM,
        MIXED,
    }

    @Serializable
    enum class CoreBlock {
        PROFILE,
        ACTION_ITEMS,
        SESSION_SUMMARY,
    }

    @Serializable
    data class RetrievalRequest(
        val memoryType: MemorySemanticType,
        val why: String = "",
        val query: String,
        val topK: Int,
        val filters: JsonObject = JsonObject(emptyMap()),
        val preferredClaimPredicates: List<String> = emptyList(),
        val deprioritizedClaimPredicates: List<String> = emptyList(),
    )
}
