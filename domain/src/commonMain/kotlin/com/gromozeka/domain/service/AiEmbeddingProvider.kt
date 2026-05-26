package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiRuntimeSelection

interface AiEmbeddingProvider {
    suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse
}

data class AiEmbeddingRequest(
    val selection: AiRuntimeSelection,
    val inputs: List<String>,
    val projectPath: String? = null,
) {
    init {
        require(inputs.isNotEmpty()) { "AI embedding request inputs must not be empty" }
        require(inputs.all { it.isNotBlank() }) { "AI embedding request inputs must not be blank" }
    }
}

data class AiEmbeddingResponse(
    val modelId: String,
    val dimensions: Int,
    val vectors: List<AiEmbeddingVector>,
    val promptTokens: Int? = null,
) {
    init {
        require(modelId.isNotBlank()) { "AI embedding response model id must not be blank" }
        require(dimensions > 0) { "AI embedding response dimensions must be positive" }
        require(vectors.isNotEmpty()) { "AI embedding response vectors must not be empty" }
        require(vectors.all { it.values.size == dimensions }) { "AI embedding vector dimensions must match response dimensions" }
    }
}

data class AiEmbeddingVector(
    val index: Int,
    val values: List<Float>,
) {
    init {
        require(index >= 0) { "AI embedding vector index must be non-negative" }
        require(values.isNotEmpty()) { "AI embedding vector values must not be empty" }
    }
}
