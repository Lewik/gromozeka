package db.migration.postgres

import com.gromozeka.domain.model.safeToolOutputText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V61__search_tool_results : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.prepareStatement("SELECT id, message_json FROM messages WHERE message_json LIKE '%toolUseId%'").use { select ->
            select.fetchSize = 128
            context.connection.prepareStatement("UPDATE messages SET search_text = concat_ws(E'\n', nullif(search_text, ''), ?) WHERE id = ?").use { update ->
                select.executeQuery().use { rows ->
                    while (rows.next()) {
                        val message = Json.parseToJsonElement(rows.getString(2)) as? JsonObject ?: continue
                        val content = message["content"] as? JsonArray ?: continue
                        val text = content.mapNotNull { it as? JsonObject }
                            .filter { it.containsKey("toolUseId") }
                            .flatMap { (it["result"] as? JsonArray).orEmpty() }
                            .mapNotNull { ((it as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content }
                            .joinToString("\n").safeToolOutputText()
                        if (text.isEmpty()) continue
                        update.setString(1, text)
                        update.setString(2, rows.getString(1))
                        update.executeUpdate()
                    }
                }
            }
        }
    }
}
