package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryClaimReconciler
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition.AggregateEffect
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition.SemanticKind
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryClaimReconciler(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryClaimReconciler {
    private val log = KLoggers.logger(this)

    override suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimReconciliationOp> {
        if (claimCandidates.isEmpty()) return emptyList()

        val existingClaims = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .filter { it.status == MemoryClaim.Status.ACTIVE }

        if (existingClaims.isEmpty() && claimCandidates.all { it.hasKnownPredicatePolicy(predicateCatalog) }) {
            log.info {
                "Memory claim reconciler direct insert: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "reason=no_existing_active_claims candidates=${claimCandidates.size}"
            }
            return claimCandidates.map {
                MemoryClaimReconciliationOp(
                    action = MemoryReconciliationAction.INSERT,
                    candidate = it.withKnownPredicatePolicy(request, predicateCatalog),
                    reason = "No existing active claims were retrieved.",
                )
            }
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "claim-reconciler",
            taskPrompt = buildReconcilerPrompt(request, claimCandidates, existingClaims, predicateCatalog),
        )

        log.info {
            "Memory claim reconciler LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${claimCandidates.size} existingClaims=${existingClaims.size} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} stageMessages=${stageMessages.size}"
        }

        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.CLAIM_RECONCILER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ClaimReconciler,
                    toolContext = mapOf(
                        "memoryClaimReconciler" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "claim-reconciler",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
            parse = { jsonText ->
                json.decodeFromString<ClaimReconcilerResponse>(jsonText)
                    .operations
                    .mapNotNull { it.toOp(claimCandidates, existingClaims, request, predicateCatalog) }
            },
            validate = MemoryClaimPredicateQualityPolicy::validateReconciliationOps,
        )

        log.info {
            "Memory claim reconciler raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForReconcilerMemoryLog(4_000)}"
        }

        val ops = result.value

        log.info {
            "Memory claim reconciler mapped ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${ops.joinToString("|") { "${it.action.name}:target=${it.targetClaimId?.value ?: "null"}:candidate=${it.candidate?.predicate ?: "null"}:family=${it.candidate?.predicateFamily ?: "null"}:${it.reason.oneLineForReconcilerMemoryLog(180)}" }.ifBlank { "none" }}"
        }

        return ops.ifEmpty {
            claimCandidates.map {
                MemoryClaimReconciliationOp(
                    action = MemoryReconciliationAction.INSERT,
                    candidate = it,
                    reason = "Reconciler returned no valid operations",
                )
            }
        }
    }

    private fun buildReconcilerPrompt(
        request: DirectStructuredMemoryWriteRequest,
        claimCandidates: List<MemoryClaimCandidate>,
        existingClaims: List<MemoryClaim>,
        predicateCatalog: MemoryPredicateCatalog,
    ): String = """
        Memory stage: ClaimReconciler v1.
        Runtime processing time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Decide how candidate claims should update active memory and assign predicate policy.

        Return JSON:
        {
          "operations": [
            {
              "action": "insert | noop | supersede | retract | update",
              "candidate_index": 0,
              "target_claim_id": "existing-claim-id-or-null",
              "canonical_predicate": "stable_snake_case_predicate_or_null",
              "predicate_family": "stable_conceptual_family_or_null",
              "predicate_description": "short policy meaning or null",
              "object_kind": "entity | string | number | boolean | json | null",
              "cardinality": "single | multi | null",
              "temporal_policy": "atemporal | time_scoped | status_like | null",
              "conflict_policy": "replace | coexist | range_split | null",
              "semantic_kinds": ["preference"],
              "aggregate_effect": "none | set_current_value | increase | decrease",
              "reason": "short explanation"
            }
          ]
        }

        Actions:
        - insert: candidate is new durable information.
        - noop: candidate is duplicate, low-value, or should not become active memory.
        - supersede: candidate is the new current value and target_claim_id is the old active claim it replaces.
        - retract: target_claim_id should stop being active because the target message retracts it without a replacement candidate.
        - update: target_claim_id should be patched without adding a new candidate; use sparingly.

        Predicate policy fields:
        - canonical_predicate: normalized predicate to store for the candidate. Reuse an existing predicate only if it is the same semantic relation; otherwise choose a concise snake_case relation.
        - predicate_family: stable conceptual family used for future reconciliation.
        - cardinality: single if one subject can have only one active value in this family and scope; multi if several values can coexist.
        - temporal_policy: atemporal for timeless facts, time_scoped for facts true in explicit time windows, status_like for current states, current preferences, decisions, and other evolving values.
        - conflict_policy: replace when a new value invalidates the old active value, coexist when both can remain true, range_split when old and new values are both temporally meaningful.
        - semantic_kinds: machine-readable relation categories. Use catalog values when reusing a catalog predicate. For learned predicates, choose the smallest useful non-empty set.
        - aggregate_effect: none for ordinary predicates, set_current_value for current aggregate values, increase/decrease for explicit numeric aggregate deltas.

        Rules:
        - Use the target source and full thread context to understand corrections, replacements, pronouns, temporal meaning, and scope.
        - Do not decide by literal predicate names. Infer the semantic predicate family from the claim meaning, subject, object, qualifiers, evidence, scope, and temporal fields.
        - Use an existing predicate from Predicate catalog excerpt when it captures the same semantic relation.
        - If a catalog predicate fits, set canonical_predicate to that exact predicate and copy its cardinality, temporal_policy, conflict_policy, semantic_kinds, and aggregate_effect.
        - Invent a new canonical_predicate only when no active catalog predicate captures the relation.
        - Never canonicalize to generic description, classification, property-bag, or paraphrase predicates such as "is_described_as", "has_property", "is_a", or "defined_as". Choose a specific durable relation or return noop.
        - For ordinary positive preferences, use catalog predicate "prefers"; do not keep category-specific variants such as "prefers_car_brand" unless they represent a named current/default slot.
        - Do not map a named current/default/primary/chosen/backend slot to a generic preference, affinity, status, or usage predicate; use or create the specific slot predicate.
        - For named metric, record, score, benchmark, quota, threshold, or personal-best values, use catalog predicate "current_metric_value" when present. Preserve the metric/domain in scope or qualifiers so unrelated metrics do not replace each other.
        - For explicit countable aggregate changes, use catalog predicate "aggregate_increase" or "aggregate_decrease" when present. object_kind must be number, object_value must be the positive numeric amount, and aggregate_effect must match the catalog predicate.
        - For current place, residence, base, or relocation-result values, use catalog predicate "current_location" when present. It is a status-like single-value slot for the same subject and scope; newer explicit current-location values replace older active values.
        - For concrete details inside imported generated artifacts, use catalog predicate "generated_artifact_detail" when no more specific catalog predicate fits. These details usually coexist unless a later artifact version explicitly supersedes an earlier version.
        - If the target source updates or replaces an existing value, keep the semantic slot/family from the replaced claim when the source and context identify that slot.
        - If multiple candidates from the same target source express the same durable point at different abstraction levels, keep the most specific/actionable candidate and return noop for weaker broader candidates.
        - If one candidate captures the target source's explicit current/default/status value and another candidate is only a generic preference or affinity inferred from the same words, return noop for the generic candidate unless the target source independently asserts it.
        - Do not insert redundant broader candidates merely because they use a different predicate or family.
        - For exclusive current/default/status values in the same subject, scope, and environment, use cardinality=single and conflict_policy=replace unless simultaneous active values are explicitly allowed.
        - For dated observations or historical phase facts, use temporal_policy=time_scoped; they can coexist with later current-state claims when scopes differ by time, phase, or environment.
        - Runtime processing time is not evidence that a pending obligation has expired, completed, or become stale. For catalog pending_* claims and other independently completeable duties, insert the explicit pending fact unless the target source or existing active memory explicitly says it was completed, cancelled, returned, picked up, superseded, or otherwise no longer pending.
        - If the candidate is equivalent to an existing active claim in the same semantic family and scope, return noop.
        - If predicate policy says replace and the candidate conflicts with an older active value in the same family and scope, supersede the old claim.
        - If predicate policy says coexist, insert the candidate unless it is a duplicate.
        - If predicate policy says range_split, insert the candidate and supersede only an old current-state claim that should no longer be retrieved as current.
        - Attributed viewpoints, independent owners, independent events, and historical facts usually coexist unless the target source explicitly retracts them.
        - Use catalog predicate "believes" for third-party viewpoints such as "Alice thinks X"; do not canonicalize those claims to "prefers" or "avoids" unless the source explicitly asserts preference or avoidance.
        - Prefer insert over destructive actions when policy or evidence is unclear.
        - Return one operation for each candidate unless the right action is a target-only retract/update.
        - candidate_index is zero-based from Candidate claims below.
        - target_claim_id must be one of Existing active claims below or null.
        - Fill predicate policy fields for every operation with a candidate, including noop.
        - Return valid JSON only.

        TARGET_SOURCE:
        ${request.source.contentText.trim()}

        Candidate claims:
        ${claimCandidates.mapIndexed { index, claim -> claim.renderForReconciler(index) }.joinToString("\n")}

        Existing active claims:
        ${existingClaims.joinToString("\n") { it.renderForReconciler() }.ifBlank { "none" }}

        Predicate catalog excerpt:
        ${predicateCatalog.renderForMemoryPrompt(maxDefinitions = 120)}
    """.trimIndent()

    @Serializable
    private data class ClaimReconcilerResponse(
        val operations: List<Operation> = emptyList(),
    )

    @Serializable
    private data class Operation(
        val action: String,
        @SerialName("candidate_index")
        val candidateIndex: Int? = null,
        @SerialName("target_claim_id")
        val targetClaimId: String? = null,
        @SerialName("canonical_predicate")
        val canonicalPredicate: String? = null,
        @SerialName("predicate_family")
        val predicateFamily: String? = null,
        @SerialName("predicate_description")
        val predicateDescription: String? = null,
        @SerialName("object_kind")
        val objectKind: String? = null,
        val cardinality: String? = null,
        @SerialName("temporal_policy")
        val temporalPolicy: String? = null,
        @SerialName("conflict_policy")
        val conflictPolicy: String? = null,
        @SerialName("semantic_kinds")
        val semanticKinds: List<String>,
        @SerialName("aggregate_effect")
        val aggregateEffect: String,
        val reason: String = "",
    ) {
        fun toOp(
            candidates: List<MemoryClaimCandidate>,
            existingClaims: List<MemoryClaim>,
            request: DirectStructuredMemoryWriteRequest,
            predicateCatalog: MemoryPredicateCatalog,
        ): MemoryClaimReconciliationOp? {
            val candidate = candidateIndex?.let { candidates.getOrNull(it) }?.withPredicatePolicy(
                request = request,
                predicateCatalog = predicateCatalog,
                canonicalPredicate = canonicalPredicate,
                predicateFamily = predicateFamily,
                predicateDescription = predicateDescription,
                objectKind = objectKind,
                cardinality = cardinality,
                temporalPolicy = temporalPolicy,
                conflictPolicy = conflictPolicy,
                semanticKinds = semanticKinds,
                aggregateEffect = aggregateEffect,
            )
            val target = targetClaimId?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.let { MemoryClaim.Id(it) }
            val targetExists = target == null || existingClaims.any { it.id == target }
            if (!targetExists) return null

            return when (action.trim().lowercase()) {
                "insert" -> candidate?.let {
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.INSERT,
                        candidate = it,
                        reason = reason.trim().ifBlank { "LLM reconciler inserted candidate" },
                    )
                }

                "noop" -> MemoryClaimReconciliationOp(
                    action = MemoryReconciliationAction.NOOP,
                    targetClaimId = target,
                    candidate = candidate,
                    reason = reason.trim().ifBlank { "LLM reconciler selected noop" },
                )

                "supersede" -> {
                    if (target == null || candidate == null) return null
                    val targetClaim = existingClaims.firstOrNull { it.id == target } ?: return null
                    if (candidate.shouldCoexistWithTemporalTargetForMemoryReconciliation(targetClaim)) {
                        return MemoryClaimReconciliationOp(
                            action = MemoryReconciliationAction.INSERT,
                            candidate = candidate,
                            reason = reason.trim().ifBlank { "Temporal candidate coexists with historical target" },
                        )
                    }
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.SUPERSEDE,
                        targetClaimId = target,
                        candidate = candidate,
                        updatedClaim = MemoryClaimReconciliationOp.Patch(
                            status = MemoryClaim.Status.SUPERSEDED,
                            validTo = request.source.observedAt,
                        ),
                        reason = reason.trim().ifBlank { "LLM reconciler superseded older active claim" },
                    )
                }

                "retract" -> {
                    if (target == null) return null
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.RETRACT,
                        targetClaimId = target,
                        candidate = candidate,
                        updatedClaim = MemoryClaimReconciliationOp.Patch(
                            status = MemoryClaim.Status.RETRACTED,
                            validTo = request.source.observedAt,
                        ),
                        reason = reason.trim().ifBlank { "LLM reconciler retracted active claim" },
                    )
                }

                "update" -> {
                    if (target == null) return null
                    MemoryClaimReconciliationOp(
                        action = MemoryReconciliationAction.UPDATE,
                        targetClaimId = target,
                        candidate = candidate,
                        reason = reason.trim().ifBlank { "LLM reconciler updated active claim" },
                    )
                }

                else -> null
            }
        }

    }
}

