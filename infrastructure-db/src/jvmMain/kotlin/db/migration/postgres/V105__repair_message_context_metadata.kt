package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextMetadata
import com.gromozeka.infrastructure.db.persistence.contextMetadataJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V105__repair_message_context_metadata : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Message context metadata repair requires a non-transactional Flyway connection" }
        val json = Json { ignoreUnknownKeys = true }
        var cursor = ""
        while (true) {
            val page = readPage(connection, cursor)
            if (page.isEmpty()) break
            val updates = page.mapNotNull { row ->
                val message = json.decodeFromString<Conversation.Message>(row.messageJson)
                check(message.id.value == row.id) { "Message ID mismatch during context metadata repair" }
                val expected = MessageContextMetadata.from(message)
                val stored = json.decodeFromString<MessageContextMetadata>(row.contextMetadataJson)
                if (stored == expected) null else MetadataUpdate(
                    id = row.id,
                    messageJson = row.messageJson,
                    previousMetadataJson = row.contextMetadataJson,
                    repairedMetadataJson = message.contextMetadataJson(json),
                )
            }
            writeUpdates(connection, updates)
            cursor = page.last().id
        }
    }

    private fun readPage(connection: Connection, cursor: String): List<StoredMessage> {
        val candidates = connection.prepareStatement(
            """
            SELECT id, octet_length(message_json)
            FROM messages
            WHERE id > ?
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setInt(2, MAX_PAGE_ROWS)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(SizedId(rows.getString(1), rows.getLong(2)))
                }
            }
        }.byteBoundedPrefix()
        if (candidates.isEmpty()) return emptyList()
        val messages = connection.prepareStatement(
            """
            SELECT id, message_json, context_metadata_json
            FROM messages
            WHERE id > ? AND id <= ?
            ORDER BY id
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setString(2, candidates.last().id)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(StoredMessage(rows.getString(1), rows.getString(2), rows.getString(3)))
                }
            }
        }
        check(messages.map { it.id } == candidates.map { it.id }) {
            "Conversation history changed while reading context metadata repair page"
        }
        return messages
    }

    private fun writeUpdates(connection: Connection, updates: List<MetadataUpdate>) {
        if (updates.isEmpty()) return
        connection.autoCommit = false
        try {
            val counts = connection.prepareStatement(
                """
                UPDATE messages
                SET context_metadata_json = ?
                WHERE id = ? AND message_json = ? AND context_metadata_json = ?
                """.trimIndent(),
            ).use { update ->
                updates.forEach { item ->
                    update.setString(1, item.repairedMetadataJson)
                    update.setString(2, item.id)
                    update.setString(3, item.messageJson)
                    update.setString(4, item.previousMetadataJson)
                    update.addBatch()
                }
                update.executeBatch()
            }
            check(counts.size == updates.size && counts.all { it == 1 }) {
                "Conversation history changed while repairing context metadata"
            }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun List<SizedId>.byteBoundedPrefix(): List<SizedId> {
        val selected = mutableListOf<SizedId>()
        var bytes = 0L
        for (candidate in this) {
            check(candidate.bytes >= 0L) { "Conversation message has a negative encoded size" }
            if (selected.isNotEmpty() && bytes + candidate.bytes > MAX_PAGE_BYTES) break
            selected += candidate
            bytes += candidate.bytes
        }
        return selected
    }

    private data class StoredMessage(val id: String, val messageJson: String, val contextMetadataJson: String)

    private data class MetadataUpdate(
        val id: String,
        val messageJson: String,
        val previousMetadataJson: String,
        val repairedMetadataJson: String,
    )

    private data class SizedId(val id: String, val bytes: Long)

    private companion object {
        const val MAX_PAGE_ROWS = 1_000
        const val MAX_PAGE_BYTES = 4L * 1024 * 1024
    }
}
