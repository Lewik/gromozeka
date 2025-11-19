package com.gromozeka.infrastructure.db.vector

import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service

@Service
@ConditionalOnBean(VectorStore::class)
class QdrantVectorStore(
    private val vectorStore: VectorStore
) {

    suspend fun add(documents: List<Document>) {
        vectorStore.add(documents)
    }

    suspend fun search(
        query: String, 
        topK: Int = 10,
        filterExpression: String? = null
    ): List<Document> {
        val searchRequest = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .apply {
                filterExpression?.let { filterExpression(it) }
            }
            .build()
        return vectorStore.similaritySearch(searchRequest)
    }

    suspend fun delete(ids: List<String>) {
        vectorStore.delete(ids)
    }
}
