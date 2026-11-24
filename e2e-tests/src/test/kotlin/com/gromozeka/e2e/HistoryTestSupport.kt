package com.gromozeka.e2e

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.ToolSequenceFacts
import com.gromozeka.presentation.AppComponents
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.call.save
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal suspend fun AppComponents.loadRecentHistoryMessages(
    conversationId: Conversation.Id,
    limit: Int = 100,
): List<Conversation.Message> = conversationHistoryService
    .loadPage(conversationId, ConversationHistoryPageRequest(limit = limit))
    .messages
    .map { it.message }

internal class HistoryNetworkProbe(private val client: HttpClient) {
    val requests = AtomicInteger()
    val responseSizes = CopyOnWriteArrayList<Long>()
    val urls = CopyOnWriteArrayList<String>()
    @Volatile var gate: CompletableDeferred<Unit>? = null

    init {
        client.plugin(HttpSend).intercept { request ->
            val history = request.url.encodedPath.endsWith("/history")
            if (history) {
                requests.incrementAndGet()
                urls += request.url.buildString()
            }
            val response = execute(request)
            if (history) {
                // Content-Length is optional (e.g. Servlet chunked JSON). Measure actual
                // payload bytes and return a saved call so the real client can still decode it.
                val saved = response.save()
                responseSizes += saved.response.bodyAsBytes().size.toLong()
                gate?.await()
                saved
            } else response
        }
    }
}

internal fun seedHistory(database: PostgresTestDatabase, conversation: Conversation, messages: List<Conversation.Message>) {
    DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
        connection.autoCommit = false
        val arrays = mutableListOf<java.sql.Array>()
        try {
            connection.prepareStatement("""
                INSERT INTO messages(
                    id, conversation_id, role, created_at, message_json, search_text, compaction_header,
                    tool_call_ids, tool_result_ids, provider_managed_tool, tool_results_only, full_compaction_anchor
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { insert ->
                messages.forEach { message ->
                    val facts = ToolSequenceFacts.from(message)
                    val calls = connection.createArrayOf("text", facts.callIds.toTypedArray()).also(arrays::add)
                    val results = connection.createArrayOf("text", facts.resultIds.toTypedArray()).also(arrays::add)
                    insert.setString(1, message.id.value)
                    insert.setString(2, conversation.id.value)
                    insert.setString(3, message.role.name)
                    insert.setTimestamp(4, Timestamp.from(java.time.Instant.parse(message.createdAt.toString())))
                    insert.setString(5, Json.encodeToString(message))
                    insert.setString(6, message.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>().joinToString(" ") { it.text })
                    insert.setString(7, Json.encodeToString(CompactionMessageHeader.from(message)))
                    insert.setArray(8, calls)
                    insert.setArray(9, results)
                    insert.setBoolean(10, facts.providerManaged)
                    insert.setBoolean(11, facts.toolResultsOnly)
                    insert.setBoolean(12, facts.fullCompactionAnchor)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        } finally {
            arrays.forEach { runCatching { it.free() } }
        }
        connection.prepareStatement("INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)").use { insert ->
            messages.forEachIndexed { index, message ->
                insert.setString(1, conversation.currentThread.value)
                insert.setString(2, message.id.value)
                insert.setInt(3, index)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        connection.commit()
    }
}
