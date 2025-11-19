package com.gromozeka.infrastructure.db.memory.graph

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class RerankService(
    private val httpClient: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rerank(
        query: String,
        documents: List<String>,
        topK: Int? = null
    ): List<RerankResult> {
        if (documents.isEmpty()) {
            return emptyList()
        }

        val response = try {
            httpClient.post("http://localhost:7997/rerank") {
                contentType(ContentType.Application.Json)
                setBody(
                    RerankRequest(
                        query = query,
                        documents = documents,
                        returnDocuments = false,
                        topK = topK
                    )
                )
            }.body<RerankResponse>()
        } catch (e: Exception) {
            return documents.indices.map { idx ->
                RerankResult(index = idx, relevanceScore = 0f)
            }
        }

        return response.results.sortedByDescending { it.relevanceScore }
    }

    suspend fun rerankAndFilter(
        query: String,
        documents: List<String>,
        topK: Int,
        minScore: Float = 0.0f
    ): List<Int> {
        val results = rerank(query, documents, topK)
        return results
            .filter { it.relevanceScore >= minScore }
            .map { it.index }
    }
}

@Serializable
data class RerankRequest(
    val query: String,
    val documents: List<String>,
    @SerialName("return_documents")
    val returnDocuments: Boolean = false,
    @SerialName("top_k")
    val topK: Int? = null
)

@Serializable
data class RerankResponse(
    val results: List<RerankResult>,
    val usage: RerankUsage? = null
)

@Serializable
data class RerankResult(
    val index: Int,
    @SerialName("relevance_score")
    val relevanceScore: Float
)

@Serializable
data class RerankUsage(
    @SerialName("total_tokens")
    val totalTokens: Int
)
