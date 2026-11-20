package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V91__compaction_headers : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use { it.execute("ALTER TABLE messages ADD COLUMN compaction_header text") }
        val json = Json { ignoreUnknownKeys = true }
        connection.prepareStatement("SELECT id, message_json FROM messages").use { select ->
            select.fetchSize = 8
            connection.prepareStatement("UPDATE messages SET compaction_header = ? WHERE id = ?").use { update ->
                select.executeQuery().use { rows ->
                    var count = 0
                    while (rows.next()) {
                        val message = json.decodeFromString<Conversation.Message>(rows.getString(2))
                        check(message.id.value == rows.getString(1)) { "Message ID mismatch during compaction header migration" }
                        update.setString(1, Json.encodeToString(CompactionMessageHeader.from(message)))
                        update.setString(2, message.id.value)
                        update.addBatch()
                        if (++count % 128 == 0) update.executeBatch()
                    }
                    if (count % 128 != 0) update.executeBatch()
                }
            }
        }
    }
}
