package db.migration.postgres

import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** One-time format conversion. Runtime code never infers coverage from origin. */
class V63__explicit_compaction_coverage : BaseJavaMigration() {
    override fun migrate(context: Context) {
        migrateColumn(context.connection, "messages", "id", "message_json", jsonb = false)
        // Replayed events and queued history mutations also contain typed message content.
        migrateColumn(context.connection, "conversation_runtime_records", "conversation_id", "record_json", jsonb = true)
    }

    private fun migrateColumn(connection: Connection, table: String, id: String, column: String, jsonb: Boolean) {
        connection.prepareStatement("SELECT $id, $column FROM $table WHERE $column::text LIKE '%ContextCompactionResult%'").use { select ->
            select.fetchSize = 128
            val value = if (jsonb) "CAST(? AS jsonb)" else "?"
            connection.prepareStatement("UPDATE $table SET $column = $value WHERE $id = ?").use { update ->
                select.executeQuery().use { rows ->
                    while (rows.next()) {
                        val original = Json.parseToJsonElement(rows.getString(2))
                        val migrated = migrateDocument(original)
                        if (migrated == original) continue
                        update.setString(1, migrated.toString())
                        update.setString(2, rows.getString(1))
                        update.executeUpdate()
                    }
                }
            }
        }
    }

    internal fun migrateDocument(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::migrateDocument))
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            when {
                // These are untyped/audit/provider data, not canonical messages.
                key in opaqueFields -> child
                key == "content" || key == "newContent" ->
                    if (child is JsonArray) JsonArray(child.map(::migrateContent)) else child
                else -> migrateDocument(child)
            }
        })
        else -> value
    }

    private fun migrateContent(value: JsonElement): JsonElement {
        val item = value as? JsonObject ?: return value
        if (item["type"] != JsonPrimitive("ContextCompactionResult")) return value
        val existing = item["coverage"]
        if (existing != null && existing != JsonNull) {
            require(existing == JsonPrimitive("SELECTED_MESSAGES") || existing == JsonPrimitive("ALL_PREVIOUS")) {
                "Cannot migrate compaction with unrecognized coverage"
            }
            return item
        }
        val coverage = when (item["origin"]) {
            JsonPrimitive("USER_REQUESTED") -> "SELECTED_MESSAGES"
            JsonPrimitive("PROVIDER_AUTO"), JsonPrimitive("GROMOZEKA_POLICY"), JsonPrimitive("RUNTIME_MIGRATION") -> "ALL_PREVIOUS"
            else -> error("Cannot migrate compaction with missing or unrecognized origin")
        }
        // Do not descend into payload: native checkpoints must remain byte-for-byte equivalent JSON values.
        return JsonObject(item + ("coverage" to JsonPrimitive(coverage)))
    }

    private val opaqueFields = setOf(
        "providerMetadata", "input", "output", "result", "raw", "trace",
        "toolExecutions", "memoryOperations", "commandTasks", "commandMonitors", "commandMonitorEvents",
    )
}
