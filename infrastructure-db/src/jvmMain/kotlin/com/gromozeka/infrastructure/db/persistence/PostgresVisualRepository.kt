package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.visual.*
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
class PostgresVisualRepository(private val dataSource: DataSource) : VisualRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try { block(connection).also { connection.commit() } }
            catch (error: Throwable) { connection.rollback(); throw error }
        }
    }

    private fun Connection.rows(sql: String, vararg args: Any?): List<String> = prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    private fun Connection.execute(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    override suspend fun list(conversationId: Conversation.Id): List<Visual> = transaction { c ->
        c.rows("SELECT record_json::text FROM conversation_visuals WHERE conversation_id = ? ORDER BY created_at, id", conversationId.value)
            .map { json.decodeFromString<Visual>(it) }
    }

    override suspend fun find(conversationId: Conversation.Id, visualId: String): Visual? = transaction { c ->
        c.rows("SELECT record_json::text FROM conversation_visuals WHERE id = ? AND conversation_id = ?", visualId, conversationId.value)
            .singleOrNull()?.let { json.decodeFromString<Visual>(it) }
    }

    override suspend fun save(visual: Visual) { transaction { it.write(visual) } }

    private fun Connection.write(visual: Visual) {
        val changed = if (visual.revision == 1L) {
            execute("INSERT INTO conversation_visuals(id, conversation_id, revision, has_handler, status, created_at, record_json) VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb) ON CONFLICT (id) DO NOTHING",
                visual.id, visual.conversationId.value, visual.revision, visual.handler != null, visual.status.name, visual.createdAt.toString(), json.encodeToString(visual))
        } else {
            execute("UPDATE conversation_visuals SET revision = ?, has_handler = ?, status = ?, record_json = ?::jsonb WHERE id = ? AND conversation_id = ? AND revision = ?",
                visual.revision, visual.handler != null, visual.status.name, json.encodeToString(visual), visual.id, visual.conversationId.value, visual.revision - 1)
        }
        check(changed == 1) { "Visual was changed or closed concurrently" }
    }

    override suspend fun delete(conversationId: Conversation.Id, visualId: String): Boolean = transaction { c ->
        c.execute("DELETE FROM conversation_visuals WHERE id = ? AND conversation_id = ?", visualId, conversationId.value) == 1
    }

    override suspend fun withHandlers(): List<Visual> = transaction { c ->
        c.rows("SELECT record_json::text FROM conversation_visuals WHERE has_handler AND status <> 'STOPPED'")
            .map { json.decodeFromString<Visual>(it) }
    }

    override suspend fun findAction(eventId: String): VisualActionReceipt? = transaction { c ->
        c.rows("SELECT record_json::text FROM visual_action_receipts WHERE event_id = ?", eventId)
            .singleOrNull()?.let { json.decodeFromString<VisualActionReceipt>(it) }
    }

    override suspend fun acceptAction(visual: Visual, receipt: VisualActionReceipt): Boolean = transaction { c ->
        val inserted = c.execute("INSERT INTO visual_action_receipts(event_id, visual_id, record_json) VALUES (?, ?, ?::jsonb) ON CONFLICT (event_id) DO NOTHING",
            receipt.eventId, visual.id, json.encodeToString(receipt))
        if (inserted == 0) false else { c.write(visual); true }
    }

    override suspend fun finishAction(eventId: String, result: VisualActionResult) { transaction { c ->
        val old = c.rows("SELECT record_json::text FROM visual_action_receipts WHERE event_id = ? FOR UPDATE", eventId)
            .singleOrNull()?.let { json.decodeFromString<VisualActionReceipt>(it) }
        if (old != null && old.result == null) c.execute("UPDATE visual_action_receipts SET record_json = ?::jsonb WHERE event_id = ?",
            json.encodeToString(old.copy(result = result)), eventId)
    } }
}
