package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadPlanner
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryRetrievalBudget
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

    private class FixedMemoryReadPlanner(
        private val plan: MemoryReadPlan,
    ) : MemoryReadPlanner {
        override suspend fun plan(request: MemoryReadRequest): MemoryReadPlan = plan
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
    }
}
