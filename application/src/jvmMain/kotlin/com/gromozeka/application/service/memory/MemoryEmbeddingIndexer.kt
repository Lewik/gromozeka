package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEmbeddingRecord
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

interface MemoryEmbeddingIndexer {
    suspend fun withEmbeddings(batch: MemoryUpdateBatch): MemoryUpdateBatch

    suspend fun searchEmbedding(query: String): MemoryStore.SearchEmbedding?

    suspend fun rebuildNamespace(namespace: MemoryNamespace): MemoryEmbeddingRebuildResult

    fun status(): MemoryEmbeddingIndexStatus
}

object NoOpMemoryEmbeddingIndexer : MemoryEmbeddingIndexer {
    override suspend fun withEmbeddings(batch: MemoryUpdateBatch): MemoryUpdateBatch = batch

    override suspend fun searchEmbedding(query: String): MemoryStore.SearchEmbedding? = null

    override suspend fun rebuildNamespace(namespace: MemoryNamespace): MemoryEmbeddingRebuildResult =
        MemoryEmbeddingRebuildResult(
            namespace = namespace,
            modelConfigurationId = "",
            providerModelId = "",
            dimensions = 0,
            embeddableItems = 0,
            embeddings = 0,
            memoryBatch = MemoryUpdateBatch(),
        )

    override fun status(): MemoryEmbeddingIndexStatus = MemoryEmbeddingIndexStatus()
}

