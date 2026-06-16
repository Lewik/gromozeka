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
import kotlin.math.absoluteValue

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

        val matchedDeltas = claimCandidates.mapNotNull { candidate ->
            val delta = candidate.toAggregateDelta() ?: return@mapNotNull null
            val baseline = baselines
                .filter { it.matchesAggregateScope(delta) }
                .filter { it.isOlderThan(delta.candidate.validFrom) }
                .maxByOrNull { it.claim.validFrom ?: it.claim.createdAt }
                ?: return@mapNotNull null
            MatchedAggregateDelta(baseline, delta)
        }

        return matchedDeltas.groupBy { it.baseline.claim.id }
            .values
            .mapNotNull { deltas ->
                val totalDelta = deltas.sumOf { it.delta.value }
                if (totalDelta == 0) return@mapNotNull null
                val latest = deltas.maxBy { it.delta.candidate.validFrom ?: it.baseline.claim.createdAt }
                latest.delta.candidate.toDerivedMetricCandidate(
                    metricPolicy = metricPolicy,
                    baseline = latest.baseline,
                    value = latest.baseline.value + totalDelta,
                    delta = totalDelta,
                    contributingDeltaCount = deltas.size,
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

    private fun MemoryClaimCandidate.toAggregateDelta(): AggregateDelta? {
        val value = listOfNotNull(evidenceQuote, normalizedText, contextText)
            .mapNotNull { it.extractAggregateDeltaValue() }
            .maxByOrNull { it.absoluteValue }
            ?: return null
        return AggregateDelta(this, value)
    }

    private fun MemoryClaimCandidate.toDerivedMetricCandidate(
        metricPolicy: com.gromozeka.domain.model.memory.MemoryPredicateDefinition,
        baseline: AggregateBaseline,
        value: Int,
        delta: Int,
        contributingDeltaCount: Int,
    ): MemoryClaimCandidate =
        copy(
            subjectEntityId = baseline.claim.subjectEntityId,
            predicate = metricPolicy.predicate,
            predicateFamily = metricPolicy.predicate,
            predicatePolicy = metricPolicy,
            objectEntityId = null,
            objectValue = JsonPrimitive(value.toString()),
            normalizedText = "${baseline.claim.scope.text} current aggregate count is $value.",
            contextText = "Derived from previous aggregate count ${baseline.value} and later explicit ${delta.deltaPhrase()} in the same scope.",
            scope = baseline.claim.scope,
            qualifiers = buildJsonObject {
                put("derived_from_claim_id", baseline.claim.id.value)
                put("aggregate_delta", delta)
                put("contributing_delta_count", contributingDeltaCount)
                put("aggregate_scope", baseline.claim.scope.text)
            },
            confidence = minOf(confidence, baseline.claim.confidence).coerceAtLeast(0.7),
            importance = maxOf(importance, baseline.claim.importance),
            evidenceKind = MemoryEvidenceRef.Kind.INFERRED,
            evidenceReason = "The source gives an explicit aggregate delta in the same scope as a retrieved active aggregate metric.",
            reason = "Derived updated aggregate metric from previous current_metric_value and later explicit aggregate delta.",
        )

    private fun Int.deltaPhrase(): String =
        if (this > 0) "addition of $this" else "removal of ${absoluteValue}"

    private fun AggregateBaseline.isOlderThan(candidateValidFrom: Instant?): Boolean {
        val baselineValidFrom = claim.validFrom ?: return false
        val candidateFrom = candidateValidFrom ?: return false
        return baselineValidFrom < candidateFrom
    }

    private fun AggregateBaseline.matchesAggregateScope(delta: AggregateDelta): Boolean {
        if (claim.scope.sameAggregateScopeAs(delta.candidate.scope)) return true
        val baselineTokens = claim.aggregateScopeTokens()
        val candidateTokens = delta.candidate.aggregateScopeTokens()
        if (baselineTokens.isEmpty() || candidateTokens.isEmpty()) return false

        val overlap = baselineTokens.intersect(candidateTokens)
        val scopeNumericTokens = claim.scope.text.aggregateScopeTokens().filterTo(mutableSetOf()) { it.all(Char::isDigit) }
        if (scopeNumericTokens.isNotEmpty() && overlap.none { it in scopeNumericTokens }) return false

        val requiredOverlap = minOf(2, minOf(baselineTokens.size, candidateTokens.size))
        return overlap.size >= requiredOverlap
    }

    private fun MemoryScope.sameAggregateScopeAs(other: MemoryScope): Boolean =
        aggregateScopeKey() == other.aggregateScopeKey() || text.normalizedScopeText() == other.text.normalizedScopeText()

    private fun MemoryClaim.aggregateScopeTokens(): Set<String> =
        scope.text.aggregateScopeTokens() +
            listOfNotNull(normalizedText, contextText)
                .joinToString(" ")
                .aggregateScopeTokens()
                .filterNot { it.all(Char::isDigit) }

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

    private fun String.extractAggregateDeltaValue(): Int? {
        val text = lowercase()
        if (aggregateDeltaNegationRegex.containsMatchIn(text)) return null

        val additions = aggregateAdditionRegex.findAll(text).map { it.aggregateQuantityOrOne() }.toList()
        val removals = aggregateRemovalRegex.findAll(text).map { it.aggregateQuantityOrOne() }.toList()
        return when {
            additions.isNotEmpty() && removals.isEmpty() -> additions.sum()
            removals.isNotEmpty() && additions.isEmpty() -> -removals.sum()
            else -> null
        }
    }

    private fun MatchResult.aggregateQuantityOrOne(): Int {
        val normalized = value.trim().lowercase()
        groupValues.drop(1).firstNotNullOfOrNull { it.toIntOrNull() }?.let { return it }
        return when (normalized.split(spaceRegex).lastOrNull()) {
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            "eight" -> 8
            "nine" -> 9
            "ten" -> 10
            else -> 1
        }
    }

    private data class AggregateBaseline(
        val claim: MemoryClaim,
        val value: Int,
    )

    private data class AggregateDelta(
        val candidate: MemoryClaimCandidate,
        val value: Int,
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
    private data class MatchedAggregateDelta(
        val baseline: AggregateBaseline,
        val delta: AggregateDelta,
    )

    private val aggregateAdditionRegex =
        Regex("""(?i)\b(?:just\s+|newly\s+)?(?:added|bought|purchased|acquired|received|collected|downloaded|picked\s+up|obtained|adopted)\s+(?:(\d{1,4})|a|an|one|two|three|four|five|six|seven|eight|nine|ten|another|new)?\b""")
    private val aggregateRemovalRegex =
        Regex("""(?i)\b(?:just\s+|newly\s+)?(?:removed|sold|donated|discarded|returned|gave\s+away)\s+(?:(\d{1,4})|a|an|one|two|three|four|five|six|seven|eight|nine|ten|another|old)?\b""")
    private val aggregateDeltaNegationRegex =
        Regex("""(?i)\b(?:did\s+not|didn't|not|never|without|no\s+longer)\s+(?:add|added|buy|bought|purchase|purchased|acquire|acquired|receive|received|collect|collected|download|downloaded|pick\s+up|picked\s+up|obtain|obtained|adopt|adopted|remove|removed|sell|sold|donate|donated|discard|discarded|return|returned|give\s+away|gave\s+away)\b""")
    private val metricNumberAfterMarkerRegex =
        Regex("""(?i)\b(?:count(?:s)?(?:\s+is|\s+of)?|total(?:\s+of)?|is|are)\s+(\d{1,9})\b""")
    private val metricNumberBeforeNounRegex =
        Regex("""(?i)\b(\d{1,9})\s+(?:items?|entries|objects|coins?|books?|records?|albums?|tasks?|events?|people|members)\b""")
    private val plainIntegerRegex = Regex("""\b\d{1,9}\b""")
    private val nonAlphanumericRegex = Regex("""[^a-z0-9]+""")
    private val spaceRegex = Regex("""\s+""")
}
