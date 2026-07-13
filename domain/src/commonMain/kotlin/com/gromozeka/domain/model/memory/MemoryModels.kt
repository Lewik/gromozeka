package com.gromozeka.domain.model.memory

import com.gromozeka.domain.model.Conversation as DomainConversation
import com.gromozeka.domain.model.Project as DomainProject
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

/*
 * MemoryModels is the domain vocabulary for Gromozeka memory.
 *
 * This file is not a database schema and not an implementation plan. It defines
 * the durable shapes the system is allowed to remember and pass between memory
 * services.
 *
 * Read it as a layered model:
 * 1. MemorySource keeps raw evidence: chat turns, tool outputs, documents, imports.
 * 2. MemoryEntity names stable things memory can talk about.
 * 3. MemoryClaim stores atomic structured facts about entities.
 * 4. MemoryNote stores richer semantic context: decisions, rationale, plans,
 *    hypotheses, lessons, and document-heavy summaries.
 * 5. MemoryActionItem, MemoryProfile, and MemoryEpisode are specialized projections:
 *    future action, compact always-useful context, and reusable experience.
 * 6. MemoryRun, MemoryItemRef, MemoryUpdateBatch, and MemoryNamespaceSnapshot
 *    are service/audit objects used to apply, inspect, and debug memory changes.
 *
 * The central split is deliberate: Source is evidence, Claim is precise truth,
 * Note is contextual meaning, Profile is a rebuildable projection, and Run is
 * the audit trail. Provenance connects interpreted memory back to sources through
 * MemoryEvidenceRef and MemoryRun lineage.
 */

/**
 * Boundary for one independent memory space.
 *
 * A namespace is the first guardrail against accidental memory mixing between
 * projects, users, experiments, or future tenants. Production currently uses
 * only [Global]; explicit values remain available for isolated tests.
 */
@Serializable
@JvmInline
value class MemoryNamespace(val value: String) {
    companion object {
        val Global = MemoryNamespace("global")
    }
}

/**
 * Human-readable and machine-readable boundary of a memory item.
 *
 * `text` keeps the scope explainable to a model or debugger. The sealed subtype
 * keeps the same boundary machine-filterable without nullable descriptor fields.
 */
@Serializable
sealed interface MemoryScope {
    /**
     * Human-readable label that explains where this memory applies.
     *
     * This is for prompts, logs, and debugging. It must not contain the remembered
     * fact itself; subtype fields define the actual boundary.
     */
    val text: String

    /**
     * Why this boundary is trusted: explicit source text, inference, summary, or import.
     */
    val basis: Basis

    /** How this scope boundary was derived. */
    @Serializable
    enum class Basis {
        EXPLICIT,
        INFERRED,
        SUMMARIZED,
        IMPORTED,
    }