@Service
class DefaultMemoryEmbeddingIndexer(
    private val settingsProvider: SettingsProvider,
    private val embeddingProvider: AiEmbeddingProvider,
    private val store: MemoryStore,
) : MemoryEmbeddingIndexer {
    private val log = KLoggers.logger(this)
    private val totalEmbeddedItems = AtomicLong(0)
    private val totalEmbeddingRequests = AtomicLong(0)
    private val totalRebuilds = AtomicLong(0)
    private val totalFailedRequests = AtomicLong(0)

    override suspend fun withEmbeddings(batch: MemoryUpdateBatch): MemoryUpdateBatch {
        val resolved = resolveEmbeddingRuntime()
        val entries = batch.toEmbeddingInputs(
            modelConfigurationId = resolved.modelConfigurationId,
            providerModelId = resolved.providerModelId,
            maxInputTokens = resolved.maxInputTokens,
        )
        if (entries.isEmpty()) return batch

        val embeddings = embedEntries(resolved, entries)
        return batch.copy(embeddings = batch.embeddings + embeddings)
    }

    override suspend fun searchEmbedding(query: String): MemoryStore.SearchEmbedding? {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return null
        val resolved = resolveEmbeddingRuntime()
        val truncatedQuery = normalizedQuery.truncateForEmbedding(resolved.maxInputTokens)
        val response = requestEmbeddings(resolved, listOf(truncatedQuery))
        val vector = response.vectors.singleOrNull()?.values
            ?: error("AI embedding response returned ${response.vectors.size} vectors for one search query")
        return MemoryStore.SearchEmbedding(
            modelConfigurationId = resolved.modelConfigurationId,
            providerModelId = response.modelId,
            vector = vector,
        )
    }

    override suspend fun rebuildNamespace(namespace: MemoryNamespace): MemoryEmbeddingRebuildResult {
        val resolved = resolveEmbeddingRuntime()
        val snapshot = store.loadNamespaceSnapshot(namespace)
        val batch = MemoryUpdateBatch(
            sources = snapshot.sources,
            entities = snapshot.entities,
            claims = snapshot.claims,
            notes = snapshot.notes,
            tasks = snapshot.tasks,
            profiles = snapshot.profiles,
            episodes = snapshot.episodes,
        )
        val indexedBatch = withEmbeddings(batch)
        val embeddingBatch = MemoryUpdateBatch(embeddings = indexedBatch.embeddings)
        if (embeddingBatch.embeddings.isNotEmpty()) {
            store.apply(embeddingBatch)
        }
        totalRebuilds.incrementAndGet()
        log.info {
            "Memory embeddings rebuilt: namespace=${namespace.value} model=${resolved.modelConfigurationId}/${resolved.providerModelId} " +
                "items=${indexedBatch.embeddings.size} dimensions=${resolved.dimensions}"
        }
        return MemoryEmbeddingRebuildResult(
            namespace = namespace,
            modelConfigurationId = resolved.modelConfigurationId,
            providerModelId = resolved.providerModelId,
            dimensions = resolved.dimensions,
            embeddableItems = batch.embeddableItemCount(),
            embeddings = indexedBatch.embeddings.size,
            memoryBatch = embeddingBatch,
        )
    }

    override fun status(): MemoryEmbeddingIndexStatus =
        MemoryEmbeddingIndexStatus(
            totalEmbeddedItems = totalEmbeddedItems.get(),
            totalEmbeddingRequests = totalEmbeddingRequests.get(),
            totalRebuilds = totalRebuilds.get(),
            totalFailedRequests = totalFailedRequests.get(),
        )

    private suspend fun embedEntries(
        resolved: ResolvedEmbeddingRuntime,
        entries: List<EmbeddingInput>,
    ): List<MemoryEmbeddingRecord> {
        val now = Clock.System.now()
        return entries.chunked(EmbeddingBatchSize).flatMap { batch ->
            val response = requestEmbeddings(resolved, batch.map { it.text })
            require(response.vectors.size == batch.size) {
                "AI embedding response returned ${response.vectors.size} vectors for ${batch.size} inputs"
            }
            response.vectors
                .sortedBy { it.index }
                .mapIndexed { outputIndex, vector ->
                    val input = batch[outputIndex]
                    MemoryEmbeddingRecord(
                        id = input.embeddingId,
                        namespace = input.namespace,
                        itemRef = input.itemRef,
                        modelConfigurationId = resolved.modelConfigurationId,
                        providerModelId = response.modelId,
                        dimensions = response.dimensions,
                        contentHash = input.contentHash,
                        vector = vector.values,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
        }.also { totalEmbeddedItems.addAndGet(it.size.toLong()) }
    }

    private suspend fun requestEmbeddings(
        resolved: ResolvedEmbeddingRuntime,
        inputs: List<String>,
    ) = runCatching {
        totalEmbeddingRequests.incrementAndGet()
        embeddingProvider.embed(
            AiEmbeddingRequest(
                selection = resolved.selection,
                inputs = inputs,
            )
        )
    }.onFailure {
        totalFailedRequests.incrementAndGet()
    }.getOrThrow().also { response ->
        require(response.dimensions == resolved.dimensions) {
            "AI embedding model ${resolved.providerModelId} returned ${response.dimensions} dimensions, expected ${resolved.dimensions}"
        }
        require(response.modelId == resolved.providerModelId) {
            "AI embedding response model ${response.modelId} differs from configured model ${resolved.providerModelId}"
        }
    }

    private fun resolveEmbeddingRuntime(): ResolvedEmbeddingRuntime {
        val selection = settingsProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS)
        val runtime = settingsProvider.resolveAiRuntime(selection)
        val spec = settingsProvider.userProfile.aiSettings.modelSpecFor(runtime.modelConfiguration)
            ?: error("AI embedding model spec not found: ${runtime.modelConfiguration.providerModelId}")
        require(AiModelCapability.EMBEDDINGS in spec.capabilities) {
            "AI model ${runtime.modelConfiguration.providerModelId} does not support embeddings"
        }
        val embeddingLimits = spec.limits.embeddings
            ?: error("AI embedding model ${runtime.modelConfiguration.providerModelId} must declare embedding limits")
        val dimensions = embeddingLimits.dimensions
            ?: error("AI embedding model ${runtime.modelConfiguration.providerModelId} must declare dimensions")
        return ResolvedEmbeddingRuntime(
            selection = selection,
            modelConfigurationId = runtime.modelConfiguration.id.value,
            providerModelId = runtime.modelConfiguration.providerModelId,
            dimensions = dimensions,
            maxInputTokens = embeddingLimits.maxInputTokens,
        )
    }

    private data class ResolvedEmbeddingRuntime(
        val selection: com.gromozeka.domain.model.ai.AiRuntimeSelection,
        val modelConfigurationId: String,
        val providerModelId: String,
        val dimensions: Int,
        val maxInputTokens: Int?,
    )

    private companion object {
        private const val EmbeddingBatchSize = 64
    }
}

data class MemoryEmbeddingIndexStatus(
    val totalEmbeddedItems: Long = 0,
    val totalEmbeddingRequests: Long = 0,
    val totalRebuilds: Long = 0,
    val totalFailedRequests: Long = 0,
)

data class MemoryEmbeddingRebuildResult(
    val namespace: MemoryNamespace,
    val modelConfigurationId: String,
    val providerModelId: String,
    val dimensions: Int,
    val embeddableItems: Int,
    val embeddings: Int,
    val memoryBatch: MemoryUpdateBatch,
) {
    val summary: String =
        "Rebuilt $embeddings/$embeddableItems memory embeddings for ${namespace.value} using $modelConfigurationId."
}

private data class EmbeddingInput(
    val embeddingId: MemoryEmbeddingRecord.Id,
    val namespace: MemoryNamespace,
    val itemRef: MemoryItemRef,
    val contentHash: String,
    val text: String,
)

private fun MemoryUpdateBatch.toEmbeddingInputs(
    modelConfigurationId: String,
    providerModelId: String,
    maxInputTokens: Int?,
): List<EmbeddingInput> =
    buildList {
        sources.mapNotNullTo(this) {
            if (it.deletedAt != null || !it.usagePolicy.allowRecall) {
                null
            } else {
                it.toEmbeddingInput(
                    ref = MemoryItemRef(MemoryItemRef.Type.SOURCE, it.id.value),
                    modelConfigurationId = modelConfigurationId,
                    providerModelId = providerModelId,
                    text = it.embeddingText(),
                    maxInputTokens = maxInputTokens,
                )
            }
        }
        entities.mapNotNullTo(this) {
            it.toEmbeddingInput(
                ref = MemoryItemRef(MemoryItemRef.Type.ENTITY, it.id.value),
                modelConfigurationId = modelConfigurationId,
                providerModelId = providerModelId,
                text = it.embeddingText(),
                maxInputTokens = maxInputTokens,
            )
        }
        claims.mapNotNullTo(this) {
            if (it.archivedAt != null) {
                null
            } else {
                it.toEmbeddingInput(
                    ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value),
                    modelConfigurationId = modelConfigurationId,
                    providerModelId = providerModelId,
                    text = it.embeddingText(),
                    maxInputTokens = maxInputTokens,
                )
            }
        }
        notes.mapNotNullTo(this) {
            if (it.archivedAt != null) {
                null
            } else {
                it.toEmbeddingInput(
                    ref = MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value),
                    modelConfigurationId = modelConfigurationId,
                    providerModelId = providerModelId,
                    text = it.embeddingText(),
                    maxInputTokens = maxInputTokens,
                )
            }
        }
        tasks.mapNotNullTo(this) {
            if (it.archivedAt != null) {
                null
            } else {
                it.toEmbeddingInput(
                    ref = MemoryItemRef(MemoryItemRef.Type.TASK, it.id.value),
                    modelConfigurationId = modelConfigurationId,
                    providerModelId = providerModelId,
                    text = it.embeddingText(),
                    maxInputTokens = maxInputTokens,
                )
            }
        }
        profiles.mapNotNullTo(this) {
            it.toEmbeddingInput(
                ref = MemoryItemRef(MemoryItemRef.Type.PROFILE, it.id.value),
                modelConfigurationId = modelConfigurationId,
                providerModelId = providerModelId,
                text = it.embeddingText(),
                maxInputTokens = maxInputTokens,
            )
        }
        episodes.mapNotNullTo(this) {
            if (it.archivedAt != null) {
                null
            } else {
                it.toEmbeddingInput(
                    ref = MemoryItemRef(MemoryItemRef.Type.EPISODE, it.id.value),
                    modelConfigurationId = modelConfigurationId,
                    providerModelId = providerModelId,
                    text = it.embeddingText(),
                    maxInputTokens = maxInputTokens,
                )
            }
        }
    }

