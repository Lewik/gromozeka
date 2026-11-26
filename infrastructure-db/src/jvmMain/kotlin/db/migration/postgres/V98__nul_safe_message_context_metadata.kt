package db.migration.postgres

import com.gromozeka.domain.model.Conversation
import com.gromozeka.infrastructure.db.persistence.contextMetadataJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V98__nul_safe_message_context_metadata : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use {
            it.execute("ALTER TABLE messages ALTER COLUMN context_metadata_json DROP EXPRESSION")
        }

        val json = Json { ignoreUnknownKeys = true }
        var cursor = ""
        while (true) {
            val rows = readMissingMetadata(connection, cursor)
            if (rows.isEmpty()) break
            connection.prepareStatement(
                """
                UPDATE messages
                SET context_metadata_json = ?
                WHERE id = ? AND message_json = ? AND context_metadata_json IS NULL
                """.trimIndent(),
            ).use { update ->
                rows.forEach { row ->
                    val message = json.decodeFromString<Conversation.Message>(row.messageJson)
                    check(message.id.value == row.id) { "Message ID mismatch during context metadata migration" }
                    update.setString(1, message.contextMetadataJson(json))
                    update.setString(2, row.id)
                    update.setString(3, row.messageJson)
                    update.addBatch()
                }
                val counts = update.executeBatch()
                check(counts.size == rows.size && counts.all { it == 1 }) {
                    "Conversation history changed during context metadata migration"
                }
            }
            cursor = rows.last().id
        }

        connection.createStatement().use {
            it.execute("ALTER TABLE messages ALTER COLUMN context_metadata_json SET NOT NULL")
            it.execute("""
                ALTER TABLE messages
                ADD CONSTRAINT messages_context_metadata_json_object
                CHECK (jsonb_typeof(context_metadata_json::jsonb) = 'object')
            """.trimIndent())
            it.execute("DROP FUNCTION gromozeka_message_context_metadata(json)")
        }
    }

    private fun readMissingMetadata(connection: Connection, cursor: String): List<MetadataRow> =
        connection.prepareStatement(
            """
            SELECT id, message_json
            FROM messages
            WHERE context_metadata_json IS NULL AND id > ?
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setInt(2, PAGE_SIZE)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(MetadataRow(rows.getString(1), rows.getString(2)))
                }
            }
        }

    private data class MetadataRow(val id: String, val messageJson: String)

    private companion object {
        const val PAGE_SIZE = 64
    }
}
