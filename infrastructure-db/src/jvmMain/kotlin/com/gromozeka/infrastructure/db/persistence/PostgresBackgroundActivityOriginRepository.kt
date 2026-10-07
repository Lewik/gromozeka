package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

@Service
@DependsOn("postgresFlyway")
class PostgresBackgroundActivityOriginRepository(private val dataSource: DataSource) : BackgroundActivityOriginRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try { block(connection).also { connection.commit() } }
            catch (error: Throwable) { connection.rollback(); throw error }
        }
    }

    override suspend fun bind(origin: BackgroundActivityOrigin) {
        transaction { connection ->
            connection.prepareStatement("""
                INSERT INTO background_activity_origins
                    (kind, activity_id, conversation_id, actor_user_id, worker_request_id, tool_call_id, record_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
            """.trimIndent()).use { statement ->
                listOf(origin.kind.name, origin.activityId, origin.conversationId.value, origin.actorUserId.value,
                    origin.workerRequestId, origin.toolCallId, json.encodeToString(origin)).forEachIndexed { index, value ->
                    statement.setString(index + 1, value)
                }
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT record_json::text FROM background_activity_origins WHERE kind = ? AND activity_id = ?").use { statement ->
                statement.setString(1, origin.kind.name)
                statement.setString(2, origin.activityId)
                statement.executeQuery().use { rows ->
                    check(rows.next() && json.decodeFromString<BackgroundActivityOrigin>(rows.getString(1)) == origin) {
                        "Background activity origin cannot be reassigned"
                    }
                }
            }
        }
    }

    override suspend fun find(
        conversationId: Conversation.Id,
        keys: Set<BackgroundActivityOrigin.Key>,
    ): Map<BackgroundActivityOrigin.Key, BackgroundActivityOrigin> {
        if (keys.isEmpty()) return emptyMap()
        return transaction { connection ->
            val parameters = keys.joinToString(",") { "(?, ?)" }
            connection.prepareStatement("SELECT record_json::text FROM background_activity_origins WHERE conversation_id = ? AND (kind, activity_id) IN ($parameters)").use { statement ->
                statement.setString(1, conversationId.value)
                keys.forEachIndexed { index, key ->
                    statement.setString(2 + index * 2, key.kind.name)
                    statement.setString(3 + index * 2, key.activityId)
                }
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            val origin = json.decodeFromString<BackgroundActivityOrigin>(rows.getString(1))
                            put(origin.key, origin)
                        }
                    }
                }
            }
        }
    }
}
