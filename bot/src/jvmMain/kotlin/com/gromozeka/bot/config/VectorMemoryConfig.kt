package com.gromozeka.bot.config

import com.gromozeka.bot.services.SettingsService
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import klog.KLoggers
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class VectorMemoryConfig {

    private val log = KLoggers.logger(this)

    @Bean
    fun qdrantClient(settingsService: SettingsService): QdrantClient? {
        if (!settingsService.settings.vectorStorageEnabled) {
            log.info("Vector storage disabled, skipping Qdrant client creation")
            return null
        }

        return try {
            QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
            ).also {
                log.info("Qdrant client connected to localhost:6334")
            }
        } catch (e: Exception) {
            log.warn("Failed to create Qdrant client: ${e.message}. Vector storage will be unavailable.")
            null
        }
    }

    @Bean
    fun qdrantVectorStore(
        qdrantClient: QdrantClient?,
        embeddingModel: EmbeddingModel,
        settingsService: SettingsService
    ): VectorStore? {
        if (qdrantClient == null || !settingsService.settings.vectorStorageEnabled) {
            log.info("Qdrant client not available or vector storage disabled, VectorStore will not be created")
            return null
        }

        return try {
            QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName("vector_store")
                .initializeSchema(true)
                .build().also {
                    log.info("QdrantVectorStore initialized: collection='vector_store', dimensions=${embeddingModel.dimensions()}")
                }
        } catch (e: Exception) {
            log.warn("Failed to create QdrantVectorStore: ${e.message}. Vector storage will be unavailable.")
            null
        }
    }
}
