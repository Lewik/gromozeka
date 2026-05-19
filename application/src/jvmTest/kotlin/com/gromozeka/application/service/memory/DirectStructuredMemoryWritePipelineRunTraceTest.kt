package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    private fun source(text: String): MemorySource =
        MemorySource.ChatTurn(
            id = MemorySource.Id("chat:message-1"),
            namespace = NAMESPACE,
            conversationId = Conversation.Id("conversation"),
            threadId = Conversation.Thread.Id("thread"),
            sourceMessageId = Conversation.Message.Id("message-1"),
            speakerRole = MemorySource.ActorRole.USER,
            contentText = text,
            contentHash = "hash",
            observedAt = NOW,
            createdAt = NOW,
        )

    private companion object {
        val NAMESPACE = MemoryNamespace("project:trace-test")
        val NOW: Instant = Instant.parse("2026-05-19T00:00:00Z")
    }
}