private fun MemoryUpdateBatch.embeddableItemCount(): Int =
    sources.count { it.deletedAt == null && it.usagePolicy.allowRecall } +
        entities.size +
        claims.count { it.archivedAt == null } +
        notes.count { it.archivedAt == null } +
        tasks.count { it.archivedAt == null } +
        profiles.size +
        episodes.count { it.archivedAt == null }

private fun MemorySource.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryEntity.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryClaim.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryNote.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryTask.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryProfile.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun MemoryEpisode.toEmbeddingInput(
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? = toEmbeddingInput(namespace, ref, modelConfigurationId, providerModelId, text, maxInputTokens)

private fun toEmbeddingInput(
    namespace: MemoryNamespace,
    ref: MemoryItemRef,
    modelConfigurationId: String,
    providerModelId: String,
    text: String,
    maxInputTokens: Int?,
): EmbeddingInput? {
    val normalizedText = text.trim().replace(Regex("\\s+"), " ")
    if (normalizedText.isBlank()) return null
    val truncatedText = normalizedText.truncateForEmbedding(maxInputTokens)
    val stableKey = "${namespace.value}|${ref.type.name}|${ref.id}|$modelConfigurationId"
    return EmbeddingInput(
        embeddingId = MemoryEmbeddingRecord.Id("embedding:${stableKey.sha256()}"),
        namespace = namespace,
        itemRef = ref,
        contentHash = "${providerModelId}:${truncatedText.sha256()}",
        text = truncatedText,
    )
}

private fun MemorySource.embeddingText(): String =
    buildList {
        add("source")
        add(contentText)
        searchText?.takeIf { it.isNotBlank() }?.let { add("search: $it") }
    }.joinToString("\n")

private fun MemoryEntity.embeddingText(): String =
    buildList {
        add("entity ${entityType.name}")
        add(canonicalName)
        add(normalizedName)
        if (observedTypes.isNotEmpty()) add("observed types: ${observedTypes.joinToString { it.name }}")
        summary?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (aliases.isNotEmpty()) add("aliases: ${aliases.joinToString { it.text }}")
    }.joinToString("\n")

private fun MemoryClaim.embeddingText(): String =
    buildList {
        add("claim")
        add(normalizedText)
        contextText?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("predicate: $predicate")
        predicateFamily?.takeIf { it.isNotBlank() }?.let { add("family: $it") }
        objectValue?.let { add("object: $it") }
        add("scope: ${scope.text}")
    }.joinToString("\n")

private fun MemoryNote.embeddingText(): String =
    buildList {
        add("note ${noteType.name}")
        add(title)
        add(summary)
        add("scope: ${scope.text}")
        if (keywords.isNotEmpty()) add("keywords: ${keywords.joinToString()}")
        if (tags.isNotEmpty()) add("tags: ${tags.joinToString()}")
    }.joinToString("\n")

private fun MemoryTask.embeddingText(): String =
    buildList {
        add("task ${status.name} ${priority.name}")
        add(title)
        description?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("scope: ${scope.text}")
        if (acceptanceCriteria.isNotEmpty()) add("acceptance: ${acceptanceCriteria.joinToString("; ")}")
        if (blockers.isNotEmpty()) add("blockers: ${blockers.joinToString("; ")}")
    }.joinToString("\n")

private fun MemoryProfile.embeddingText(): String =
    buildList {
        add("profile")
        add(profileText)
        if (profileJson.isNotEmpty()) add(profileJson.toString())
    }.joinToString("\n")

private fun MemoryEpisode.embeddingText(): String =
    buildList {
        add("episode")
        add("situation: $situation")
        add("action: $action")
        add("result: $result")
        add("lesson: $lesson")
        if (tags.isNotEmpty()) add("tags: ${tags.joinToString()}")
    }.joinToString("\n")

private fun String.truncateForEmbedding(maxInputTokens: Int?): String {
    if (maxInputTokens == null) return this
    val maxChars = maxInputTokens * 4
    return if (length <= maxChars) this else take(maxChars)
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
