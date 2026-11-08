package db.migration.postgres

import com.gromozeka.domain.model.safeToolOutputText
import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V82__visible_message_search : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use {
            it.execute("ALTER TABLE messages ADD COLUMN message_text TEXT NOT NULL DEFAULT ''")
        }
        // Kotlin can read escaped NUL and other valid JSON strings that PostgreSQL JSON extraction rejects.
        context.connection.prepareStatement("SELECT id, message_json FROM messages").use { select ->
            select.fetchSize = 128
            context.connection.prepareStatement("UPDATE messages SET message_text = ? WHERE id = ?").use { update ->
                select.executeQuery().use { rows ->
                    var pending = 0
                    while (rows.next()) {
                        val content = Json.parseToJsonElement(rows.getString(2)).jsonObject.getValue("content").jsonArray
                        val text = content.mapNotNull { element ->
                            val item = element.jsonObject
                            when (item["type"]?.jsonPrimitive?.content) {
                                "Message" -> item.getValue("text").jsonPrimitive.content
                                "IntermediateMessage" -> item.getValue("structured").jsonObject["fullText"]?.jsonPrimitive?.content
                                "visual_interaction" -> "[Visual button action] ${item.getValue("visualTitle").jsonPrimitive.content} → ${item.getValue("buttonLabel").jsonPrimitive.content}"
                                else -> null
                            }
                        }.joinToString("\n").safeToolOutputText().trim()
                        update.setString(1, text)
                        update.setString(2, rows.getString(1))
                        update.addBatch()
                        if (++pending == 128) {
                            update.executeBatch()
                            pending = 0
                        }
                    }
                    if (pending != 0) update.executeBatch()
                }
            }
        }
        context.connection.createStatement().use {
            it.execute("CREATE INDEX idx_messages_message_text_trgm ON messages USING GIN(message_text gin_trgm_ops)")
        }
    }
}
