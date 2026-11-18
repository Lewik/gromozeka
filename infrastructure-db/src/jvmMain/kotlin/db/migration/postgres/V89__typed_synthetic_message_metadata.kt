package db.migration.postgres

import java.sql.Connection
import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** Converts canonical messages only; provider replay data and tool payloads remain opaque. */
class V89__typed_synthetic_message_metadata : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        val sources = mutableMapOf<OriginKey, JsonObject?>()
        connection.prepareStatement("SELECT record_json::text, tool_call_id FROM background_activity_origins WHERE kind = ? AND activity_id = ? AND conversation_id = ?").use { origin ->
            val source: SyntheticMessageSourceLookup = { kind, activityId, conversationId ->
                sources.getOrPut(OriginKey(kind, activityId, conversationId)) {
                    origin.setString(1, kind)
                    origin.setString(2, activityId)
                    origin.setString(3, conversationId)
                    origin.executeQuery().use { rows ->
                        if (!rows.next()) null else {
                            val record = Json.parseToJsonElement(rows.getString("record_json")).jsonObject
                            check(record.optionalString("kind") == kind && record.optionalString("activityId") == activityId &&
                                record.optionalString("conversationId") == conversationId) { "Background activity origin identity mismatch" }
                            val callId = record.optionalString("toolCallId")
                            check(callId == rows.getString("tool_call_id")) { "Background activity origin call mismatch" }
                            val agentId = record.optionalString("agentDefinitionId")
                            if (callId == null || agentId == null) null else buildJsonObject {
                                put("toolCallId", callId); put("agentDefinitionId", agentId)
                            }
                        }
                    }
                }
            }
            migrateColumn(connection, "messages", listOf("id"), "message_json", jsonb = false, source)
            migrateColumn(connection, "conversation_runtime_records", listOf("conversation_id"), "scheduling", jsonb = true, source)
            migrateColumn(connection, "conversation_runtime_events", listOf("conversation_id", "sequence"), "entry_json", jsonb = true, source)
            migrateColumn(connection, "agent_response_drafts", listOf("id"), "record_json", jsonb = true, source)
        }
    }

    private fun migrateColumn(
        connection: Connection, table: String, keys: List<String>, column: String, jsonb: Boolean, source: SyntheticMessageSourceLookup,
    ) {
        val selectSql = "SELECT ${keys.joinToString()}, $column FROM $table WHERE $column::text LIKE '%\"syntheticKind\"%'"
        val value = if (jsonb) "CAST(? AS jsonb)" else "?"
        val updateSql = "UPDATE $table SET $column = $value WHERE ${keys.joinToString(" AND ") { "$it = ?" }}"
        connection.prepareStatement(selectSql).use { select ->
            select.fetchSize = 128
            connection.prepareStatement(updateSql).use { update ->
                select.executeQuery().use { rows ->
                    while (rows.next()) {
                        val original = Json.parseToJsonElement(rows.getString(keys.size + 1))
                        val migrated = migrateDocument(original, source)
                        if (migrated == original) continue
                        update.setString(1, migrated.toString())
                        keys.indices.forEach { update.setObject(it + 2, rows.getObject(it + 1)) }
                        check(update.executeUpdate() == 1) { "Synthetic message migration lost its target row" }
                    }
                }
            }
        }
    }

    internal fun migrateDocument(value: JsonElement, source: SyntheticMessageSourceLookup = { _, _, _ -> null }): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { migrateDocument(it, source) })
        is JsonObject -> if (value.isCanonicalMessage()) migrateMessage(value, source) else JsonObject(value.mapValues { (key, child) ->
            if (key in opaqueFields) child else migrateDocument(child, source)
        })
        else -> value
    }

    private fun migrateMessage(message: JsonObject, source: SyntheticMessageSourceLookup): JsonObject {
        val raw = message["providerMetadata"] ?: return message
        val metadata = raw as? JsonObject ?: error("Message provider metadata must be an object")
        val rawKind = metadata["syntheticKind"] ?: return message
        val kind = rawKind.stringValue()
        val canonical = kindNames[kind] ?: kind.takeIf { it in kindNames.values }
            ?: error("Unknown persisted synthetic message kind")
        val migrated = metadata.toMutableMap()
        migrated["syntheticKind"] = JsonPrimitive(canonical)
        val originKind = when (canonical) {
            "COMMAND_TASK_COMPLETION" -> "COMMAND"
            "COMMAND_MONITOR_UPDATE" -> "MONITOR"
            else -> null
        }
        if (metadata["backgroundActivitySource"] != null) {
            check(originKind != null) { "Non-activity message cannot name a background source" }
            val existing = metadata.getValue("backgroundActivitySource") as? JsonObject
                ?: error("Background activity source must be an object")
            check(existing.optionalString("toolCallId") != null && existing.optionalString("agentDefinitionId") != null) {
                "Background activity source is incomplete"
            }
        } else if (originKind != null) {
            val activityId = metadata.optionalString(if (originKind == "COMMAND") "commandTaskId" else "commandMonitorId")
            if (activityId != null) {
                source(originKind, activityId, message.getValue("conversationId").stringValue())?.let {
                    migrated["backgroundActivitySource"] = it
                }
            }
        }
        return JsonObject(message + ("providerMetadata" to JsonObject(migrated)))
    }

    private fun JsonObject.isCanonicalMessage(): Boolean =
        this["id"] is JsonPrimitive && this["conversationId"] is JsonPrimitive &&
            this["role"] in listOf(JsonPrimitive("USER"), JsonPrimitive("ASSISTANT"), JsonPrimitive("SYSTEM")) &&
            this["content"] is JsonArray

    private fun JsonElement.stringValue(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.also {
            require(it.isNotBlank()) { "Synthetic message metadata requires nonblank strings" }
        } ?: error("Synthetic message metadata requires strings")

    private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.stringValue()

    private data class OriginKey(val kind: String, val activityId: String, val conversationId: String)

    private val kindNames = mapOf(
        "command_task_completion" to "COMMAND_TASK_COMPLETION",
        "command_monitor_update" to "COMMAND_MONITOR_UPDATE",
        "tool_routing_error" to "TOOL_ROUTING_ERROR",
        "runtime_execution_incident" to "RUNTIME_EXECUTION_INCIDENT",
        "slot_event" to "SLOT_EVENT",
        "memory" to "MEMORY",
        "memory_focused_context" to "MEMORY_FOCUSED_CONTEXT",
        "compaction_continuation" to "COMPACTION_CONTINUATION",
    )
    private val opaqueFields = setOf("providerMetadata", "input", "output", "result", "raw", "trace", "toolContext")
}

internal typealias SyntheticMessageSourceLookup = (kind: String, activityId: String, conversationId: String) -> JsonObject?
