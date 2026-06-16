package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MemoryAggregateMetricDeriverTest {
    @Test
    fun derivesUpdatedMetricFromLaterAdditionInSameScope() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a new field guide to User's field guide collection.",
            contextText = "The user just added a new field guide to the collection.",
            evidenceQuote = "I just added a new field guide to my collection",
            amount = 1,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals("current_metric_value", derived.single().predicate)
        assertEquals(COLLECTION_ENTITY, derived.single().subjectEntityId)
        assertEquals(metricScope, derived.single().scope)
        assertEquals("5", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
        assertEquals(MemoryEvidenceRef.Kind.INFERRED, derived.single().evidenceKind)
        assertTrue(derived.single().normalizedText.contains("5"))
    }

    @Test
    fun derivesUpdatedMetricFromLaterDecreaseInSameScope() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user removed a field guide from User's field guide collection.",
            contextText = "The user removed one field guide from the collection.",
            evidenceQuote = "I removed one field guide from my collection",
            amount = 1,
            predicate = "aggregate_decrease",
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals("3", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
        assertTrue(derived.single().contextText.orEmpty().contains("removal of 1"))
    }

    @Test
    fun ignoresAdditionInDifferentScope() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a new coin to User's coin collection.",
            contextText = "The user just added a new coin to another collection.",
            evidenceQuote = "I just added a new coin to my other collection",
            amount = 1,
            scope = MemoryScope.Entity("User's coin collection", USER_ENTITY),
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        assertEquals(emptyList(), derive(baseline, candidate))
    }

    @Test
    fun derivesUpdatedMetricWhenCandidateScopeIsBroaderButEvidenceMatchesCollection() {
        val baselineScope = MemoryScope.Entity("User's pre-1920 American coins collection", COLLECTION_ENTITY)
        val baseline = metricClaim(
            normalizedText = "User's pre-1920 American coins collection current aggregate count is 37.",
            scope = baselineScope,
            value = "37",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a Barber quarter to User's coin collection.",
            contextText = "The user added a Barber quarter to their collection of pre-1920 American coins.",
            evidenceQuote = "I just added a new coin to my collection of pre-1920 American coins - a Barber quarter.",
            amount = 1,
            scope = MemoryScope.Entity("User's coin collection", USER_ENTITY),
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals(baselineScope, derived.single().scope)
        assertEquals("38", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun doesNotRequireTheCurrentMetricNumberToAppearInTheDeltaEvidence() {
        val baselineScope = MemoryScope.Entity("User's American coin collection", COLLECTION_ENTITY)
        val baseline = metricClaim(
            normalizedText = "User's American coin collection current aggregate count is 37.",
            scope = baselineScope,
            value = "37",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a Morgan dollar to User's broader coin collection.",
            contextText = "The Morgan dollar belongs to the user's American coin collection.",
            evidenceQuote = "I just added a Morgan dollar to my American coin collection.",
            amount = 1,
            scope = MemoryScope.Entity("User's broader coin collection", USER_ENTITY),
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals("38", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun accumulatesMultipleLaterDeltasForSameBaseline() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val firstCandidate = membershipCandidate(
            normalizedText = "The user added a new field guide to User's field guide collection.",
            contextText = "The user just added a new field guide to the collection.",
            evidenceQuote = "I just added a new field guide to my collection",
            amount = 1,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )
        val secondCandidate = membershipCandidate(
            normalizedText = "The user bought another field guide for User's field guide collection.",
            contextText = "The user bought another field guide for the same collection.",
            evidenceQuote = "I bought another field guide for the same collection",
            amount = 1,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-03T00:00:00Z"),
        )

        val derived = derive(baseline, firstCandidate, secondCandidate)

        assertEquals(1, derived.size)
        assertEquals("6", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
        assertTrue(derived.single().contextText.orEmpty().contains("addition of 2"))
    }

    @Test
    fun doesNotDuplicateSameDeltaRepeatedAcrossCandidateFields() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added two field guides to User's field guide collection.",
            contextText = "The user added two field guides to the same collection.",
            evidenceQuote = "I added two field guides to my collection",
            amount = 2,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals("6", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
        assertTrue(derived.single().contextText.orEmpty().contains("addition of 2"))
    }

    @Test
    fun sumsMultipleExplicitDeltasWithinSingleEvidenceField() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user expanded User's field guide collection.",
            contextText = "The user added several field guides to the collection.",
            evidenceQuote = "I bought two field guides and received three more field guides for my collection",
            amount = 5,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals("9", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
        assertTrue(derived.single().contextText.orEmpty().contains("addition of 5"))
    }

    @Test
    fun ignoresCandidateWithoutAggregateDeltaPolicy() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a field guide to User's field guide collection.",
            contextText = "The user explicitly added a field guide to the collection.",
            evidenceQuote = "I added a field guide to my collection",
            amount = 1,
            scope = collectionScope,
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
            predicate = "custom_collection_event",
            predicateFamily = "custom_collection_event",
        )

        assertEquals(emptyList(), derive(baseline, candidate))
    }

    @Test
    fun ignoresAdditionWithoutTemporalOrder() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection current aggregate count is 4.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user added a new field guide to User's field guide collection.",
            contextText = "The user added a new field guide to the collection.",
            evidenceQuote = "I added a new field guide to my collection",
            amount = 1,
            scope = collectionScope,
            validFrom = null,
        )

        assertEquals(emptyList(), derive(baseline, candidate))
    }

    private fun derive(
        baseline: MemoryClaim,
        vararg candidates: MemoryClaimCandidate,
    ): List<MemoryClaimCandidate> =
        MemoryAggregateMetricDeriver.derive(
            claimCandidates = candidates.toList(),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(baseline, score = 1.0)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(NAMESPACE),
        )

    private fun metricClaim(
        normalizedText: String,
        scope: MemoryScope,
        value: String,
        validFrom: Instant?,
    ): MemoryClaim {
        val policy = MemoryPredicateCatalogDefaults.forNamespace(NAMESPACE)
            .resolvePredicateDefinition("current_metric_value")
        return MemoryClaim(
            id = MemoryClaim.Id("claim-metric"),
            namespace = NAMESPACE,
            subjectEntityId = COLLECTION_ENTITY,
            predicate = "current_metric_value",
            predicateFamily = "current_metric_value",
            predicatePolicy = policy,
            objectValue = JsonPrimitive(value),
            normalizedText = normalizedText,
            scope = scope,
            qualifiers = JsonObject(emptyMap()),
            confidence = 0.95,
            importance = 8,
            status = MemoryClaim.Status.ACTIVE,
            validFrom = validFrom,
            firstSeenAt = NOW,
            lastSeenAt = NOW,
            createdAt = NOW,
            updatedAt = NOW,
        )
    }

    private fun membershipCandidate(
        normalizedText: String,
        contextText: String,
        evidenceQuote: String,
        amount: Int,
        scope: MemoryScope,
        validFrom: Instant?,
        predicate: String = "aggregate_increase",
        predicateFamily: String? = predicate,
    ): MemoryClaimCandidate {
        val policy = MemoryPredicateCatalogDefaults.forNamespace(NAMESPACE)
            .resolvePredicateDefinition(predicate)
        return MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY,
            predicate = predicate,
            predicateFamily = predicateFamily,
            predicatePolicy = policy,
            objectValue = JsonPrimitive(amount),
            normalizedText = normalizedText,
            contextText = contextText,
            scope = scope,
            qualifiers = JsonObject(emptyMap()),
            confidence = 0.95,
            importance = 6,
            validFrom = validFrom,
            evidenceQuote = evidenceQuote,
            evidenceKind = MemoryEvidenceRef.Kind.DIRECT,
            evidenceReason = "The quote directly states the aggregate delta.",
            reason = "A numeric aggregate delta is durable enough to update a current aggregate metric.",
        )
    }

    private companion object {
        val NAMESPACE = MemoryNamespace("aggregate-metric-test")
        val USER_ENTITY = MemoryEntity.Id("entity-user")
        val COLLECTION_ENTITY = MemoryEntity.Id("entity-collection")
        val NOW = Instant.parse("2023-01-03T00:00:00Z")
        val metricScope = MemoryScope.Entity("User's field guide collection", COLLECTION_ENTITY)
        val collectionScope = MemoryScope.Entity("User's field guide collection", USER_ENTITY)
    }
}