private fun MemoryClaimCandidate.hasKnownPredicatePolicy(predicateCatalog: MemoryPredicateCatalog): Boolean =
    predicatePolicy != null || predicateCatalog.resolvePredicateDefinition(predicate, predicateFamily) != null

private fun MemoryClaimCandidate.withKnownPredicatePolicy(
    request: DirectStructuredMemoryWriteRequest,
    predicateCatalog: MemoryPredicateCatalog,
): MemoryClaimCandidate {
    val policy = predicatePolicy?.scopedTo(request.namespace)
        ?: predicateCatalog.resolvePredicateDefinition(predicate, predicateFamily)?.scopedTo(request.namespace)
        ?: throw IllegalArgumentException("Claim candidate '$predicate' has no predicate policy")
    return copy(
        predicate = policy.predicate,
        predicateFamily = policy.predicate,
        predicatePolicy = policy,
    )
}

private fun MemoryClaimCandidate.withPredicatePolicy(
    request: DirectStructuredMemoryWriteRequest,
    predicateCatalog: MemoryPredicateCatalog,
    canonicalPredicate: String? = null,
    predicateFamily: String? = null,
    predicateDescription: String? = null,
    objectKind: String? = null,
    cardinality: String? = null,
    temporalPolicy: String? = null,
    conflictPolicy: String? = null,
    semanticKinds: List<String>,
    aggregateEffect: String,
): MemoryClaimCandidate {
    val canonical = canonicalPredicate
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: predicate
    val family = predicateFamily
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: this.predicateFamily
        ?: canonical
    val catalogDefinition = predicateCatalog.resolvePredicateDefinition(canonical, family)
    val explicitObjectKind = objectKind.toEnumValueOrNull<MemoryPredicateDefinition.ObjectValueKind>()
    val explicitCardinality = cardinality.toEnumValueOrNull<MemoryPredicateDefinition.Cardinality>()
    val explicitTemporalPolicy = temporalPolicy.toEnumValueOrNull<MemoryPredicateDefinition.TemporalPolicy>()
    val explicitConflictPolicy = conflictPolicy.toEnumValueOrNull<MemoryPredicateDefinition.ConflictPolicy>()
    val explicitSemanticKinds = semanticKinds.toEnumValueSet<SemanticKind>("semantic_kinds")
    val explicitAggregateEffect = aggregateEffect.toRequiredEnumValue<AggregateEffect>("aggregate_effect")

    if (catalogDefinition != null &&
        (catalogDefinition.matchesExplicitPolicy(
            objectKind = explicitObjectKind,
            cardinality = explicitCardinality,
            temporalPolicy = explicitTemporalPolicy,
            conflictPolicy = explicitConflictPolicy,
        ) || !hasLearnedSlotSignal(canonical, family, predicateDescription, normalizedText))
    ) {
        val scopedDefinition = catalogDefinition.scopedTo(request.namespace)
        return copy(
            predicate = scopedDefinition.predicate,
            predicateFamily = scopedDefinition.predicate,
            predicatePolicy = scopedDefinition,
        )
    }

    val learnedPredicate = if (catalogDefinition == null) {
        canonical
    } else {
        learnedSlotPredicateName(
            canonical = canonical,
            family = family,
            description = predicateDescription,
            normalizedText = normalizedText,
        )
    }
    val learnedDefinition = MemoryPredicateDefinition(
        predicate = learnedPredicate,
        namespace = request.namespace,
        description = predicateDescription?.trim()?.takeIf { it.isNotBlank() }
            ?: "Learned predicate for ${normalizedText.take(160)}",
        objectKind = explicitObjectKind ?: catalogDefinition?.objectKind ?: MemoryPredicateDefinition.ObjectValueKind.JSON,
        cardinality = explicitCardinality ?: catalogDefinition?.cardinality ?: MemoryPredicateDefinition.Cardinality.MULTI,
        temporalPolicy = explicitTemporalPolicy ?: catalogDefinition?.temporalPolicy ?: MemoryPredicateDefinition.TemporalPolicy.ATEMPORAL,
        conflictPolicy = explicitConflictPolicy ?: catalogDefinition?.conflictPolicy ?: MemoryPredicateDefinition.ConflictPolicy.COEXIST,
        semanticKinds = if (catalogDefinition == null) {
            explicitSemanticKinds.ifEmpty {
                throw IllegalArgumentException("Learned predicate '$learnedPredicate' must declare at least one semantic_kind")
            }
        } else {
            catalogDefinition.semanticKinds
        },
        aggregateEffect = catalogDefinition?.aggregateEffect ?: explicitAggregateEffect,
        profileSync = catalogDefinition?.profileSync == true,
        actionItemSync = catalogDefinition?.actionItemSync == true,
        defaultImportance = catalogDefinition?.defaultImportance ?: 5,
    )

    return copy(
        predicate = learnedDefinition.predicate,
        predicateFamily = learnedDefinition.predicate,
        predicatePolicy = learnedDefinition,
    )
}

