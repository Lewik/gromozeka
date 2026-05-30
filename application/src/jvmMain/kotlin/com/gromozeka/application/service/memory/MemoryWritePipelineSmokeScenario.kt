package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive

object MemoryWritePipelineSmokeScenario {
    suspend fun runSingleClaimWrite(
        now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ): DirectStructuredMemoryWriteResult {
        val namespace = MemoryNamespace("smoke")
        val source = MemorySource.ChatTurn(
            id = MemorySource.Id("smoke-source-1"),
            namespace = namespace,
            conversationId = Conversation.Id("smoke-conversation-1"),
            speakerRole = MemorySource.ActorRole.USER,
            contentText = "Gromozeka is implemented in Kotlin.",
            contentHash = "smoke-source-1-hash",
            observedAt = now,
            createdAt = now,
        )
        val gromozekaEntityId = MemoryEntity.Id("smoke-entity-gromozeka")
        val claimCandidate = MemoryClaimCandidate(
            subjectEntityId = gromozekaEntityId,
            predicate = "uses_programming_language",
            objectValue = JsonPrimitive("Kotlin"),
            normalizedText = "Gromozeka is implemented in Kotlin.",
            scope = MemoryScope.Global("Gromozeka project fact"),
            confidence = 1.0,
            importance = 8,
            evidenceQuote = "Gromozeka is implemented in Kotlin.",
            evidenceReason = "The target message states the implementation language directly.",
        )
        val store = InMemoryMemoryStore()
        val pipeline = DirectStructuredMemoryWritePipeline(
            store = store,
            router = FixedMemoryWriteRouter(),
            retrievalPlanner = FixedMemoryWriteRetrievalPlanner(),
            entityCanonicalizer = FixedMemoryEntityCanonicalizer(
                entityOps = listOf(
                    MemoryEntityCanonicalizationOp(
                        mention = "Gromozeka",
                        action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                        entityId = gromozekaEntityId,
                        newEntity = MemoryEntityCanonicalizationOp.NewEntity(
                            entityType = MemoryEntity.Type.PROJECT,
                            canonicalName = "Gromozeka",
                        ),
                        confidence = 1.0,
                        reason = "Smoke scenario creates the project anchor",
                    ),
                ),
            ),
            noteConstructor = FixedMemoryNoteConstructor(),
            noteReconciler = InsertOnlyMemoryNoteReconciler,
            claimExtractor = FixedMemoryClaimExtractor(listOf(claimCandidate)),
            claimReconciler = InsertOnlyMemoryClaimReconciler,
            actionItemUpdater = FixedMemoryActionItemUpdater(),
            materializer = DefaultDirectStructuredMemoryWriteMaterializer(
                idFactory = SequentialMemoryIdFactory("smoke"),
            ),
            clock = FixedMemoryClock(now),
        )

        val result = pipeline.write(
            DirectStructuredMemoryWriteRequest(
                namespace = namespace,
                source = source,
            ),
        )

        require(result.sourceBatch.sources.size == 1)
        require(result.memoryBatch.entities.size == 1)
        require(result.memoryBatch.claims.size == 1)
        require(result.memoryBatch.runs.size == 1)

        return result
    }
}
