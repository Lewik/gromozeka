package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.db.persistence.EmbeddingCacheService
import com.openai.models.embeddings.EmbeddingCreateParams
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class OpenAiSdkEmbeddingProvider(
    private val clientFactory: OpenAiSdkClientFactory,
    private val settingsProvider: SettingsProvider,
    private val embeddingCacheService: EmbeddingCacheService,
) : AiEmbeddingProvider {
    private val log = KLoggers.logger(this)

    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
        val runtime = settingsProvider.resolveAiRuntime(request.selection)
        require(runtime.connection.kind == AiConnection.Kind.OPENAI_API || runtime.connection.kind == AiConnection.Kind.OPENAI_COMPATIBLE) {
            "OpenAI embedding provider supports only OpenAI-compatible connections, got ${runtime.connection.kind}"
        }
        val spec = settingsProvider.userProfile.aiSettings.modelSpecFor(runtime.modelConfiguration)
            ?: error("AI embedding model spec not found: ${runtime.modelConfiguration.providerModelId}")
        require(AiModelCapability.EMBEDDINGS in spec.capabilities) {
            "AI model ${runtime.modelConfiguration.providerModelId} does not support embeddings"
        }
        val dimensions = spec.limits.embeddings?.dimensions
            ?: error("AI embedding model ${runtime.modelConfiguration.providerModelId} must declare dimensions")

        val cachedVectors = mutableMapOf<Int, List<Float>>()
        val missingInputs = mutableListOf<String>()
        val missingIndices = mutableListOf<Int>()
        request.inputs.forEachIndexed { index, text ->
            val cached = embeddingCacheService.getCachedEmbedding(text, runtime.modelConfiguration.providerModelId, dimensions)
                ?.toList()
                ?.takeIf { it.size == dimensions }
            if (cached != null) {
                cachedVectors[index] = cached
            } else {
                missingInputs += text
                missingIndices += index
            }
        }

        var promptTokens = 0
        if (missingInputs.isNotEmpty()) {
            val client = clientFactory.createClient(runtime.connection)
            val params = EmbeddingCreateParams.builder()
                .model(runtime.modelConfiguration.providerModelId)
                .inputOfArrayOfStrings(missingInputs)
                .dimensions(dimensions.toLong())
                .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
                .build()
            log.info {
                "Calling OpenAI embedding runtime: connectionKind=${runtime.connection.kind} " +
                    "model=${runtime.modelConfiguration.providerModelId} inputs=${missingInputs.size} dimensions=$dimensions"
            }
            val response = withContext(Dispatchers.IO) {
                client.embeddings().create(params)
            }
            promptTokens = response.usage().promptTokens().toInt()
            response.data().forEach { embedding ->
                val missingIndex = embedding.index().toInt()
                val originalIndex = missingIndices.getOrNull(missingIndex)
                    ?: error("AI embedding response index $missingIndex is outside request batch size ${missingIndices.size}")
                val vector = embedding.embedding()
                require(vector.size == dimensions) {
                    "AI embedding model ${runtime.modelConfiguration.providerModelId} returned ${vector.size} dimensions, expected $dimensions"
                }
                cachedVectors[originalIndex] = vector
                embeddingCacheService.cacheEmbedding(
                    text = request.inputs[originalIndex],
                    model = runtime.modelConfiguration.providerModelId,
                    dimensions = dimensions,
                    embedding = vector.toFloatArray(),
                )
            }
            require(cachedVectors.size == request.inputs.size) {
                "AI embedding response did not return vectors for all inputs: ${cachedVectors.size}/${request.inputs.size}"
            }
        }

        return AiEmbeddingResponse(
            modelId = runtime.modelConfiguration.providerModelId,
            dimensions = dimensions,
            vectors = request.inputs.indices.map { index ->
                AiEmbeddingVector(
                    index = index,
                    values = cachedVectors[index]
                        ?: error("AI embedding vector missing for input index $index"),
                )
            },
            promptTokens = promptTokens,
        )
    }
}
