package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LlmMemoryClaimReconcilerTest {

    @Test
    fun directInsertsWhenNoActiveClaimsWereRetrieved() = runBlocking {
        val runtime = SequencedJsonRuntime(responses = ArrayDeque())
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually prefer concise technical answers."),
            ),
            claimCandidates = listOf(conciseAnswersCandidate()),
            retrievedHits = emptyList(),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.INSERT, ops.single().action)
        assertEquals("has_constraint", ops.single().candidate?.predicate)
        assertEquals("has_constraint", ops.single().candidate?.predicatePolicy?.predicate)
        assertEquals(0, runtime.requests.size)
    }

    @Test
    fun repairsGenericDescriptionCanonicalPredicateBeforeReturningOps() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(canonicalPredicate = "is_described_as"),
                    operationJson(canonicalPredicate = "has_constraint"),
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually prefer concise technical answers."),
            ),
            claimCandidates = listOf(conciseAnswersCandidate()),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.INSERT, ops.single().action)
        assertEquals("has_constraint", ops.single().candidate?.predicate)
        assertEquals(2, runtime.requests.size)
        assertEquals(true, runtime.requests.last().options.toolContext["memoryStageRepair"])
    }

    @Test
    fun protectsCoexistingClaimsFromDestructiveSupersedeWithoutExplicitReplacement() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(
                        action = "supersede",
                        targetClaimId = "claim-existing",
                        canonicalPredicate = "metric_observation",
                        conflictPolicy = "coexist",
                    )
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("My hard-mode run took 30 hours."),
            ),
            claimCandidates = listOf(metricObservationCandidate()),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingMetricObservationClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.INSERT, ops.single().action)
        assertEquals(null, ops.single().targetClaimId)
        assertEquals("metric_observation", ops.single().candidate?.predicate)
    }

    @Test
    fun allowsCoexistingClaimSupersedeWhenReplacementIsExplicit() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(
                        action = "supersede",
                        targetClaimId = "claim-existing",
                        canonicalPredicate = "metric_observation",
                        conflictPolicy = "coexist",
                    )
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("Correction: that hard-mode run took 30 hours, not 25."),
            ),
            claimCandidates = listOf(
                metricObservationCandidate(
                    qualifiers = JsonObject(mapOf("replaces_previous" to JsonPrimitive(true))),
                )
            ),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingMetricObservationClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.SUPERSEDE, ops.single().action)
        assertEquals(MemoryClaim.Status.SUPERSEDED, ops.single().updatedClaim?.status)
    }

    @Test
    fun allowsCoexistingClaimSupersedeWhenReplacementIsExplicitInEvidence() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(
                        action = "supersede",
                        targetClaimId = "claim-existing",
                        canonicalPredicate = "metric_observation",
                        conflictPolicy = "coexist",
                    )
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("Correction: that hard-mode run took 30 hours, not 25."),
            ),
            claimCandidates = listOf(
                metricObservationCandidate(
                    evidenceQuote = "the hard-mode run took 30 hours instead of 25",
                )
            ),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingMetricObservationClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.SUPERSEDE, ops.single().action)
        assertEquals(MemoryClaim.Status.SUPERSEDED, ops.single().updatedClaim?.status)
    }

    @Test
    fun supersedesAmbiguousEventWhenLlmNoopsMorePreciseDatedDuplicate() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(
                        action = "noop",
                        targetClaimId = "claim-existing",
                        canonicalPredicate = "attended_event",
                        temporalPolicy = "time_scoped",
                        semanticKinds = listOf("event_participation"),
                    )
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I just came back from a lecture series at the Museum of Contemporary Art."),
            ),
            claimCandidates = listOf(datedLectureCandidate()),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingAmbiguousLectureClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.SUPERSEDE, ops.single().action)
        assertEquals(MemoryClaim.Id("claim-existing"), ops.single().targetClaimId)
        assertEquals("attended_event", ops.single().candidate?.predicate)
        assertEquals(MemoryClaim.Status.SUPERSEDED, ops.single().updatedClaim?.status)
    }

    @Test
    fun supersedesWeaklyAnchoredRelativeEventDateWhenLlmNoopsAsDuplicate() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(
                        action = "noop",
                        targetClaimId = "claim-existing",
                        canonicalPredicate = "attended_event",
                        temporalPolicy = "time_scoped",
                        semanticKinds = listOf("event_participation"),
                    )
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I just came back from a lecture series at the Museum of Contemporary Art."),
            ),
            claimCandidates = listOf(datedLectureCandidate()),
            retrievedHits = listOf(MemoryStore.SearchHit.ClaimHit(existingWeaklyAnchoredLectureClaim(), score = 0.9)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.SUPERSEDE, ops.single().action)
        assertEquals(MemoryClaim.Id("claim-existing"), ops.single().targetClaimId)
        assertEquals("attended_event", ops.single().candidate?.predicate)
        assertEquals(MemoryClaim.Status.SUPERSEDED, ops.single().updatedClaim?.status)
    }

    private class SequencedJsonRuntime(
        private val responses: ArrayDeque<String>,
    ) : AiRuntime {
        val requests = mutableListOf<AiRuntimeRequest>()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(responses.removeFirst()),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        val TEST_NAMESPACE = com.gromozeka.domain.model.memory.MemoryNamespace("claim-reconciler-test")
        val USER_ENTITY_ID = MemoryEntity.Id("entity-user")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun conciseAnswersCandidate(): MemoryClaimCandidate =
            MemoryClaimCandidate(
                subjectEntityId = USER_ENTITY_ID,
                predicate = "has_constraint",
                objectValue = JsonPrimitive("concise technical answers"),
                normalizedText = "The user prefers concise technical answers.",
                scope = MemoryScope.Global(
                    text = "Namespace-wide memory",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                qualifiers = JsonObject(emptyMap()),
                confidence = 0.88,
                importance = 7,
                evidenceQuote = "prefer concise technical answers",
                evidenceReason = "The target explicitly states the preference.",
                reason = "This is a durable answer style preference.",
            )

        fun metricObservationCandidate(
            qualifiers: JsonObject = JsonObject(emptyMap()),
            evidenceQuote: String = "hard-mode run took 30 hours",
        ): MemoryClaimCandidate =
            MemoryClaimCandidate(
                subjectEntityId = USER_ENTITY_ID,
                predicate = "metric_observation",
                objectValue = JsonPrimitive("30 hours"),
                normalizedText = "The user's hard-mode run took 30 hours.",
                scope = MemoryScope.Global(
                    text = "Hard-mode run duration",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                qualifiers = qualifiers,
                confidence = 0.99,
                importance = 7,
                evidenceQuote = evidenceQuote,
                evidenceReason = "The target explicitly states the observed duration.",
                reason = "This is a historical metric observation.",
            )

        fun existingClaim(): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id("claim-existing"),
                namespace = TEST_NAMESPACE,
                subjectEntityId = USER_ENTITY_ID,
                predicate = "has_constraint",
                predicateFamily = "has_constraint",
                predicatePolicy = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE)
                    .first { it.predicate == "has_constraint" },
                objectValue = JsonPrimitive("brief answers"),
                normalizedText = "The user prefers brief answers.",
                scope = MemoryScope.Global(
                    text = "Namespace-wide memory",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                confidence = 0.8,
                importance = 6,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun existingMetricObservationClaim(): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id("claim-existing"),
                namespace = TEST_NAMESPACE,
                subjectEntityId = USER_ENTITY_ID,
                predicate = "metric_observation",
                predicateFamily = "metric_observation",
                predicatePolicy = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE)
                    .first { it.predicate == "metric_observation" },
                objectValue = JsonPrimitive("25 hours"),
                normalizedText = "The user's normal-mode run took 25 hours.",
                scope = MemoryScope.Global(
                    text = "Normal-mode run duration",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                confidence = 0.99,
                importance = 7,
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun datedLectureCandidate(): MemoryClaimCandidate =
            MemoryClaimCandidate(
                subjectEntityId = USER_ENTITY_ID,
                predicate = "attended_event",
                objectValue = JsonPrimitive(
                    "Lecture series at the Museum of Contemporary Art attended by the user shortly before or on 2023-01-22.",
                ),
                normalizedText = "The user attended a lecture series at the Museum of Contemporary Art shortly before or on 2023-01-22.",
                scope = MemoryScope.Global(
                    text = "User's dated art events",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                qualifiers = JsonObject(emptyMap()),
                confidence = 0.97,
                importance = 8,
                validFrom = Instant.parse("2023-01-22T18:05:00Z"),
                evidenceQuote = "I just came back from a lecture series at the Museum of Contemporary Art",
                evidenceReason = "The target explicitly states recent attendance in the dated source.",
                reason = "This is a more precisely dated event claim.",
            )

        fun existingAmbiguousLectureClaim(): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id("claim-existing"),
                namespace = TEST_NAMESPACE,
                subjectEntityId = USER_ENTITY_ID,
                predicate = "attended_event",
                predicateFamily = "attended_event",
                predicatePolicy = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE)
                    .first { it.predicate == "attended_event" },
                objectValue = JsonPrimitive("Lecture series at the Museum of Contemporary Art attended by the user recently."),
                normalizedText = "The user recently attended a lecture series at the Museum of Contemporary Art.",
                scope = MemoryScope.Global(
                    text = "User's art events",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                confidence = 0.95,
                importance = 7,
                validFrom = Instant.parse("2023-01-15T08:39:00Z"),
                evidenceRefs = listOf(
                    MemoryEvidenceRef(
                        sourceId = MemorySource.Id("source-old"),
                        kind = MemoryEvidenceRef.Kind.IMPORTED,
                        cachedQuote = "I attended a lecture series at the Museum of Contemporary Art recently.",
                    )
                ),
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun existingWeaklyAnchoredLectureClaim(): MemoryClaim =
            MemoryClaim(
                id = MemoryClaim.Id("claim-existing"),
                namespace = TEST_NAMESPACE,
                subjectEntityId = USER_ENTITY_ID,
                predicate = "attended_event",
                predicateFamily = "attended_event",
                predicatePolicy = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE)
                    .first { it.predicate == "attended_event" },
                objectValue = JsonPrimitive("Lecture series at the Museum of Contemporary Art attended by the user before or around 2023-01-15."),
                normalizedText = "The user attended a lecture series at the Museum of Contemporary Art before or around 2023-01-15.",
                scope = MemoryScope.Global(
                    text = "User's art events",
                    basis = MemoryScope.Basis.EXPLICIT,
                ),
                confidence = 0.95,
                importance = 7,
                validFrom = Instant.parse("2023-01-15T08:39:00Z"),
                evidenceRefs = listOf(
                    MemoryEvidenceRef(
                        sourceId = MemorySource.Id("source-old"),
                        kind = MemoryEvidenceRef.Kind.IMPORTED,
                        cachedQuote = "I attended a lecture series at the Museum of Contemporary Art recently.",
                    )
                ),
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )

        fun source(text: String): MemorySource =
            MemorySource.ChatTurn(
                id = MemorySource.Id("source"),
                namespace = TEST_NAMESPACE,
                conversationId = Conversation.Id("conversation"),
                threadId = Conversation.Thread.Id("thread"),
                sourceMessageId = Conversation.Message.Id("message"),
                speakerRole = MemorySource.ActorRole.USER,
                contentText = text,
                contentHash = "source-hash",
                observedAt = NOW,
                createdAt = NOW,
            )

        fun operationJson(
            canonicalPredicate: String,
            action: String = "insert",
            targetClaimId: String? = null,
            conflictPolicy: String = "coexist",
            temporalPolicy: String = "status_like",
            semanticKinds: List<String> = listOf("constraint"),
        ): String =
            """
            {
              "operations": [
                {
                  "action": "$action",
                  "candidate_index": 0,
                  "target_claim_id": ${targetClaimId?.let { "\"$it\"" } ?: "null"},
                  "canonical_predicate": "$canonicalPredicate",
                  "predicate_family": "$canonicalPredicate",
                  "predicate_description": "Test predicate",
                  "object_kind": "string",
                  "cardinality": "multi",
                  "temporal_policy": "$temporalPolicy",
                  "conflict_policy": "$conflictPolicy",
                  "semantic_kinds": [${semanticKinds.joinToString(",") { "\"$it\"" }}],
                  "aggregate_effect": "none",
                  "reason": "Test operation."
                }
              ]
            }
            """.trimIndent()
    }
}