internal fun MemoryClaimCandidate.shouldCoexistWithTemporalTargetForMemoryReconciliation(target: MemoryClaim): Boolean {
    val candidatePolicy = predicatePolicy ?: return false
    val targetPolicy = target.predicatePolicy ?: return false
    val candidateFamily = predicateFamily ?: predicate
    val targetFamily = target.predicateFamily ?: target.predicate

    val sameRangeSplitFamily = candidateFamily.equals(targetFamily, ignoreCase = true) &&
        candidatePolicy.temporalPolicy == MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED &&
        candidatePolicy.conflictPolicy == MemoryPredicateDefinition.ConflictPolicy.RANGE_SPLIT &&
        targetPolicy.temporalPolicy == MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED
    if (sameRangeSplitFamily) return true

    return targetPolicy.temporalPolicy == MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED &&
        candidatePolicy.temporalPolicy != MemoryPredicateDefinition.TemporalPolicy.TIME_SCOPED
}

private fun MemoryClaimCandidate.renderForReconciler(index: Int): String =
    "- candidate[$index] subject=${subjectEntityId.value} predicate=$predicate object=${objectEntityId?.value ?: objectValue} " +
        "scope=${scope.text} validFrom=$validFrom validTo=$validTo qualifiers=$qualifiers text=${normalizedText.oneLineForReconcilerMemoryLog(240)} " +
        "evidence=${evidenceQuote?.oneLineForReconcilerMemoryLog(180) ?: "null"}"

