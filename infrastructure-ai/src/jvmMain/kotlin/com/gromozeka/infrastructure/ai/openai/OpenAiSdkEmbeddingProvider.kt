package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.resolveEmbeddingDimensions
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingCache
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.DirectAiEmbeddingProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.openai.models.embeddings.EmbeddingCreateParams
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class OpenAiSdkEmbeddingProvider(
    private val clientFactory: OpenAiSdkClientFactory,
    private val embeddingCache: AiEmbeddingCache,
) : DirectAiEmbeddingProvider {
    private val log = KLoggers.logger(this)

    override suspend fun embed(
        runtime: ResolvedAiRuntime,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse {
        val modelSpec = runtime.modelSpec
        require(runtime.connection.kind == AiConnection.Kind.OPENAI_API || runtime.connection.kind == AiConnection.Kind.OPENAI_COMPATIBLE) {
            "OpenAI embedding provider supports only OpenAI-compatible connections, got ${runtime.connection.kind}"
        }
        require(AiModelCapability.EMBEDDINGS in modelSpec.capabilities) {
            "AI model ${runtime.modelConfiguration.providerModelId} does not support embeddings"
        }
        require(modelSpec.id == runtime.modelConfiguration.providerModelId) {
            "AI embedding model spec ${modelSpec.id} does not match ${runtime.modelConfiguration.providerModelId}"
        }
        val dimensions = runtime.modelConfiguration.resolveEmbeddingDimensions(modelSpec)
        val requestedDimensions = runtime.modelConfiguration.requestedEmbeddingDimensions

        val cachedVectors = mutableMapOf<Int, List<Float>>()
        val missingInputs = mutableListOf<String>()
        val missingIndices = mutableListOf<Int>()
        request.inputs.forEachIndexed { index, text ->
            val cached = embeddingCache.find(text, runtime.modelConfiguration.providerModelId, dimensions)
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
            val params = embeddingCreateParams(
                modelId = runtime.modelConfiguration.providerModelId,
                inputs = missingInputs,
                requestedDimensions = requestedDimensions,
            )
            log.info {
                "Calling OpenAI embedding runtime: connectionKind=${runtime.connection.kind} " +
                    "model=${runtime.modelConfiguration.providerModelId} inputs=${missingInputs.size} " +
                    "dimensions=$dimensions requestedDimensions=${requestedDimensions ?: "provider-default"}"
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
                embeddingCache.store(
                    text = request.inputs[originalIndex],
                    modelId = runtime.modelConfiguration.providerModelId,
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

internal fun embeddingCreateParams(
    modelId: String,
    inputs: List<String>,
    requestedDimensions: Int?,
): EmbeddingCreateParams =
    EmbeddingCreateParams.builder()
        .model(modelId)
        .inputOfArrayOfStrings(inputs)
        .apply {
            requestedDimensions?.let { dimensions(it.toLong()) }
        }
        .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
        .build()
