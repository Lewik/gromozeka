package com.gromozeka.e2e

import com.gromozeka.domain.model.Conversation
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
                response.response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let(responseSizes::add)
                gate?.await()
            }
            response
        }
    }
}

internal fun seedHistory(database: PostgresTestDatabase, conversation: Conversation, messages: List<Conversation.Message>) {
    DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("INSERT INTO messages(id, conversation_id, role, created_at, message_json, search_text) VALUES (?, ?, ?, ?, ?, ?)").use { insert ->
            messages.forEach { message ->
                insert.setString(1, message.id.value)
                insert.setString(2, conversation.id.value)
                insert.setString(3, message.role.name)
                insert.setTimestamp(4, Timestamp.from(java.time.Instant.parse(message.createdAt.toString())))
                insert.setString(5, Json.encodeToString(message))
                insert.setString(6, message.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>().joinToString(" ") { it.text })
                insert.addBatch()
            }
            insert.executeBatch()
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