private fun MemoryClaim.renderForReconciler(): String =
    "- id=${id.value} subject=${subjectEntityId.value} predicate=$predicate object=${objectEntityId?.value ?: objectValue} " +
        "family=${predicateFamily ?: "unknown"} policy=${predicatePolicy?.policyForReconcilerLog() ?: "unknown"} " +
        "scope=${scope.text} status=${status.name} validFrom=$validFrom validTo=$validTo qualifiers=$qualifiers text=${normalizedText.oneLineForReconcilerMemoryLog(240)}"

private inline fun <reified T : Enum<T>> String?.toEnumValue(default: T): T {
    val normalized = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return default
    return enumValues<T>().firstOrNull { it.name.equals(normalized.replace("-", "_"), ignoreCase = true) } ?: default
}

private inline fun <reified T : Enum<T>> String?.toEnumValueOrNull(): T? {
    val normalized = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(normalized.replace("-", "_"), ignoreCase = true) }
}

private inline fun <reified T : Enum<T>> String.toRequiredEnumValue(fieldName: String): T {
    val normalized = trim().takeIf { it.isNotBlank() && it != "null" }
        ?: throw IllegalArgumentException("Missing required enum field '$fieldName'")
    return enumValues<T>().firstOrNull { it.name.equals(normalized.replace("-", "_"), ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown enum value '$this' for '$fieldName'")
}

private inline fun <reified T : Enum<T>> List<String>.toEnumValueSet(fieldName: String): Set<T> =
    map { value ->
        value.toRequiredEnumValue<T>(fieldName)
    }.toSet()

private fun MemoryPredicateDefinition.matchesExplicitPolicy(
    objectKind: MemoryPredicateDefinition.ObjectValueKind?,
    cardinality: MemoryPredicateDefinition.Cardinality?,
    temporalPolicy: MemoryPredicateDefinition.TemporalPolicy?,
    conflictPolicy: MemoryPredicateDefinition.ConflictPolicy?,
): Boolean =
    (objectKind == null || this.objectKind == objectKind) &&
        (cardinality == null || this.cardinality == cardinality) &&
        (temporalPolicy == null || this.temporalPolicy == temporalPolicy) &&
        (conflictPolicy == null || this.conflictPolicy == conflictPolicy)

private fun learnedSlotPredicateName(
    canonical: String,
    family: String,
    description: String?,
    normalizedText: String,
): String {
    val source = listOf(description, family, normalizedText, canonical)
        .firstOrNull { !it.isNullOrBlank() && it.trim().length >= 8 }
        ?: canonical
    val words = Regex("[A-Za-z0-9]+")
        .findAll(source.lowercase())
        .map { it.value }
        .filterNot { it in learnedPredicateStopWords }
        .take(8)
        .toList()
    val base = words.joinToString("_").ifBlank { canonical.normalizeLearnedPredicateName() }
    val normalizedCanonical = canonical.normalizeLearnedPredicateName()
    return if (base == normalizedCanonical) "${base}_slot" else base
}

private fun hasLearnedSlotSignal(
    canonical: String,
    family: String,
    description: String?,
    normalizedText: String,
): Boolean {
    val haystack = listOf(canonical, family, description.orEmpty(), normalizedText)
        .joinToString(" ")
        .lowercase()
    return learnedSlotSignalWords.any { word ->
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(haystack)
    }
}

private fun String.normalizeLearnedPredicateName(): String =
    Regex("[A-Za-z0-9]+")
        .findAll(lowercase())
        .joinToString("_") { it.value }
        .ifBlank { "learned_predicate" }

private val learnedSlotSignalWords = setOf(
    "backend",
    "chosen",
    "default",
    "primary",
    "selected",
)

private val learnedPredicateStopWords = setOf(
    "a",
    "an",
    "and",
    "as",
    "for",
    "in",
    "is",
    "of",
    "or",
    "s",
    "the",
    "their",
    "to",
    "user",
    "users",
)

private fun MemoryPredicateDefinition.policyForReconcilerLog(): String =
    "cardinality=${cardinality.name},temporal=${temporalPolicy.name},conflict=${conflictPolicy.name}," +
        "semantics=${semanticKinds.joinToString("|") { it.name }},aggregateEffect=${aggregateEffect.name}"

private fun String.oneLineForReconcilerMemoryLog(maxChars: Int): String {
    val normalized = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars) + "...[truncated ${normalized.length - maxChars} chars]"
}
