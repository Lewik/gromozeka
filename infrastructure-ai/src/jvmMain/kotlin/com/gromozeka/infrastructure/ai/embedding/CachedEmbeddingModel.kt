package com.gromozeka.infrastructure.ai.embedding

import com.gromozeka.infrastructure.db.persistence.EmbeddingCacheService
import klog.KLoggers
import org.springframework.ai.embedding.EmbeddingModel

class CachedEmbeddingModel(
    private val delegate: EmbeddingModel,
    private val cacheService: EmbeddingCacheService
) : EmbeddingModel by delegate {

    private val log = KLoggers.logger(this)

    override fun embed(text: String): FloatArray {
        val modelName = getModelName()

        val cached = cacheService.getCachedEmbedding(text, modelName)
        if (cached != null) {
            log.info { "Embedding cache HIT for model=$modelName, text length=${text.length}" }
            return cached
        }

        log.info { "Embedding cache MISS for model=$modelName, text length=${text.length}" }
        val embedding = delegate.embed(text)

        try {
            cacheService.cacheEmbedding(text, modelName, embedding)
        } catch (e: Exception) {
            log.warn(e) { "Failed to cache embedding for model=$modelName" }
        }

        return embedding
    }

    private fun getModelName(): String {
        return try {
            delegate::class.simpleName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
