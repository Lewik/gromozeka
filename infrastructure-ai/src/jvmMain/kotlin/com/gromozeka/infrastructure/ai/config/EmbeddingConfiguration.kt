package com.gromozeka.infrastructure.ai.config

import com.gromozeka.infrastructure.ai.embedding.CachedEmbeddingModel
import com.gromozeka.infrastructure.db.persistence.EmbeddingCacheService
import klog.KLoggers
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
@AutoConfigureAfter(name = [
    "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration",
    "org.springframework.ai.autoconfigure.ollama.OllamaAutoConfiguration",
    "org.springframework.ai.autoconfigure.anthropic.AnthropicAutoConfiguration"
])
class EmbeddingConfiguration {

    private val log = KLoggers.logger(this)

    @Bean
    @Primary
    @ConditionalOnBean(EmbeddingModel::class)
    fun cachedEmbeddingModel(
        embeddingModelProvider: ObjectProvider<EmbeddingModel>,
        cacheService: EmbeddingCacheService
    ): EmbeddingModel {
        val delegate = embeddingModelProvider.orderedStream()
            .filter { it !is CachedEmbeddingModel }
            .findFirst()
            .orElseThrow { IllegalStateException("No EmbeddingModel bean found") }

        log.info { "Wrapping ${delegate::class.simpleName} with caching" }

        return CachedEmbeddingModel(delegate, cacheService)
    }
}
