package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimExtractor
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteConstructor
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTaskUpdateOp
import com.gromozeka.domain.model.memory.MemoryTaskUpdater
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DirectStructuredMemoryWritePipelineRunTraceTest {
    @Test
    fun noopRoutePersistsMemoryRunWithDecisionOutput() = runBlocking {
        val store = InMemoryMemoryStore()
        val pipeline = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.NOOP,
                    reason = "Not durable enough",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            taskUpdater = FixedMemoryTaskUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(SequentialMemoryIdFactory("trace-test")),
            clock = FixedMemoryClock(NOW),
        )

        val result = pipeline.write(
            DirectStructuredMemoryWriteRequest(
                namespace = NAMESPACE,
                source = source("Can you repeat what you remember about me?"),
            )
        )

        val run = result.memoryBatch.runs.single()
        val output = assertNotNull(run.output).jsonObject
        val routeDecision = output.getValue("routeDecision").jsonObject
        val appliedOps = run.appliedOps as JsonArray

        assertEquals(MemoryRun.Type.ROUTE, run.runType)
        assertEquals("NOOP", routeDecision.getValue("decision").jsonPrimitive.content)
        assertTrue(routeDecision.getValue("reason").jsonPrimitive.content.contains("Not durable enough"))
        assertTrue(appliedOps.any { it.jsonObject.getValue("op").jsonPrimitive.content == "route" })
        assertEquals(run.id, store.loadNamespaceSnapshot(NAMESPACE).runs.single().id)
    }

    @Test
    fun documentIngestSourceOverridesRouterNoop() = runBlocking {
        val result = noopRouterPipeline().write(
            DirectStructuredMemoryWriteRequest(
                namespace = NAMESPACE,
                source = MemorySource.ExternalRecord(
                    id = MemorySource.Id("external:document-section:test"),
                    namespace = NAMESPACE,
                    recordRef = "doc.md#section:1",
                    authorLabel = "document section",
                    contentText = """
                        Document title: Doc
                        Document source: doc.md
                        Document imported at: $NOW
                        Document section: Architecture

                        # Architecture
                        Durable document fact.
                    """.trimIndent(),
                    contentPayload = buildJsonObject {
                        put("memoryToolOrigin", "provided_document_section")
                        put("sourceKind", "document")
                        put("sourceRef", "doc.md")
                        put("importedAt", NOW.toString())
                    },
                    contentHash = "document-hash",
                    observedAt = NOW,
                    createdAt = NOW,
                    retentionClass = MemorySource.RetentionClass.IMPORTED,
                ),
            )
        )

        assertEquals(MemoryRouteDecision.Decision.NOTE_WRITE, result.routeDecision.decision)
        assertTrue(result.routeDecision.reason.contains("Document ingest source overrode router NOOP"))
        assertTrue(result.routeDecision.sourcePolicy.allowStructuredExtraction)
        assertTrue(MemorySemanticType.NOTE in result.routeDecision.memoryTypes)
    }

    @Test
    fun forcedMemorySourceOverridesRouterNoop() = runBlocking {
        val result = noopRouterPipeline().write(
            DirectStructuredMemoryWriteRequest(
                namespace = NAMESPACE,
                source = source(
                    text = "Remember this exact operational note even if it looks like loose text.",
                    contentPayload = buildJsonObject {
                        put("memoryToolOrigin", "provided_text")
                        put("userConsentConfirmed", true)
                        put("forceMemoryWrite", true)
                    },
                ),
            )
        )

        assertEquals(MemoryRouteDecision.Decision.NOTE_WRITE, result.routeDecision.decision)
        assertTrue(result.routeDecision.reason.contains("Forced memory write overrode router NOOP"))
        assertTrue(result.routeDecision.sourcePolicy.allowStructuredExtraction)
        assertTrue(result.routeDecision.salience >= 0.95)
    }

    @Test
    fun writeBranchesStaySequentialWhenParallelismIsOne() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val pipeline = branchTestPipeline(
            noteConstructor = RecordingMemoryNoteConstructor(events, "note"),
            claimExtractor = RecordingMemoryClaimExtractor(events, "claim"),
            taskUpdater = RecordingMemoryTaskUpdater(events, "task"),
            branchParallelism = 1,
        )

        pipeline.write(
            DirectStructuredMemoryWriteRequest(
                namespace = NAMESPACE,
                source = source("Remember that memory write branches should stay ordered by default."),
            )
        )

        assertEquals(listOf("note", "claim", "task"), events.toList())
    }

    @Test
    fun writeBranchesCanRunInParallelWhenConfigured() = runBlocking {
        val probe = BranchStartProbe(expectedBranches = 3)
        val pipeline = branchTestPipeline(
            noteConstructor = CoordinatedMemoryNoteConstructor(probe, "note"),
            claimExtractor = CoordinatedMemoryClaimExtractor(probe, "claim"),
            taskUpdater = CoordinatedMemoryTaskUpdater(probe, "task"),
            branchParallelism = 3,
        )

        withTimeout(2_000) {
            pipeline.write(
                DirectStructuredMemoryWriteRequest(
                    namespace = NAMESPACE,
                    source = source("Remember that independent write branches may overlap when explicitly configured."),
                )
            )
        }

        assertEquals(setOf("note", "claim", "task"), probe.startedBranches.toSet())
    }

    private fun noopRouterPipeline(): DirectStructuredMemoryWritePipeline =
        DirectStructuredMemoryWritePipeline(
            store = InMemoryMemoryStore(),
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.NOOP,
                    reason = "Router decided source is not a current user assertion",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            taskUpdater = FixedMemoryTaskUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(SequentialMemoryIdFactory("trace-test")),
            clock = FixedMemoryClock(NOW),
        )

    private fun branchTestPipeline(
        noteConstructor: MemoryNoteConstructor,
        claimExtractor: MemoryClaimExtractor,
        taskUpdater: MemoryTaskUpdater,
        branchParallelism: Int,
    ): DirectStructuredMemoryWritePipeline =
        DirectStructuredMemoryWritePipeline(
            store = InMemoryMemoryStore(),
            router = FixedMemoryWriteRouter(
                MemoryRouteDecision(
                    decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                    memoryTypes = setOf(
                        MemorySemanticType.NOTE,
                        MemorySemanticType.CLAIM,
                        MemorySemanticType.TASK,
                        MemorySemanticType.ENTITY,
                        MemorySemanticType.SOURCE,
                    ),
                    salience = 1.0,
                    reason = "Scripted branch write",
                )
            ),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(),
            noteConstructor = noteConstructor,
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = claimExtractor,
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            taskUpdater = taskUpdater,
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(SequentialMemoryIdFactory("branch-test")),
            clock = FixedMemoryClock(NOW),
            branchParallelism = branchParallelism,
        )

    private fun source(
        text: String,
        contentPayload: JsonObject? = null,
    ): MemorySource =
        MemorySource.ChatTurn(
            id = MemorySource.Id("chat:message-1"),
            namespace = NAMESPACE,
            conversationId = Conversation.Id("conversation"),
            threadId = Conversation.Thread.Id("thread"),
            sourceMessageId = Conversation.Message.Id("message-1"),
            speakerRole = MemorySource.ActorRole.USER,
            contentText = text,
            contentPayload = contentPayload,
            contentHash = "hash",
            observedAt = NOW,
            createdAt = NOW,
        )

    private companion object {
        val NAMESPACE = MemoryNamespace("project:trace-test")
        val NOW: Instant = Instant.parse("2026-05-19T00:00:00Z")
    }
}

