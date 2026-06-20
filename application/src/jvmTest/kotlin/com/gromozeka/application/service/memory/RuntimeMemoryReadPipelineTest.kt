package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionRequest
import com.gromozeka.domain.model.memory.MemoryReadSelectionResult
import com.gromozeka.domain.model.memory.MemoryReadSelector
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryThreadContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class RuntimeMemoryReadPipelineTest {
    @Test
    fun completeSetEvidenceReadSweepsBoundedSourcesBeyondLexicalTopHits() = runBlocking {
        val matchingSource = source("source-01", "Matching source mentions the obvious counted item.")
        val coverageOnlySource = source("source-02", "Coverage-only source contains another separate item.")
        val store = SourceSweepStore(listOf(matchingSource, coverageOnlySource))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(sources = 4),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Need complete source evidence for every distinct item.",
                            query = "matching obvious counted item",
                            topK = 1,
                        )
                    ),
                    requireEvidenceFallback = true,
                )
            ),
        )

        val result = pipeline.read(readRequest("How many distinct matching items are remembered?"))

        assertEquals(
            listOf("source-01", "source-02"),
            result.retrievedHits
                .filterIsInstance<MemoryStore.SearchHit.SourceHit>()
                .map { it.source.id.value }
                .sorted(),
        )
        assertTrue(result.trace.searchSteps.any { it.stage == "coverage:SOURCE" })
    }

    @Test
    fun selectedSourcesRestoreActiveTypedEvidenceSupport() = runBlocking {
        val source = source(
            id = "source-owned-item",
            text = "The source mentions a personal item that the user owns, but lexical selector may keep only the source.",
        )
        val claim = claim(
            id = "claim-owned-item",
            sourceId = source.id,
            text = "The user owns a signed personal music-media copy.",
        )
        val store = InMemoryMemoryStore(
            MemoryNamespaceSnapshot(
                sources = listOf(source),
                claims = listOf(claim),
            )
        )
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1, sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Need raw evidence for the complete set.",
                            query = "owned music media items",
                            topK = 1,
                        )
                    ),
                    requireEvidenceFallback = true,
                )
            ),
            selector = SourceOnlySelector,
        )

        val result = pipeline.read(readRequest("How many music items did I acquire?"))

        assertTrue(
            result.retrievedHits
                .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
                .any { it.claim.id == claim.id },
        )
        assertTrue(result.runtimePrompt.orEmpty().contains("claim-owned-item"))
        assertTrue(result.runtimePrompt.orEmpty().contains("semantics=POSSESSION"))
        assertTrue(result.trace.sourceSafety.restoredTypedHits.any { it.ref.id == "claim-owned-item" })
    }

    @Test
    fun runtimePromptTreatsMusicCarriersAsBroadReleaseItems() = runBlocking {
        val source = source(
            id = "source-music-carrier",
            text = "The user got a signed vinyl record after the show.",
        )
        val claim = claim(
            id = "claim-music-carrier",
            sourceId = source.id,
            text = "The user owns a signed vinyl record after the show.",
            predicate = "owns",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(claim)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need acquired music release evidence.",
                            query = "music albums EPs purchased downloaded",
                            topK = 1,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("How many music albums or EPs have I purchased or downloaded?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("For broad counts of music works or releases"), prompt)
        assertTrue(prompt.contains("vinyl record, CD, cassette, digital copy, or music download"), prompt)
    }

    @Test
    fun runtimePromptTreatsAggregateMetricOperandsAsCountedInventory() = runBlocking {
        val source = source(
            id = "source-aggregate-total",
            text = "The user has 12 rare figurines and also mentioned a separate antique vase.",
        )
        val claim = claim(
            id = "claim-aggregate-total",
            sourceId = source.id,
            text = "The user's rare figurine collection contained 12 rare figurines.",
            predicate = "current_metric_value",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(claim)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need aggregate operands.",
                            query = "rare item total",
                            topK = 1,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("How many rare items do I have in total?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("define the counted inventory"), prompt)
        assertTrue(prompt.contains("do not add separate singular possession or ownership claims as +1"), prompt)
    }

    @Test
    fun runtimePromptComputesMetricDeltaFromBaselineAndLaterValue() = runBlocking {
        val source = source(
            id = "source-metric-delta",
            text = "The user had 250 subscribers initially and 350 subscribers after two weeks.",
        )
        val baseline = claim(
            id = "claim-baseline",
            sourceId = source.id,
            text = "The user's newsletter had 250 subscribers at the initial baseline.",
            predicate = "current_metric_value",
        )
        val later = claim(
            id = "claim-later",
            sourceId = source.id,
            text = "The user's newsletter had 350 subscribers after two weeks.",
            predicate = "metric_observation",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(baseline, later)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need metric delta operands.",
                            query = "newsletter subscriber increase baseline later value",
                            topK = 2,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("What was the increase in newsletter subscribers after two weeks?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("compute from compatible explicit numeric operands"), prompt)
        assertTrue(prompt.contains("baseline/previous value"), prompt)
        assertTrue(prompt.contains("later/current/final value"), prompt)
        assertTrue(prompt.contains("Do not answer \"insufficient\" solely because the baseline is phrased as an initial/start value"), prompt)
    }

    @Test
    fun runtimePromptComputesRelativeDurationAgainstNamedAnchorEvent() = runBlocking {
        val source = source(
            id = "source-anchor-duration",
            text = "The user attended the workshop on 2024-02-01 and the product launch happened on 2024-02-10.",
        )
        val earlierEvent = claim(
            id = "claim-workshop",
            sourceId = source.id,
            text = "The user attended the workshop on 2024-02-01.",
            predicate = "attended_event",
        )
        val anchorEvent = claim(
            id = "claim-launch",
            sourceId = source.id,
            text = "The product launch happened on 2024-02-10.",
            predicate = "attended_event",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(earlierEvent, anchorEvent)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 2),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need both event dates.",
                            query = "workshop product launch dates",
                            topK = 2,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("How many days ago did I attend the workshop when the product launch happened?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("relative-duration questions that name an anchor event"), prompt)
        assertTrue(prompt.contains("compute the interval from event X to the anchor event Y"), prompt)
        assertTrue(prompt.contains("Use the current/question date only when no separate anchor event is named or retrieved"), prompt)
        assertTrue(prompt.contains("compute from the explicit start/begin/first-participation date"), prompt)
        assertTrue(prompt.contains("Do not add an as-of duration or tenure value"), prompt)
        assertTrue(prompt.contains("relative month-count questions such as \"two months ago\""), prompt)
        assertTrue(prompt.contains("one month ago is not two months ago"), prompt)
        assertTrue(prompt.contains("derive the target interval from the current/question date"), prompt)
        assertTrue(prompt.contains("do not smear one relative date cue"), prompt)
        assertTrue(prompt.contains("missing date evidence is uncertainty"), prompt)
        assertTrue(prompt.contains("place-visit questions"), prompt)
        assertTrue(prompt.contains("user-attended venue events such as lectures"), prompt)
    }

    @Test
    fun runtimePromptDoesNotTreatEmptyCountedSetAsZeroEvidence() = runBlocking {
        val source = source(
            id = "source-adjacent-baking",
            text = "The user baked a chocolate cake and a whole wheat baguette.",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Need complete evidence for baked items.",
                            query = "baked items egg tarts",
                            topK = 1,
                        )
                    ),
                    requireEvidenceFallback = true,
                )
            ),
        )

        val result = pipeline.read(readRequest("How many times did I bake egg tarts?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("An empty counted set is not evidence for zero by itself"), prompt)
        assertTrue(prompt.contains("retrieved memory explicitly states none/zero"), prompt)
    }

    @Test
    fun runtimePromptDoesNotAnswerWithAdjacentObjectValue() = runBlocking {
        val source = source(
            id = "source-adjacent-object",
            text = "The user's laptop backpack was bought on January 15 and arrived on January 20.",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Need exact purchase and arrival evidence for the requested item.",
                            query = "iPad case bought arrived",
                            topK = 1,
                        )
                    ),
                    requireEvidenceFallback = true,
                )
            ),
        )

        val result = pipeline.read(readRequest("How many days did it take for my iPad case to arrive after I bought it?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("Do not answer with a value for a different object"), prompt)
        assertTrue(prompt.contains("a caveat that the qualifier differs is not enough"), prompt)
        assertTrue(prompt.contains("A shared anchor can bridge retrieved memories only when it explicitly preserves the same fully-qualified target"), prompt)
        assertTrue(prompt.contains("the final answer must be an insufficiency answer, not a concrete adjacent answer with a warning"), prompt)
        assertTrue(prompt.contains("Do not infer missing academic level, course ownership, project identity, artifact type"), prompt)
        assertTrue(prompt.contains("do not compute or include the mismatched value"), prompt)
    }

    @Test
    fun runtimePromptDoesNotTreatConditionalRoleBridgeAsEvidence() = runBlocking {
        val source = source(
            id = "source-conditional-role",
            text = "The user's sister-in-law's twins were born on February 12.",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(sources = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.SOURCE,
                            why = "Need explicit parenthood evidence for both named alternatives.",
                            query = "Tom Alex became parent first",
                            topK = 1,
                        )
                    ),
                    requireEvidenceFallback = true,
                )
            ),
        )

        val result = pipeline.read(readRequest("Who became a parent first, Tom or Alex?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("A conditional bridge in a selected reason"), prompt)
        assertTrue(prompt.contains("Do not map an unnamed role or relative to a named person"), prompt)
    }

    @Test
    fun runtimePromptDoesNotTreatPlainProjectAssociationAsLeadership() = runBlocking {
        val source = source(
            id = "source-project-association",
            text = "The user was working on research about a broad topic.",
        )
        val claim = claim(
            id = "claim-project-association",
            sourceId = source.id,
            text = "The user was working on research about a broad topic.",
            predicate = "works_on_project",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(claim)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    coverageMode = MemoryReadPlan.CoverageMode.COMPLETE_SET,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need project leadership evidence.",
                            query = "projects led currently leading",
                            topK = 1,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("How many projects have I led or am currently leading?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("a plain works_on_project or generic project association claim is not enough"), prompt)
        assertTrue(prompt.contains("solo or user-owned project evidence"), prompt)
    }

    @Test
    fun runtimePromptExplainsMaturedPlansForLaterCurrentStateQuestions() = runBlocking {
        val source = source(
            id = "source-planned-location",
            text = "The user planned a later current storage state.",
        )
        val claim = claim(
            id = "claim-planned-location",
            sourceId = source.id,
            text = "The user intends to store a personal item in a specific container inside a broader location.",
            predicate = "has_goal",
        )
        val store = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = listOf(source), claims = listOf(claim)))
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = FixedMemoryReadPlanner(
                MemoryReadPlan(
                    needMemory = true,
                    answerMode = MemoryReadPlan.AnswerMode.FACTUAL,
                    retrievalBudget = MemoryRetrievalBudget(claims = 1),
                    retrievalRequests = listOf(
                        MemoryReadPlan.RetrievalRequest(
                            memoryType = MemorySemanticType.CLAIM,
                            why = "Need possible matured current state.",
                            query = "where currently stored",
                            topK = 1,
                        )
                    ),
                )
            ),
        )

        val result = pipeline.read(readRequest("Where do I currently keep the item?"))
        val prompt = result.runtimePrompt.orEmpty()

        assertTrue(prompt.contains("can be a matured current state"), prompt)
        assertTrue(prompt.contains("combine them into the most specific location"), prompt)
    }

    private class FixedMemoryReadPlanner(
        private val plan: MemoryReadPlan,
    ) : MemoryReadPlanner {
        override suspend fun plan(request: MemoryReadRequest): MemoryReadPlan = plan
    }

    private object SourceOnlySelector : MemoryReadSelector {
        override suspend fun select(request: MemoryReadSelectionRequest): MemoryReadSelectionResult =
            MemoryReadSelectionResult(
                selectedHits = request.candidateHits.filterIsInstance<MemoryStore.SearchHit.SourceHit>(),
                summary = "Test selector kept only raw sources.",
            )
    }

    private class SourceSweepStore private constructor(
        private val sources: List<MemorySource.ExternalRecord>,
        private val delegateStore: InMemoryMemoryStore,
    ) : MemoryStore by delegateStore {
        constructor(sources: List<MemorySource.ExternalRecord>) : this(
            sources = sources,
            delegateStore = InMemoryMemoryStore(MemoryNamespaceSnapshot(sources = sources)),
        )

        override suspend fun search(request: MemoryStore.SearchRequest): List<MemoryStore.SearchHit> {
            if (request.scopes != setOf(MemoryStore.SearchScope.SOURCES)) {
                return delegateStore.search(request)
            }

            val selectedSources = if (request.query.isBlank()) {
                sources
            } else {
                sources.take(1)
            }
            return selectedSources
                .take(request.limit)
                .map { MemoryStore.SearchHit.SourceHit(it, score = 1.0) }
        }
    }

    private companion object {
        private val NAMESPACE = MemoryNamespace("runtime-read-test")
        private val NOW = Instant.parse("2026-01-02T03:04:05Z")

        private fun readRequest(text: String): MemoryReadRequest {
            val message = Conversation.Message(
                id = Conversation.Message.Id("target-message"),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                createdAt = NOW,
            )
            return MemoryReadRequest(
                namespace = NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("thread"),
                    targetMessageId = message.id,
                    messages = listOf(message),
                ),
            )
        }

        private fun source(
            id: String,
            text: String,
        ): MemorySource.ExternalRecord =
            MemorySource.ExternalRecord(
                id = MemorySource.Id(id),
                namespace = NAMESPACE,
                recordRef = "test:$id",
                contentText = text,
                contentHash = id,
                observedAt = NOW,
                createdAt = NOW,
            )

        private fun claim(
            id: String,
            sourceId: MemorySource.Id,
            text: String,
            predicate: String = "owns",
        ): MemoryClaim =
            MemoryPredicateCatalogDefaults.forNamespace(NAMESPACE)
                .single { it.predicate == predicate }
                .let { predicatePolicy ->
                    MemoryClaim(
                        id = MemoryClaim.Id(id),
                        namespace = NAMESPACE,
                        subjectEntityId = MemoryEntity.Id("entity:user"),
                        predicate = predicate,
                        predicateFamily = predicate,
                        predicatePolicy = predicatePolicy,
                        normalizedText = text,
                        scope = MemoryScope.Global("User possessions"),
                        confidence = 1.0,
                        importance = 7,
                        firstSeenAt = NOW,
                        lastSeenAt = NOW,
                        evidenceRefs = listOf(
                            MemoryEvidenceRef(
                                sourceId = sourceId,
                                kind = MemoryEvidenceRef.Kind.DIRECT,
                                cachedQuote = "my signed personal music-media copy",
                            )
                        ),
                        createdAt = NOW,
                        updatedAt = NOW,
                    )
                }
    }
}
