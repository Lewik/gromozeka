package com.gromozeka.infrastructure.db.memory.graph

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import klog.KLoggers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

@Service
class RerankService(
    private val httpClient: HttpClient,
    private val rerankerProcessManager: RerankerProcessManager?
) {
    private val log = KLoggers.logger(this)

    val isAvailable: Boolean
        get() = rerankerProcessManager?.isAvailable ?: false

    suspend fun rerank(
        query: String,
        documents: List<String>,
        topN: Int? = null
    ): List<RerankResult> {
        if (documents.isEmpty()) {
            return emptyList()
        }

        if (!isAvailable) {
            log.debug { "Reranker not available, returning documents in original order" }
            return documents.indices.map { idx ->
                RerankResult(index = idx, relevanceScore = 1.0f - (idx * 0.01f))
            }
        }

        log.debug { "Reranking ${documents.size} documents for query: '${query.take(50)}...'" }

        return try {
            val response: RerankResponse
            val timeMs = measureTimeMillis {
                response = httpClient.post("http://localhost:7997/rerank") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RerankRequest(
                            query = query,
                            documents = documents,
                            returnDocuments = false,
                            topN = topN
                        )
                    )
                }.body<RerankResponse>()
            }

            log.info { "Reranking completed in ${timeMs}ms for ${documents.size} docs, ${response.usage?.totalTokens ?: 0} tokens" }
            response.results.sortedByDescending { it.relevanceScore }
        } catch (e: Exception) {
            log.warn(e) { "Reranking failed, returning documents in original order: ${e.message}" }
            documents.indices.map { idx ->
                RerankResult(index = idx, relevanceScore = 1.0f - (idx * 0.01f))
            }
        }
    }

    suspend fun rerankAndFilter(
        query: String,
        documents: List<String>,
        topN: Int,
        minScore: Float = 0.0f
    ): List<Int> {
        val results = rerank(query, documents, topN)
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
    @SerialName("top_n")
    val topN: Int? = null
)

@Serializable
data class RerankResponse(
    val results: List<RerankResult>,
    val usage: RerankUsage? = null,
    val model: String? = null,
    val id: String? = null,
    val created: Long? = null,
    @SerialName("object")
    val objectType: String? = null
)

@Serializable
data class RerankResult(
    val index: Int,
    @SerialName("relevance_score")
    val relevanceScore: Float,
    val document: String? = null
)

@Serializable
data class RerankUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)
