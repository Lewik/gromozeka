package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.memory.MemoryEmbeddingRecord
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun unavailableEmbeddingRuntimeSkipsAutomaticIndexingWithoutCallingProvider() = runBlocking {
        val namespace = MemoryNamespace("project:test")
        val store = InMemoryMemoryStore()
        val provider = FixedEmbeddingProvider()
        val source = externalSource("source:one", namespace)
        val batch = MemoryUpdateBatch(sources = listOf(source))
        val indexer = indexer(
            store = store,
            provider = provider,
            configurationProvider = UnavailableAiConfigurationProvider,
        )

        assertEquals(batch, indexer.withEmbeddings(batch))
        assertNull(indexer.searchEmbedding("remember source one"))
        assertTrue(provider.requestSizes.isEmpty())
    }

    private fun indexer(
        store: InMemoryMemoryStore,
        provider: AiEmbeddingProvider,
        configurationProvider: AiConfigurationProvider = TestAiConfigurationProvider,
    ): DefaultMemoryEmbeddingIndexer =
        DefaultMemoryEmbeddingIndexer(
            aiConfigurationProvider = configurationProvider,
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

private object TestAiConfigurationProvider : AiConfigurationProvider {
    private val connection = AiConnection.OpenAiApi(
        id = AiConnection.Id("test-openai"),
        displayName = "Test OpenAI",
        enabled = true,
    )
    private val modelConfiguration = AiModelConfiguration(
        id = AiModelConfiguration.Id("test-embedding"),
        connectionId = connection.id,
        providerModelId = "text-embedding-3-large",
        displayName = "Test embedding",
    )
    private val modelSpec = AiModelSpec(
        id = modelConfiguration.providerModelId,
        provider = AiProvider.OPENAI,
        capabilities = AiModelCapability.entries.toSet(),
        limits = AiModelSpec.Limits(
            textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 128_000),
            embeddings = AiModelSpec.Limits.Embeddings(
                dimensions = 3_072,
                maxInputTokens = 8_191,
            ),
        ),
    )
    override val snapshot: AiCatalogSnapshot = AiCatalogSnapshot(
        catalog = AiCatalog(
            connections = listOf(connection),
            modelSpecs = listOf(modelSpec),
            modelConfigurations = listOf(modelConfiguration),
            runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                .filter { it.requiresExplicitAssignment }
                .map { AiRuntimeAssignment(it, AiRuntimeSelection(modelConfiguration.id)) },
            defaultAgentId = AgentDefinition.Id("test-agent"),
        ),
        revision = 0,
    )
    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = MutableStateFlow(snapshot)

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        require(selection.modelConfigurationId == modelConfiguration.id)
        return ResolvedAiRuntime(connection, modelConfiguration, modelSpec)
    }
}

private object UnavailableAiConfigurationProvider : AiConfigurationProvider {
    override val snapshot: AiCatalogSnapshot = TestAiConfigurationProvider.snapshot.copy(
        catalog = TestAiConfigurationProvider.snapshot.catalog.copy(
            connections = TestAiConfigurationProvider.snapshot.catalog.connections.map { connection ->
                when (connection) {
                    is AiConnection.OpenAiApi -> connection.copy(enabled = false)
                    else -> error("Unexpected test connection: ${connection::class.simpleName}")
                }
            }
        )
    )
    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = MutableStateFlow(snapshot)

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
        error("Unavailable runtime must not be resolved strictly")
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