private class BranchStartProbe(
    private val expectedBranches: Int,
) {
    val startedBranches = CopyOnWriteArrayList<String>()
    private val startedCount = AtomicInteger()
    private val allStarted = CompletableDeferred<Unit>()

    suspend fun markStarted(branch: String) {
        startedBranches += branch
        if (startedCount.incrementAndGet() == expectedBranches) {
            allStarted.complete(Unit)
        }
        allStarted.await()
    }
}

private class RecordingMemoryNoteConstructor(
    private val events: MutableList<String>,
    private val branch: String,
) : MemoryNoteConstructor {
    override suspend fun construct(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryNoteCandidate> {
        events += branch
        return emptyList()
    }
}

private class RecordingMemoryClaimExtractor(
    private val events: MutableList<String>,
    private val branch: String,
) : MemoryClaimExtractor {
    override suspend fun extract(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate> {
        events += branch
        return emptyList()
    }
}

private class RecordingMemoryTaskUpdater(
    private val events: MutableList<String>,
    private val branch: String,
) : MemoryTaskUpdater {
    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryTaskUpdateOp> {
        events += branch
        return emptyList()
    }
}

private class CoordinatedMemoryNoteConstructor(
    private val probe: BranchStartProbe,
    private val branch: String,
) : MemoryNoteConstructor {
    override suspend fun construct(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryNoteCandidate> {
        probe.markStarted(branch)
        return emptyList()
    }
}

private class CoordinatedMemoryClaimExtractor(
    private val probe: BranchStartProbe,
    private val branch: String,
) : MemoryClaimExtractor {
    override suspend fun extract(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate> {
        probe.markStarted(branch)
        return emptyList()
    }
}

private class CoordinatedMemoryTaskUpdater(
    private val probe: BranchStartProbe,
    private val branch: String,
) : MemoryTaskUpdater {
    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryTaskUpdateOp> {
        probe.markStarted(branch)
        return emptyList()
    }
}