    /** Memory that is valid across the whole namespace. */
    @Serializable
    data class Global(
        override val text: String,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope

    /** Memory that is valid for one project. */
    @Serializable
    data class Project(
        override val text: String,
        val projectId: DomainProject.Id,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope

    /** Memory that is valid for one conversation, optionally within a project. */
    @Serializable
    data class Conversation(
        override val text: String,
        val conversationId: DomainConversation.Id,
        val projectId: DomainProject.Id? = null,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope

    /** Memory that is valid for one canonical entity. */
    @Serializable
    data class Entity(
        override val text: String,
        val subjectEntityId: MemoryEntity.Id,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope

    /** Memory that is valid for one named environment. */
    @Serializable
    data class Environment(
        override val text: String,
        val environment: String,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope

    /** Memory that is valid for one document or document family. */
    @Serializable
    data class Document(
        override val text: String,
        val documentRef: String,
        override val basis: Basis = Basis.EXPLICIT,
    ) : MemoryScope
}

/**
 * Reusable temporal window for search, validity, and write-time reconciliation.
 */
@Serializable
data class MemoryTimeWindow(
    val from: Instant? = null,
    val to: Instant? = null,
)

/**
 * Per-run upper bounds used to keep recall and write-time grounding bounded.
 *
 * These numbers are caps, not target counts. Retrieval policy may return fewer
 * items when fewer relevant memories were found.
 */
@Serializable
data class MemoryRetrievalBudget(
    val claims: Int = 0,
    val notes: Int = 0,
    val actionItems: Int = 0,
    val sources: Int = 0,
    val episodes: Int = 0,
)

/**
 * Reference from interpreted memory back to raw source evidence.
 *
 * Claims, notes, action items, and episodes should not become floating assertions:
 * this object points to the source and optional exact evidence location.
 */
@Serializable
data class MemoryEvidenceRef(
    val sourceId: MemorySource.Id,
    val kind: Kind,
    val span: Span? = null,

    /**
     * Cached evidence excerpt for prompts and debugging.
     *
     * The source remains the authority; this quote is only a stable preview.
     */
    val cachedQuote: String? = null,
) {
    @Serializable
    enum class Kind {
        DIRECT,
        SUMMARIZED,
        IMPORTED,
        INFERRED,
        DERIVED_FROM_NOTE,
    }

    @Serializable
    data class Span(
        val startOffset: Int? = null,
        val endOffset: Int? = null,
        val startLine: Int? = null,
        val endLine: Int? = null,
        val label: String? = null,
    )
}

/**
 * Domain rule for one structured claim predicate.
 *
 * The catalog tells reconciliation how to treat cardinality, conflicts,
 * temporal validity, and optional synchronization into profile/action item views.
 */
@Serializable
data class MemoryPredicateDefinition(
    val predicate: String,
    val namespace: MemoryNamespace? = null,
    val id: Id = Id.forPredicate(namespace, predicate),
    val description: String = "",
    val subjectType: MemoryEntity.Type? = null,
    val objectKind: ObjectValueKind = ObjectValueKind.JSON,
    val cardinality: Cardinality = Cardinality.MULTI,
    val temporalPolicy: TemporalPolicy = TemporalPolicy.ATEMPORAL,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.COEXIST,
    val semanticKinds: Set<SemanticKind>,
    val aggregateEffect: AggregateEffect = AggregateEffect.NONE,
    val profileSync: Boolean = false,
    val actionItemSync: Boolean = false,
    val defaultImportance: Int = 5,
    val active: Boolean = true,
) {
    init {
        require(predicate.isNotBlank()) { "Memory predicate must not be blank" }
        require(semanticKinds.isNotEmpty()) { "Memory predicate '$predicate' must declare at least one semantic kind" }
    }

    fun scopedTo(namespace: MemoryNamespace): MemoryPredicateDefinition =
        copy(
            namespace = namespace,
            id = Id.forPredicate(namespace, predicate),
        )

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        companion object {
            fun forPredicate(
                namespace: MemoryNamespace?,
                predicate: String,
            ): Id = Id("${namespace?.value ?: "global"}:$predicate")
        }
    }

    @Serializable
    enum class ObjectValueKind {
        ENTITY,
        STRING,
        NUMBER,
        BOOLEAN,
        JSON,
    }

    @Serializable
    enum class Cardinality {
        SINGLE,
        MULTI,
    }

    @Serializable
    enum class TemporalPolicy {
        ATEMPORAL,
        TIME_SCOPED,
        STATUS_LIKE,
    }

    @Serializable
    enum class ConflictPolicy {
        REPLACE,
        COEXIST,
        RANGE_SPLIT,
    }

    @Serializable
    enum class SemanticKind {
        IDENTITY,
        LANGUAGE,
        TIMEZONE,
        PREFERENCE,
        AVOIDANCE,
        BELIEF,
        CAPABILITY,
        EVENT_PARTICIPATION,
        GOAL,
        AGGREGATE_VALUE,
        AGGREGATE_DELTA,
        LOCATION,
        ARTIFACT_DETAIL,
        OBLIGATION,
        CONSTRAINT,
        RESPONSIBILITY,
        ASSIGNMENT,
        PROJECT_ASSOCIATION,
        TECHNICAL_CONFIGURATION,
        POSSESSION,
        USAGE,
        COUNTABLE_ITEM,
        LIFECYCLE_EVENT,
        FUNCTIONAL_ROLE,
        OTHER,
    }

    @Serializable
    enum class AggregateEffect {
        NONE,
        SET_CURRENT_VALUE,
        INCREASE,
        DECREASE,
    }
}

/**
 * Versionable set of structured claim rules for a namespace or product build.
 */
typealias MemoryPredicateCatalog = List<MemoryPredicateDefinition>

/**
 * Source-level usage gates decided at ingestion time.
 *
 * This lets the system keep raw audit evidence without treating every source as
 * eligible for structured extraction or runtime recall.
 */
@Serializable
data class MemorySourceUsagePolicy(
    val allowStructuredExtraction: Boolean = true,
    val allowRecall: Boolean = true,
    val allowEvidenceHydration: Boolean = true,
    val reason: String = "standard",
) {
    companion object {
        val STANDARD = MemorySourceUsagePolicy()
        val AUDIT_ONLY = MemorySourceUsagePolicy(
            allowStructuredExtraction = false,
            allowRecall = false,
            allowEvidenceHydration = false,
            reason = "audit only",
        )
    }
}

/**
 * Raw evidence layer.
 *
 * Sources are append-mostly memory inputs: chat turns, tool outputs, imported
 * documents, or external records. Higher-level memory may summarize or interpret
 * them, but sources preserve the original material needed for audit and repair.
 */
@Serializable
sealed interface MemorySource {
    val id: Id
    val namespace: MemoryNamespace

    /**
     * Canonical text form used for LLM prompts, search, evidence quotes, and logs.
     *
     * Every source must have a readable text representation even when the original
     * input was structured data.
     */
    val contentText: String

    /**
     * Derived search-only bridge text, usually normalized to English.
     *
     * This is not evidence and must not be quoted as user/source wording. It exists
     * so recall can find original-language sources using language-neutral queries.
     */
    val searchText: String?

    /**
     * Optional structured/raw payload preserved for audit and future processors.
     *
     * Examples: tool result JSON, structured message parts, imported document
     * structure, or an external record body. It should not be required for normal
     * recall.
     */
    val contentPayload: JsonElement?

    val contentHash: String

    /**
     * When the original source event happened or was observed outside memory.
     *
     * For a chat turn this is message time; for an import it is the source/import
     * observation time. This is the temporal meaning of the evidence.
     */
    val observedAt: Instant

    /**
     * When this source record was created inside the memory store.
     *
     * This is ingestion/audit time and may differ from observedAt for imports,
     * backfills, retries, and delayed processing.
     */
    val createdAt: Instant

    val retentionClass: RetentionClass

    /**
     * How this source may be used after capture.
     *
     * A source can be retained for audit while being excluded from extraction,
     * recall, or evidence hydration.
     */
    val usagePolicy: MemorySourceUsagePolicy

    /**
     * Optional retention deadline after which this source should no longer be used
     * for normal recall.
     */
    val expiresAt: Instant?

    /**
     * Logical deletion time.
     *
     * Deleted sources may remain physically available for audit or repair, but
     * should be excluded from normal memory construction and recall.
     */
    val deletedAt: Instant?

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class ActorRole {
        USER,
        ASSISTANT,
        SYSTEM,
        EXTERNAL,
    }

    @Serializable
    enum class RetentionClass {
        STANDARD,
        SHORT_LIVED,
        SENSITIVE,
        IMPORTED,
    }

    /**
     * Raw chat message from a conversation.
     */
    @Serializable
    data class ChatTurn(
        override val id: Id,
        override val namespace: MemoryNamespace,
        val conversationId: DomainConversation.Id,
        val threadId: DomainConversation.Thread.Id? = null,
        val sourceMessageId: DomainConversation.Message.Id? = null,
        val speakerRole: ActorRole,
        val authorLabel: String? = null,
        override val contentText: String,
        override val searchText: String? = null,
        override val contentPayload: JsonElement? = null,
        override val contentHash: String,
        override val observedAt: Instant,
        override val createdAt: Instant,
        override val retentionClass: RetentionClass = RetentionClass.STANDARD,
        override val usagePolicy: MemorySourceUsagePolicy = MemorySourceUsagePolicy.STANDARD,
        override val expiresAt: Instant? = null,
        override val deletedAt: Instant? = null,
    ) : MemorySource

    /**
     * Raw result produced by a tool or integration.
     */
    @Serializable
    data class ToolOutput(
        override val id: Id,
        override val namespace: MemoryNamespace,
        val conversationId: DomainConversation.Id? = null,
        val threadId: DomainConversation.Thread.Id? = null,
        val sourceMessageId: DomainConversation.Message.Id? = null,
        val toolName: String? = null,
        override val contentText: String,
        override val searchText: String? = null,
        override val contentPayload: JsonElement? = null,
        override val contentHash: String,
        override val observedAt: Instant,
        override val createdAt: Instant,
        override val retentionClass: RetentionClass = RetentionClass.STANDARD,
        override val usagePolicy: MemorySourceUsagePolicy = MemorySourceUsagePolicy.STANDARD,
        override val expiresAt: Instant? = null,
        override val deletedAt: Instant? = null,
    ) : MemorySource

    /**
     * Manually imported or migrated note kept as source evidence.
     */
    @Serializable
    data class ImportedNote(
        override val id: Id,
        override val namespace: MemoryNamespace,
        val importRef: String? = null,
        val authorLabel: String? = null,
        override val contentText: String,
        override val searchText: String? = null,
        override val contentPayload: JsonElement? = null,
        override val contentHash: String,
        override val observedAt: Instant,
        override val createdAt: Instant,
        override val retentionClass: RetentionClass = RetentionClass.IMPORTED,
        override val usagePolicy: MemorySourceUsagePolicy = MemorySourceUsagePolicy.STANDARD,
        override val expiresAt: Instant? = null,
        override val deletedAt: Instant? = null,
    ) : MemorySource

    /**
     * Durable record from an external system that is not a chat, tool, or document.
     */
    @Serializable
    data class ExternalRecord(
        override val id: Id,
        override val namespace: MemoryNamespace,
        val recordRef: String,
        val authorLabel: String? = null,
        override val contentText: String,
        override val searchText: String? = null,
        override val contentPayload: JsonElement? = null,
        override val contentHash: String,
        override val observedAt: Instant,
        override val createdAt: Instant,
        override val retentionClass: RetentionClass = RetentionClass.STANDARD,
        override val usagePolicy: MemorySourceUsagePolicy = MemorySourceUsagePolicy.STANDARD,
        override val expiresAt: Instant? = null,
        override val deletedAt: Instant? = null,
    ) : MemorySource
}

/**
 * Canonical thing the memory graph can talk about.
 *
 * Entities are anchors for claims, notes, action items, and profiles. They absorb aliases
 * and merges so later recall can connect "Gromozeka", "the project", and similar
 * mentions to the same object.
 */
@Serializable
data class MemoryEntity(
    val id: Id,
    val namespace: MemoryNamespace,
    val entityType: Type,
    val observedTypes: Set<Type> = setOf(entityType),
    val canonicalName: String,
    val normalizedName: String,
    val summary: String? = null,
    val status: Status = Status.ACTIVE,
    val mergedIntoEntityId: Id? = null,
    val aliases: List<Alias> = emptyList(),
    val attributes: JsonObject = JsonObject(emptyMap()),
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(observedTypes.isNotEmpty()) { "Memory entity observed types must not be empty" }
        require(entityType in observedTypes) { "Memory entity primary type must be included in observed types" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Type {
        USER,
        PERSON,
        AGENT,
        ORGANIZATION,
        PROJECT,
        REPO,
        FILE,
        TECHNOLOGY,
        PRODUCT,
        LOCATION,
        CONCEPT,
        DOCUMENT,
        CONVERSATION,
        SERVICE,
        ENVIRONMENT,
        OTHER,
    }

    @Serializable
    enum class Status {
        ACTIVE,
        MERGED,
        DELETED,
    }

    @Serializable
    data class Alias(
        val text: String,
        val normalizedText: String,
        val sourceId: MemorySource.Id? = null,
        val confidence: Double = 0.0,
        val createdAt: Instant,
    )
}

/**
 * Atomic structured assertion about an entity.
 *
 * A claim is the smallest truth-bearing memory object: subject, predicate,
 * object/value, scope, validity, status, and evidence.
 */
@Serializable
data class MemoryClaim(
    val id: Id,
    val namespace: MemoryNamespace,
    val subjectEntityId: MemoryEntity.Id,
    val predicate: String,
    val predicateFamily: String? = null,
    val predicatePolicy: MemoryPredicateDefinition? = null,
    val objectEntityId: MemoryEntity.Id? = null,
    val objectValue: JsonElement? = null,
    val normalizedText: String,
    val contextText: String? = null,
    val scope: MemoryScope,
    val qualifiers: JsonObject = JsonObject(emptyMap()),
    val confidence: Double = 0.0,
    val importance: Int = 5,
    val status: Status = Status.ACTIVE,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val originNoteId: MemoryNote.Id? = null,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val supersedesClaimId: Id? = null,
    val retractedByClaimId: Id? = null,
    val createdFromRunId: MemoryRun.Id? = null,
    val evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
    val useCount: Int = 0,
    val lastUsedAt: Instant? = null,
    val lastValidatedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Status {
        ACTIVE,
        SUPERSEDED,
        RETRACTED,
        EXPIRED,
        CANDIDATE,
        RESOLVED,
        STALE,
    }
}

/**
 * Reusable semantic memory fragment.
 *
 * Notes carry decisions, rationale, plans, hypotheses, lessons, and context that
 * are important to recall but are not necessarily clean atomic facts.
 */
@Serializable
data class MemoryNote(
    val id: Id,
    val namespace: MemoryNamespace,
    val noteType: Type,
    val title: String,
    val summary: String,
    val scope: MemoryScope,
    val status: Status = Status.ACTIVE,
    val maturity: Maturity = Maturity.FRESH,
    val maturityScore: Double = 0.0,
    val anchorEntityId: MemoryEntity.Id? = null,
    val entityRefs: List<EntityRef> = emptyList(),
    val keywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val candidateClaimHints: JsonArray = JsonArray(emptyList()),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val confidence: Double = 0.0,
    val importance: Int = 5,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val supersedesNoteId: Id? = null,
    val createdFromRunId: MemoryRun.Id? = null,
    val evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
    val linkedNotes: List<Link> = emptyList(),
    val useCount: Int = 0,
    val lastUsedAt: Instant? = null,
    val lastValidatedAt: Instant? = null,
    val evidenceCount: Int = evidenceRefs.size,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Type {
        DECISION,
        DIRECTION,
        HYPOTHESIS,
        PLAN,
        LESSON,
        DOC_DIGEST,
        CONTEXT,
    }

    @Serializable
    enum class Status {
        ACTIVE,
        SUPERSEDED,
        RETRACTED,
        RESOLVED,
        STALE,
        CANDIDATE,
    }

    @Serializable
    enum class Maturity {
        FRESH,
        STABILIZING,
        MATURE,
        CONSOLIDATED,
    }

    @Serializable
    data class EntityRef(
        val entityId: MemoryEntity.Id,
        val role: Role,
    ) {
        @Serializable
        enum class Role {
            PRIMARY,
            SECONDARY,
            MENTIONED,
            OWNER,
            SUBJECT,
        }
    }

    @Serializable
    data class Link(
        val targetNoteId: Id,
        val linkType: Type,
        val linkWeight: Double = 1.0,
    ) {
        @Serializable
        enum class Type {
            SUPPORTS,
            CONTRADICTS,
            REFINES,
            RELATED,
            SUPERSEDES,
            DERIVED_FROM,
        }
    }
}

/**
 * Operational commitment or follow-up extracted from memory.
 *
 * Action items are separate from notes and claims because they have lifecycle, priority,
 * blockers, acceptance criteria, and closure semantics.
 */
@Serializable
data class MemoryActionItem(
    val id: Id,
    val namespace: MemoryNamespace,
    val ownerEntityId: MemoryEntity.Id? = null,
    val assigneeEntityId: MemoryEntity.Id? = null,
    val title: String,
    val description: String? = null,
    val status: Status = Status.OPEN,
    val priority: Priority = Priority.NORMAL,
    val dueAt: Instant? = null,
    val scope: MemoryScope,
    val acceptanceCriteria: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val relatedEntityIds: List<MemoryEntity.Id> = emptyList(),
    val originNoteId: MemoryNote.Id? = null,
    val createdFromRunId: MemoryRun.Id? = null,
    val confidence: Double = 0.0,
    val evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant? = null,
    val useCount: Int = 0,
    val lastUsedAt: Instant? = null,
    val lastValidatedAt: Instant? = null,
    val archivedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Status {
        OPEN,
        IN_PROGRESS,
        BLOCKED,
        DONE,
        CANCELLED,
    }

    @Serializable
    enum class Priority {
        LOW,
        NORMAL,
        HIGH,
    }
}

/**
 * Compact projection of stable knowledge about one entity.
 *
 * A profile is a cache/read model, not the source of truth. It should be
 * refreshable from claims, notes, episodes, action items, and evidence refs.
 */
@Serializable
data class MemoryProfile(
    val id: Id,
    val namespace: MemoryNamespace,
    val ownerEntityId: MemoryEntity.Id,
    val profileJson: JsonObject = JsonObject(emptyMap()),
    val profileText: String,
    val version: Long = 1,
    val updatedFromRunId: MemoryRun.Id? = null,
    val lastCompactedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}

/**
 * Experience pattern learned from prior work.
 *
 * Episodes preserve situation-action-result-lesson records so recall can reuse
 * practical experience without treating it as a universal fact.
 */
@Serializable
data class MemoryEpisode(
    val id: Id,
    val namespace: MemoryNamespace,
    val ownerEntityId: MemoryEntity.Id? = null,
    val situation: String,
    val action: String,
    val result: String,
    val lesson: String,
    val tags: List<String> = emptyList(),
    val successScore: Double? = null,
    val originNoteId: MemoryNote.Id? = null,
    val createdFromRunId: MemoryRun.Id? = null,
    val evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
    val useCount: Int = 0,
    val lastUsedAt: Instant? = null,
    val lastValidatedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}

/**
 * Audit trail for one memory pipeline execution.
 *
 * Runs make LLM calls, retrieval, applied operations, failures, maintenance
 * actions, and parent-child pipeline progress inspectable after the fact.
 */
@Serializable
data class MemoryRun(
    val id: Id,
    val namespace: MemoryNamespace,
    val runType: Type,
    val triggerMode: TriggerMode = TriggerMode.HOT_PATH,
    val parentRunId: Id? = null,
    val childRunIds: List<Id> = emptyList(),
    val summary: String,
    val sourceIds: List<MemorySource.Id> = emptyList(),
    val retrievedItemRefs: List<MemoryItemRef> = emptyList(),
    val retrievalBudget: MemoryRetrievalBudget? = null,
    val progress: Progress? = null,
    val promptName: String? = null,
    val promptVersion: String? = null,
    val modelName: String? = null,
    val inputHash: String? = null,
    val output: JsonElement? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val appliedOps: JsonArray = JsonArray(emptyList()),
    val repairActions: JsonArray = JsonArray(emptyList()),
    val llmCalls: List<LlmCallTiming> = emptyList(),
    val latencyMs: Long? = null,
    val tokenInput: Int? = null,
    val tokenOutput: Int? = null,
    val status: Status = Status.SUCCESS,
    val errorText: String? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    data class Progress(
        val totalUnits: Int = 0,
        val completedUnits: Int = 0,
        val failedUnits: Int = 0,
        val currentUnitLabel: String? = null,
        val currentSourceId: MemorySource.Id? = null,
    )

    @Serializable
    data class LlmCallTiming(
        val stageName: String,
        val attempt: Int,
        val status: LlmCallStatus,
        val startedAt: Instant,
        val completedAt: Instant,
        val latencyMs: Long,
        val timeoutMs: Long? = null,
        val finishReason: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val thinkingTokens: Int? = null,
        val cacheCreationTokens: Int? = null,
        val cacheReadTokens: Int? = null,
        val totalInputTokens: Int? = null,
        val totalOutputTokens: Int? = null,
        val totalTokens: Int? = null,
        val logContext: String = "",
        val errorText: String? = null,
    )

    @Serializable
    enum class LlmCallStatus {
        SUCCESS,
        RETRYING,
        FAILED,
        CANCELLED,
    }

    @Serializable
    enum class Type {
        REMEMBER,
        ENRICH_CONTEXT,
        ANSWER_QUESTION,
        ROUTE,
        DOCUMENT_INGEST,
        RETRIEVE_UPDATE,
        CANONICALIZE,
        CONSTRUCT_NOTES,
        RECONCILE_NOTES,
        EXTRACT_CLAIMS,
        RECONCILE_CLAIMS,
        UPDATE_PROFILE,
        UPDATE_ACTION_ITEMS,
        CONSOLIDATE_NOTES,
        MAINTAIN_ENTITIES,
        REPAIR_MEMORY,
        APPLY_RETENTION,
        REBUILD_EMBEDDINGS,
        FORGET_MEMORY,
        READ_PLAN,
        COMPOSE_ANSWER,
        COMPACT,
    }

    @Serializable
    enum class TriggerMode {
        HOT_PATH,
        BACKGROUND,
        MANUAL,
        SLOW_PATH,
    }

    @Serializable
    enum class Status {
        QUEUED,
        RUNNING,
        NEEDS_INPUT,
        SUCCESS,
        FAILED,
        PARTIAL,
        CANCELLED,
    }
}

/**
 * Typed pointer to any persisted memory item.
 *
 * Used when plans, runs, and search results need to reference mixed memory types
 * without embedding the whole object.
 */
@Serializable
data class MemoryItemRef(
    val type: Type,
    val id: String,
) {
    @Serializable
    enum class Type {
        SOURCE,
        ENTITY,
        CLAIM,
        NOTE,
        ACTION_ITEM,
        PROFILE,
        EPISODE,
        RUN,
    }
}

/**
 * Vector index row for one memory item under one embedding model configuration.
 */
data class MemoryEmbeddingRecord(
    val id: Id,
    val namespace: MemoryNamespace,
    val itemRef: MemoryItemRef,
    val kind: Kind = Kind.PRIMARY,
    val modelConfigurationId: String,
    val providerModelId: String,
    val dimensions: Int,
    val contentHash: String,
    val vector: List<Float>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(modelConfigurationId.isNotBlank()) { "Memory embedding model configuration id must not be blank" }
        require(providerModelId.isNotBlank()) { "Memory embedding provider model id must not be blank" }
        require(dimensions > 0) { "Memory embedding dimensions must be positive" }
        require(vector.size == dimensions) { "Memory embedding vector size must match dimensions" }
        require(contentHash.isNotBlank()) { "Memory embedding content hash must not be blank" }
    }

    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Memory embedding id must not be blank" }
        }
    }

    enum class Kind {
        PRIMARY,
    }
}

/**
 * Transactional set of memory changes produced by a pipeline step.
 */
data class MemoryUpdateBatch(
    val predicateDefinitions: List<MemoryPredicateDefinition> = emptyList(),
    val sources: List<MemorySource> = emptyList(),
    val runs: List<MemoryRun> = emptyList(),
    val entities: List<MemoryEntity> = emptyList(),
    val claims: List<MemoryClaim> = emptyList(),
    val notes: List<MemoryNote> = emptyList(),
    val actionItems: List<MemoryActionItem> = emptyList(),
    val profiles: List<MemoryProfile> = emptyList(),
    val episodes: List<MemoryEpisode> = emptyList(),
    val embeddings: List<MemoryEmbeddingRecord> = emptyList(),
)

/**
 * Read model used by planners when they need the current namespace state.
 */
data class MemoryNamespaceSnapshot(
    val predicateDefinitions: List<MemoryPredicateDefinition> = emptyList(),
    val sources: List<MemorySource> = emptyList(),
    val runs: List<MemoryRun> = emptyList(),
    val entities: List<MemoryEntity> = emptyList(),
    val claims: List<MemoryClaim> = emptyList(),
    val notes: List<MemoryNote> = emptyList(),
    val actionItems: List<MemoryActionItem> = emptyList(),
    val profiles: List<MemoryProfile> = emptyList(),
    val episodes: List<MemoryEpisode> = emptyList(),
)

@Serializable
data class MemoryNamespaceSummary(
    val namespace: MemoryNamespace,
    val displayName: String = namespace.value,
    val kind: Kind = Kind.from(namespace),
    val counts: Counts = Counts(),
    val lastUpdatedAt: Instant? = null,
) {
    @Serializable
    enum class Kind {
        PROJECT,
        USER,
        GLOBAL,
        CUSTOM;

        companion object {
            fun from(namespace: MemoryNamespace): Kind =
                when {
                    namespace.value.startsWith("project:") -> PROJECT
                    namespace.value.startsWith("user:") -> USER
                    namespace.value == "global" || namespace.value.startsWith("global:") -> GLOBAL
                    else -> CUSTOM
                }
        }
    }

    @Serializable
    data class Counts(
        val predicateDefinitions: Int = 0,
        val sources: Int = 0,
        val runs: Int = 0,
        val entities: Int = 0,
        val claims: Int = 0,
        val notes: Int = 0,
        val actionItems: Int = 0,
        val profiles: Int = 0,
        val episodes: Int = 0,
    ) {
        val totalItems: Int
            get() = predicateDefinitions + sources + runs + entities + claims + notes + actionItems + profiles + episodes
    }

    companion object {
        fun fromSnapshot(
            namespace: MemoryNamespace,
            snapshot: MemoryNamespaceSnapshot,
            displayName: String = namespace.value,
        ): MemoryNamespaceSummary =
            MemoryNamespaceSummary(
                namespace = namespace,
                displayName = displayName,
                counts = Counts(
                    predicateDefinitions = snapshot.predicateDefinitions.size,
                    sources = snapshot.sources.size,
                    runs = snapshot.runs.size,
                    entities = snapshot.entities.size,
                    claims = snapshot.claims.size,
                    notes = snapshot.notes.size,
                    actionItems = snapshot.actionItems.size,
                    profiles = snapshot.profiles.size,
                    episodes = snapshot.episodes.size,
                ),
                lastUpdatedAt = snapshot.lastUpdatedAt(),
            )
    }
}

private fun MemoryNamespaceSnapshot.lastUpdatedAt(): Instant? =
    buildList {
        sources.mapTo(this) { it.createdAt }
        runs.mapTo(this) { it.completedAt ?: it.startedAt ?: it.createdAt }
        entities.mapTo(this) { it.updatedAt }
        claims.mapTo(this) { it.updatedAt }
        notes.mapTo(this) { it.updatedAt }
        actionItems.mapTo(this) { it.updatedAt }
        profiles.mapTo(this) { it.updatedAt }
        episodes.mapTo(this) { it.updatedAt }
    }.maxOrNull()
