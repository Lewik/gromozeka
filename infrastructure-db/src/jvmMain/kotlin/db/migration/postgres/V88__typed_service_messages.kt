package db.migration.postgres

import kotlinx.serialization.json.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** One-time structural conversion, never semantic classification of historical text. */
class V88__typed_service_messages : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use { it.execute(METADATA_FUNCTION) }
        fun rewrite(selectSql: String, updateSql: String, keys: Int, transform: (JsonObject) -> JsonObject) {
            connection.prepareStatement(selectSql).use { select ->
                select.fetchSize = 128
                connection.prepareStatement(updateSql).use { update ->
                    select.executeQuery().use { rows ->
                        var pending = 0
                        while (rows.next()) {
                            val document = Json.parseToJsonElement(rows.getString(keys + 1)).jsonObject
                            update.setString(1, transform(document).toString())
                            for (key in 1..keys) update.setObject(key + 1, rows.getObject(key))
                            update.addBatch()
                            if (++pending == 128) { update.executeBatch(); pending = 0 }
                        }
                        if (pending != 0) update.executeBatch()
                    }
                }
            }
        }
        // Parse text in Kotlin: PostgreSQL jsonb cannot represent escaped NUL in message bodies.
        // Rewriting every row also refreshes its generated context metadata under the new contract.
        rewrite("SELECT id, message_json FROM messages", "UPDATE messages SET message_json = ? WHERE id = ?", 1, ::migrateServiceMessage)
        connection.createStatement().use { it.execute("UPDATE messages SET role = 'RUNTIME_NOTIFICATION' WHERE role = 'SYSTEM'") }
        rewrite("SELECT conversation_id, scheduling::text FROM conversation_runtime_records",
            "UPDATE conversation_runtime_records SET scheduling = ?::jsonb WHERE conversation_id = ?", 1, ::migrateServiceScheduling)
        rewrite("SELECT id, record_json::text FROM agent_response_drafts",
            "UPDATE agent_response_drafts SET record_json = ?::jsonb WHERE id = ?", 1) { draft ->
            JsonObject(draft + ("messages" to JsonArray(draft.getValue("messages").jsonArray.map { migrateServiceMessage(it.jsonObject) })))
        }
        rewrite("SELECT conversation_id, sequence, entry_json::text FROM conversation_runtime_events",
            "UPDATE conversation_runtime_events SET entry_json = ?::jsonb WHERE conversation_id = ? AND sequence = ?", 2) { entry ->
            entry.mapObject("event") { event -> when (event["type"]?.jsonPrimitive?.content) {
                "com.gromozeka.domain.service.ConversationRuntimeEvent.MessageEmitted" -> event.mapObject("message", ::migrateServiceMessage)
                "com.gromozeka.domain.service.ConversationRuntimeEvent.SnapshotUpdated" -> event.mapObject("snapshot", ::migrateServiceScheduling)
                else -> event
            } }
        }
    }
}

internal fun migrateServiceMessage(message: JsonObject): JsonObject {
    val fields = message.toMutableMap()
    val wasSystem = message["role"] == JsonPrimitive("SYSTEM")
    if (wasSystem) fields["role"] = JsonPrimitive("RUNTIME_NOTIFICATION")
    val content = migrateServiceContent(message.getValue("content").jsonArray)
    fields["content"] = if (wasSystem) JsonArray(content.map { element ->
        val item = element.jsonObject
        when (item["type"]?.jsonPrimitive?.content) {
            "runtime_notification" -> item
            "Message" -> JsonObject((item - "text") + mapOf(
                "type" to JsonPrimitive("runtime_notification"),
                "level" to JsonPrimitive("INFO"),
                "content" to item.getValue("text"),
            ))
            else -> error("Unsupported legacy SYSTEM message block: ${item["type"]}")
        }
    }) else content
    message["instructions"]?.let { instructions ->
        fields["instructions"] = JsonArray(instructions.jsonArray.filterNot { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("agent", "com.gromozeka.domain.model.Conversation.Message.Instruction.ResponseExpected") })
    }
    fields["providerMetadata"]?.takeIf { it != JsonNull }?.let { value ->
        val metadata = value.jsonObject
        if ("gromozekaCollaborationOriginalContent" in metadata) fields["providerMetadata"] = JsonObject(metadata +
            ("gromozekaCollaborationOriginalContent" to migrateServiceContent(metadata.getValue("gromozekaCollaborationOriginalContent").jsonArray)))
    }
    return JsonObject(fields)
}

