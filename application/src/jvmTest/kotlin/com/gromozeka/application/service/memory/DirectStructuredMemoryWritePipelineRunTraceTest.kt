package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
