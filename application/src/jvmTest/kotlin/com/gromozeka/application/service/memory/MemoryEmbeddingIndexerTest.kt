package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.memory.MemoryEmbeddingRecord
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.SettingsProvider
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryEmbeddingIndexerTest {
    @Test
    fun fullRebuildReplacesOnlyAfterSuccessfulGeneration() = runBlocking {
        val namespace = MemoryNamespace("project:test")
        val store = InMemoryMemoryStore()
        store.apply(MemoryUpdateBatch(sources = listOf(externalSource("source:one", namespace))))

        val firstResult = indexer(store, FixedEmbeddingProvider()).rebuildNamespace(namespace)
        val existingIds = firstResult.memoryBatch.embeddings.mapTo(mutableSetOf()) { it.id }

        assertEquals(1, firstResult.embeddings)
        assertEquals(existingIds, store.findEmbeddingIds(namespace, existingIds))

        assertFailsWith<IllegalStateException> {
            indexer(store, FailingEmbeddingProvider()).rebuildNamespace(namespace, MemoryEmbeddingRebuildMode.FULL)
        }

        assertEquals(existingIds, store.findEmbeddingIds(namespace, existingIds))
    }

    @Test
    fun missingRebuildInsertsOnlyAbsentEmbeddings() = runBlocking {
        val namespace = MemoryNamespace("project:test")
        val store = InMemoryMemoryStore()
        val provider = FixedEmbeddingProvider()
        val indexer = indexer(store, provider)
        store.apply(MemoryUpdateBatch(sources = listOf(externalSource("source:one", namespace))))

        val fullResult = indexer.rebuildNamespace(namespace, MemoryEmbeddingRebuildMode.FULL)
        store.apply(MemoryUpdateBatch(sources = listOf(externalSource("source:two", namespace))))

        val coverageBeforeMissing = indexer.coverage(namespace)
        assertEquals(2, coverageBeforeMissing.expectedEmbeddings)
        assertEquals(1, coverageBeforeMissing.existingEmbeddings)
        assertEquals(1, coverageBeforeMissing.missingEmbeddings)

        val missingResult = indexer.rebuildNamespace(namespace, MemoryEmbeddingRebuildMode.MISSING)
        val allIds = (fullResult.memoryBatch.embeddings + missingResult.memoryBatch.embeddings)
            .mapTo(mutableSetOf()) { it.id }

        assertEquals(1, missingResult.existingEmbeddings)
        assertEquals(1, missingResult.missingEmbeddings)
        assertEquals(1, missingResult.embeddings)
        assertEquals(0, missingResult.deletedEmbeddings)
        assertEquals(2, store.findEmbeddingIds(namespace, allIds).size)
        assertEquals(listOf(1, 1), provider.requestSizes)
    }

    private fun indexer(
        store: InMemoryMemoryStore,
        provider: AiEmbeddingProvider,
    ): DefaultMemoryEmbeddingIndexer =
        DefaultMemoryEmbeddingIndexer(
            settingsProvider = TestSettingsProvider,
            embeddingProvider = provider,
            store = store,
        )

    private fun externalSource(
        id: String,
        namespace: MemoryNamespace,
    ): MemorySource.ExternalRecord {
        val now = Instant.parse("2026-05-29T00:00:00Z")
        return MemorySource.ExternalRecord(
            id = MemorySource.Id(id),
            namespace = namespace,
            recordRef = id,
            contentText = "Remember $id",
            contentHash = id,
            observedAt = now,
            createdAt = now,
        )
    }
}

private object TestSettingsProvider : SettingsProvider {
    override val userProfile: UserProfile = UserProfile()
    override val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop()
    override val mode: AppMode = AppMode.TEST
    override val homeDirectory: String = "/tmp/gromozeka-memory-embedding-test"
}

private class FixedEmbeddingProvider : AiEmbeddingProvider {
    val requestSizes = mutableListOf<Int>()

    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
        requestSizes += request.inputs.size
        return AiEmbeddingResponse(
            modelId = "text-embedding-3-large",
            dimensions = 3_072,
            vectors = request.inputs.mapIndexed { index, input ->
                AiEmbeddingVector(
                    index = index,
                    values = List(3_072) { dimension -> ((input.length + dimension) % 7).toFloat() },
                )
            },
        )
    }
}

private class FailingEmbeddingProvider : AiEmbeddingProvider {
    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse =
        error("embedding provider failed")
}
