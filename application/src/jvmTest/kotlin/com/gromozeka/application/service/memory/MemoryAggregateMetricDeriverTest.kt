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
            normalizedText = "User's field guide collection contains 4 items.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user owns a new field guide in User's field guide collection.",
            contextText = "The user just added a new field guide to the collection.",
            evidenceQuote = "I just added a new field guide to my collection",
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
    fun ignoresAdditionInDifferentScope() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection contains 4 items.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user owns a new coin in User's coin collection.",
            contextText = "The user just added a new coin to another collection.",
            evidenceQuote = "I just added a new coin to my other collection",
            scope = MemoryScope.Entity("User's coin collection", USER_ENTITY),
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        assertEquals(emptyList(), derive(baseline, candidate))
    }

    @Test
    fun derivesUpdatedMetricWhenCandidateScopeIsBroaderButEvidenceMatchesCollection() {
        val baselineScope = MemoryScope.Entity("User's pre-1920 American coins collection", COLLECTION_ENTITY)
        val baseline = metricClaim(
            normalizedText = "User's pre-1920 American coins collection contains 37 items.",
            scope = baselineScope,
            value = "37",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user owns a newly added Barber quarter in User's coin collection.",
            contextText = "The user added a Barber quarter to their collection of pre-1920 American coins.",
            evidenceQuote = "I just added a new coin to my collection of pre-1920 American coins - a Barber quarter.",
            scope = MemoryScope.Entity("User's coin collection", USER_ENTITY),
            validFrom = Instant.parse("2023-01-02T00:00:00Z"),
        )

        val derived = derive(baseline, candidate)

        assertEquals(1, derived.size)
        assertEquals(baselineScope, derived.single().scope)
        assertEquals("38", derived.single().objectValue?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun ignoresAdditionWithoutTemporalOrder() {
        val baseline = metricClaim(
            normalizedText = "User's field guide collection contains 4 items.",
            scope = metricScope,
            value = "4",
            validFrom = Instant.parse("2023-01-01T00:00:00Z"),
        )
        val candidate = membershipCandidate(
            normalizedText = "The user owns a new field guide in User's field guide collection.",
            contextText = "The user added a new field guide to the collection.",
            evidenceQuote = "I added a new field guide to my collection",
            scope = collectionScope,
            validFrom = null,
        )

        assertEquals(emptyList(), derive(baseline, candidate))
    }

    private fun derive(
        baseline: MemoryClaim,
        candidate: MemoryClaimCandidate,
    ): List<MemoryClaimCandidate> =
        MemoryAggregateMetricDeriver.derive(
            claimCandidates = listOf(candidate),
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
        scope: MemoryScope,
        validFrom: Instant?,
    ): MemoryClaimCandidate =
        MemoryClaimCandidate(
            subjectEntityId = USER_ENTITY,
            predicate = "owns",
            predicateFamily = "owns",
            objectValue = JsonPrimitive("new item"),
            normalizedText = normalizedText,
            contextText = contextText,
            scope = scope,
            qualifiers = JsonObject(emptyMap()),
            confidence = 0.95,
            importance = 6,
            validFrom = validFrom,
            evidenceQuote = evidenceQuote,
            evidenceKind = MemoryEvidenceRef.Kind.DIRECT,
            evidenceReason = "The quote directly says the item was added to the collection.",
            reason = "A new item in the collection is a durable ownership fact.",
        )

    private companion object {
        val NAMESPACE = MemoryNamespace("aggregate-metric-test")
        val USER_ENTITY = MemoryEntity.Id("entity-user")
        val COLLECTION_ENTITY = MemoryEntity.Id("entity-collection")
        val NOW = Instant.parse("2023-01-03T00:00:00Z")
        val metricScope = MemoryScope.Entity("User's field guide collection", COLLECTION_ENTITY)
        val collectionScope = MemoryScope.Entity("User's field guide collection", USER_ENTITY)
    }
}
