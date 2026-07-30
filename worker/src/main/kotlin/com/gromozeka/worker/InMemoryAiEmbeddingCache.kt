package com.gromozeka.worker

import com.gromozeka.domain.service.AiEmbeddingCache
import com.gromozeka.shared.utils.sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service

@Service
class InMemoryAiEmbeddingCache : AiEmbeddingCache {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, FloatArray>(MAX_ENTRIES, 0.75f, true)

    override suspend fun find(
        text: String,
        modelId: String,
        dimensions: Int,
    ): FloatArray? = mutex.withLock {
        entries[Key(text.sha256(), modelId, dimensions)]?.copyOf()
    }

    override suspend fun store(
        text: String,
        modelId: String,
        dimensions: Int,
        embedding: FloatArray,
    ) {
        require(embedding.size == dimensions) {
            "Embedding cache value has ${embedding.size} dimensions, expected $dimensions"
        }
        mutex.withLock {
            entries[Key(text.sha256(), modelId, dimensions)] = embedding.copyOf()
            while (entries.size > MAX_ENTRIES) {
                entries.remove(entries.entries.first().key)
            }
        }
    }

    private data class Key(
        val textHash: String,
        val modelId: String,
        val dimensions: Int,
    )

    private companion object {
        const val MAX_ENTRIES = 2_048
    }
}
