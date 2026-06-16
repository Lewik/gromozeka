package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object MemoryAggregateMetricDeriver {
    fun derive(
        claimCandidates: List<MemoryClaimCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate> {
        val metricPolicy = predicateCatalog.resolvePredicateDefinition("current_metric_value")
            ?: return emptyList()
        val baselines = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .filter { it.status == MemoryClaim.Status.ACTIVE && it.archivedAt == null }
            .filter { it.predicate == metricPolicy.predicate || it.predicateFamily == metricPolicy.predicate }
            .mapNotNull { it.toAggregateBaseline() }

        if (baselines.isEmpty()) return emptyList()

        return claimCandidates.mapNotNull { candidate ->
            val delta = candidate.toAggregateDelta() ?: return@mapNotNull null
            val baseline = baselines
                .filter { it.matchesAggregateScope(candidate) }
                .filter { it.isOlderThan(candidate.validFrom) }
                .maxByOrNull { it.claim.validFrom ?: it.claim.createdAt }
                ?: return@mapNotNull null
            candidate.toDerivedMetricCandidate(
                metricPolicy = metricPolicy,
                baseline = baseline,
                value = baseline.value + delta,
                delta = delta,
            )
        }
    }

    private fun MemoryClaim.toAggregateBaseline(): AggregateBaseline? {
        val value = objectValue?.jsonPrimitive?.contentOrNull?.extractFirstMetricNumber()
            ?: normalizedText.extractMetricNumber()
            ?: contextText?.extractMetricNumber()
            ?: return null
        return AggregateBaseline(this, value)
    }

    private fun MemoryClaimCandidate.toAggregateDelta(): Int? {
        if (!isCountableCollectionMembershipCandidate()) return null
        val text = listOfNotNull(evidenceQuote, normalizedText, contextText)
            .joinToString(" ")
            .lowercase()
        val hasAddition = aggregateAdditionMarkers.any { it in text }
        val hasRemoval = aggregateRemovalMarkers.any { it in text }
        return when {
            hasAddition && !hasRemoval -> 1
            hasRemoval && !hasAddition -> -1
            else -> null
        }
    }

    private fun MemoryClaimCandidate.isCountableCollectionMembershipCandidate(): Boolean {
        val predicateName = predicate.lowercase()
        val familyName = predicateFamily?.lowercase()
        return predicateName in countableMembershipPredicates || familyName in countableMembershipPredicates
    }

    private fun MemoryClaimCandidate.toDerivedMetricCandidate(
        metricPolicy: com.gromozeka.domain.model.memory.MemoryPredicateDefinition,
        baseline: AggregateBaseline,
        value: Int,
        delta: Int,
    ): MemoryClaimCandidate =
        copy(
            subjectEntityId = baseline.claim.subjectEntityId,
            predicate = metricPolicy.predicate,
            predicateFamily = metricPolicy.predicate,
            predicatePolicy = metricPolicy,
            objectEntityId = null,
            objectValue = JsonPrimitive(value.toString()),
            normalizedText = "${baseline.claim.scope.text} has current aggregate count $value.",
            contextText = "Derived from previous aggregate count ${baseline.value} and a later explicit ${delta.deltaWord()} in the same scope.",
            scope = baseline.claim.scope,
            qualifiers = buildJsonObject {
                put("derived_from_claim_id", baseline.claim.id.value)
                put("aggregate_delta", delta)
                put("aggregate_scope", baseline.claim.scope.text)
            },
            confidence = minOf(confidence, baseline.claim.confidence).coerceAtLeast(0.7),
            importance = maxOf(importance, baseline.claim.importance),
            evidenceKind = MemoryEvidenceRef.Kind.INFERRED,
            evidenceReason = "The source gives an explicit aggregate delta in the same scope as a retrieved active aggregate metric.",
            reason = "Derived updated aggregate metric from previous current_metric_value and later explicit collection delta.",
        )

    private fun Int.deltaWord(): String =
        if (this > 0) "addition" else "removal"

    private fun AggregateBaseline.isOlderThan(candidateValidFrom: Instant?): Boolean {
        val baselineValidFrom = claim.validFrom ?: return false
        val candidateFrom = candidateValidFrom ?: return false
        return baselineValidFrom < candidateFrom
    }

    private fun AggregateBaseline.matchesAggregateScope(candidate: MemoryClaimCandidate): Boolean {
        if (claim.scope.sameAggregateScopeAs(candidate.scope)) return true
        val baselineTokens = claim.aggregateScopeTokens()
        val candidateTokens = candidate.aggregateScopeTokens()
        if (baselineTokens.isEmpty() || candidateTokens.isEmpty()) return false

        val overlap = baselineTokens.intersect(candidateTokens)
        val baselineNumericTokens = baselineTokens.filterTo(mutableSetOf()) { it.all(Char::isDigit) }
        if (baselineNumericTokens.isNotEmpty() && overlap.none { it in baselineNumericTokens }) return false

        val requiredOverlap = minOf(2, minOf(baselineTokens.size, candidateTokens.size))
        return overlap.size >= requiredOverlap
    }

    private fun MemoryScope.sameAggregateScopeAs(other: MemoryScope): Boolean =
        aggregateScopeKey() == other.aggregateScopeKey() || text.normalizedScopeText() == other.text.normalizedScopeText()

    private fun MemoryClaim.aggregateScopeTokens(): Set<String> =
        listOfNotNull(scope.text, normalizedText, contextText)
            .joinToString(" ")
            .aggregateScopeTokens()

    private fun MemoryClaimCandidate.aggregateScopeTokens(): Set<String> =
        listOfNotNull(scope.text, normalizedText, contextText, evidenceQuote)
            .joinToString(" ")
            .aggregateScopeTokens()

    private fun MemoryScope.aggregateScopeKey(): String =
        when (this) {
            is MemoryScope.Conversation -> "conversation:${conversationId.value}:${projectId?.value.orEmpty()}:${text.normalizedScopeText()}"
            is MemoryScope.Document -> "document:$documentRef:${text.normalizedScopeText()}"
            is MemoryScope.Entity -> "entity:${subjectEntityId.value}:${text.normalizedScopeText()}"
            is MemoryScope.Environment -> "environment:$environment:${text.normalizedScopeText()}"
            is MemoryScope.Global -> "global:${text.normalizedScopeText()}"
            is MemoryScope.Project -> "project:${projectId.value}:${text.normalizedScopeText()}"
        }

    private fun String.normalizedScopeText(): String =
        lowercase()
            .replace(nonAlphanumericRegex, " ")
            .trim()
            .replace(spaceRegex, " ")

    private fun String.aggregateScopeTokens(): Set<String> =
        lowercase()
            .replace(nonAlphanumericRegex, " ")
            .split(spaceRegex)
            .mapNotNull { it.toAggregateScopeToken() }
            .filterNot { it in aggregateScopeStopWords }
            .toSet()

    private fun String.toAggregateScopeToken(): String? {
        val token = trim()
        if (token.isBlank()) return null
        if (token.all(Char::isDigit)) return token
        if (token.length < 3) return null
        return when {
            token.endsWith("ies") && token.length > 4 -> token.dropLast(3) + "y"
            token.endsWith("es") && token.length > 4 -> token.dropLast(2)
            token.endsWith("s") && token.length > 3 -> token.dropLast(1)
            else -> token
        }
    }

    private fun String.extractMetricNumber(): Int? {
        metricNumberAfterMarkerRegex.find(this)?.let { return it.groupValues[1].toIntOrNull() }
        metricNumberBeforeNounRegex.find(this)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun String.extractFirstMetricNumber(): Int? =
        plainIntegerRegex.find(this)?.value?.toIntOrNull()

    private data class AggregateBaseline(
        val claim: MemoryClaim,
        val value: Int,
    )

    private val countableMembershipPredicates = setOf(
        "owns",
        "has",
        "contains",
        "includes",
    )
    private val aggregateAdditionMarkers = listOf(
        " added ",
        " add ",
        " bought ",
        " purchased ",
        " acquired ",
        " got ",
        " received ",
        " collected ",
    )
    private val aggregateScopeStopWords = setOf(
        "user",
        "collection",
        "size",
        "count",
        "total",
        "current",
        "aggregate",
        "item",
        "entry",
        "object",
        "new",
        "same",
        "scope",
        "explicit",
        "own",
        "owns",
        "owned",
        "has",
        "have",
        "had",
        "their",
        "my",
        "the",
        "and",
        "of",
        "in",
        "to",
        "from",
        "with",
        "for",
        "that",
        "this",
        "was",
        "were",
    )
    private val aggregateRemovalMarkers = listOf(
        " removed ",
        " remove ",
        " sold ",
        " donated ",
        " gave away ",
        " got rid of ",
        " discarded ",
    )
    private val metricNumberAfterMarkerRegex =
        Regex("""(?i)\b(?:contains|contain|count(?:s)?(?:\s+is|\s+of)?|total(?:\s+of)?|has|have|had|is|are)\s+(\d{1,9})\b""")
    private val metricNumberBeforeNounRegex =
        Regex("""(?i)\b(\d{1,9})\s+(?:items?|entries|objects|coins?|books?|records?|albums?|tasks?|events?|people|members)\b""")
    private val plainIntegerRegex = Regex("""\b\d{1,9}\b""")
    private val nonAlphanumericRegex = Regex("""[^a-z0-9]+""")
    private val spaceRegex = Regex("""\s+""")
}
