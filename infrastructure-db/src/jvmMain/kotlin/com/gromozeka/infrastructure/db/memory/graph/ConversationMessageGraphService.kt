package com.gromozeka.infrastructure.db.memory.graph

import com.gromozeka.infrastructure.db.graph.Neo4jGraphStore
import klog.KLoggers
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class ConversationMessageGraphService(
    private val neo4jGraphStore: Neo4jGraphStore
) {
    private val log = KLoggers.logger(this)

    suspend fun saveMessages(messages: List<ConversationMessageNode>) {
        log.debug { "Saving ${messages.size} conversation messages to Neo4j" }

        try {
            messages.forEach { message ->
                val query = """
                    MERGE (m:ConversationMessage {id: ${'$'}id})
                    SET m.conversationId = ${'$'}conversationId,
                        m.threadId = ${'$'}threadId,
                        m.projectId = ${'$'}projectId,
                        m.role = ${'$'}role,
                        m.content = ${'$'}content,
                        m.embedding = ${'$'}embedding,
                        m.createdAt = datetime()
                """.trimIndent()

                neo4jGraphStore.executeQuery(
                    query,
                    mapOf(
                        "id" to message.id,
                        "conversationId" to message.conversationId,
                        "threadId" to message.threadId,
                        "projectId" to message.projectId,
                        "role" to message.role,
                        "content" to message.content,
                        "embedding" to message.embedding
                    )
                )
            }
            log.info { "Successfully saved ${messages.size} conversation messages to Neo4j" }
        } catch (e: Exception) {
            log.error(e) { "Failed to save conversation messages to Neo4j: ${e.message}" }
            throw e
        }
    }

    suspend fun vectorSearch(
        queryEmbedding: List<Float>,
        threadId: String? = null,
        limit: Int = 5
    ): List<MessageSearchResult> {
        val whereClause = threadId?.let { "WHERE node.threadId = \$threadId" } ?: ""

        val query = """
            CALL db.index.vector.queryNodes('conversation_message_vector', $limit, ${'$'}queryEmbedding)
            YIELD node, score
            $whereClause
            RETURN node.id AS id,
                   node.content AS content,
                   node.threadId AS threadId,
                   score
            ORDER BY score DESC
        """.trimIndent()

        val params = mutableMapOf<String, Any>("queryEmbedding" to queryEmbedding)
        threadId?.let { params["threadId"] = it }

        val results = neo4jGraphStore.executeQuery(query, params)

        return results.map { row ->
            MessageSearchResult(
                id = row["id"] as String,
                content = row["content"] as String,
                threadId = row["threadId"] as String,
                score = (row["score"] as Number).toDouble()
            )
        }
    }

    suspend fun deleteMessage(messageId: String) {
        val query = "MATCH (m:ConversationMessage {id: ${'$'}id}) DELETE m"
        neo4jGraphStore.executeQuery(query, mapOf("id" to messageId))
        log.debug { "Deleted message $messageId from Neo4j" }
    }

    data class ConversationMessageNode(
        val id: String,
        val conversationId: String,
        val threadId: String,
        val projectId: String,
        val role: String,
        val content: String,
        val embedding: List<Float>
    )

    data class MessageSearchResult(
        val id: String,
        val content: String,
        val threadId: String,
        val score: Double
    )
}