internal fun migrateServiceContent(content: JsonArray): JsonArray = JsonArray(content.map { element ->
    val item = element.jsonObject
    if (item["type"] == JsonPrimitive("com.gromozeka.domain.model.Conversation.Message.ContentItem.System"))
        JsonObject(item + ("type" to JsonPrimitive("runtime_notification"))) else item
})

internal fun migrateServiceScheduling(document: JsonObject): JsonObject {
    var result = document
    for (key in listOf("activeTask", "continuationTask")) result = result.mapObject(key, ::migrateServiceTask)
    for (key in listOf("activeInsertions", "pendingTasks")) {
        result[key]?.takeIf { it != JsonNull }?.let { value ->
            result = JsonObject(result + (key to JsonArray(value.jsonArray.map { migrateServiceTask(it.jsonObject) })))
        }
    }
    result["incidents"]?.takeIf { it != JsonNull }?.let { value ->
        result = JsonObject(result + ("incidents" to JsonArray(value.jsonArray.map { incident ->
            incident.jsonObject.mapObject("task", ::migrateServiceTask)
        })))
    }
    result = result.mapObject("proactiveCompaction") { it.mapObject("checkpoint", ::migrateServiceMessage) }
    return result
}

private fun migrateServiceTask(task: JsonObject): JsonObject = task.mapObject("payload") { payload ->
    when (payload["type"]?.jsonPrimitive?.content) {
        "post_message", "agent_invocation" -> payload.mapObject("userMessage", ::migrateServiceMessage)
        "history_mutation" -> payload.mapObject("mutation") { mutation ->
            if (mutation["mutationType"] == JsonPrimitive("edit")) JsonObject(mutation +
                ("newContent" to migrateServiceContent(mutation.getValue("newContent").jsonArray))) else mutation
        }
        else -> payload
    }
}

private fun JsonObject.mapObject(key: String, transform: (JsonObject) -> JsonObject): JsonObject {
    val value = this[key]?.takeIf { it != JsonNull } ?: return this
    return JsonObject(this + (key to transform(value.jsonObject)))
}

private val METADATA_FUNCTION = """
CREATE OR REPLACE FUNCTION gromozeka_message_context_metadata(document json)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS ${'$'}${'$'}
BEGIN
    RETURN (WITH content AS (
        SELECT value FROM json_array_elements(COALESCE(document -> 'content', '[]'::json))
    ), instructions AS (
        SELECT value, ordinal
        FROM json_array_elements(COALESCE(document -> 'instructions', '[]'::json)) WITH ORDINALITY AS item(value, ordinal)
    ), original_content AS (
        SELECT value FROM json_array_elements(CASE
            WHEN document ->> 'role' = 'ASSISTANT' AND document #>> '{author,type}' = 'agent'
            THEN COALESCE(document #> '{providerMetadata,gromozekaCollaborationOriginalContent}', '[]'::json)
            ELSE '[]'::json END)
    )
    SELECT json_build_object(
        'hasCompaction',
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'ContextCompactionResult') OR
            EXISTS (SELECT 1 FROM original_content WHERE value ->> 'type' = 'ContextCompactionResult'),
        'humanInput',
            COALESCE(document ->> 'role' = 'USER' AND document #>> '{author,type}' = 'user', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' IN ('Message', 'visual_interaction',
                'com.gromozeka.domain.model.Conversation.Message.ContentItem.ImageItem',
                'com.gromozeka.domain.model.Conversation.Message.ContentItem.DocumentItem',
                'com.gromozeka.domain.model.Conversation.Message.ContentItem.ArtifactItem')),
        'userText',
            COALESCE(document ->> 'role' = 'USER', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'Message'),
        'userInstructionIds', COALESCE((
            SELECT json_agg(value -> 'id' ORDER BY ordinal) FROM instructions
            WHERE value ->> 'type' = 'com.gromozeka.domain.model.Conversation.Message.Instruction.UserInstruction'
        ), '[]'::json),
        'promptAgentId', (SELECT value -> 'agentId' FROM content WHERE value ->> 'type' = 'prompt_message'),
        'nativeUsage', CASE WHEN json_typeof(document #> '{providerMetadata,provider}') = 'string' AND
            document #>> '{providerMetadata,provider}' = 'OPENAI_SUBSCRIPTION'
        THEN json_build_object(
            'connectionId', document #> '{providerMetadata,connectionId}',
            'model', document #> '{providerMetadata,model}',
            'tokens', document #> '{providerMetadata,openaiSubscriptionContextTokens}',
            'estimate', document #> '{providerMetadata,openaiSubscriptionContextEstimate}'
        ) ELSE NULL END
    )::text);
EXCEPTION WHEN SQLSTATE '22P05' THEN
    RETURN NULL;
END
${'$'}${'$'};
""".trimIndent()
