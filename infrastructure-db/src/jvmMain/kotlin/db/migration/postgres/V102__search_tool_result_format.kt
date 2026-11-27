package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.infrastructure.db.persistence.referencedArtifactIds
import com.gromozeka.infrastructure.db.persistence.searchText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V102__search_tool_result_format : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Search tool result migration requires a non-transactional Flyway connection" }
        var cursor = ""
        while (true) {
            val page = readPage(connection, cursor)
            if (page.isEmpty()) break
            val updates = page.mapNotNull(::migrateStoredMessage)
            if (updates.isNotEmpty()) {
                val artifactText = readArtifactText(connection, updates.flatMap { it.message.referencedArtifactIds() }.toSet())
                writeBatch(connection, updates, artifactText)
            }
            cursor = page.last().id
        }
        verifyCanonicalResults(connection)
    }

    internal fun migrateDocument(document: JsonObject): JsonObject {
        val content = document["content"]?.jsonArray ?: return document
        var changed = false
        val migratedContent = content.map { element ->
            val item = element as? JsonObject ?: return@map element
            if (item["type"]?.jsonPrimitive?.contentOrNull != TOOL_RESULT_TYPE ||
                item["isError"]?.jsonPrimitive?.booleanOrNull == true ||
                (item["executionToolName"]?.jsonPrimitive?.contentOrNull != SEARCH_TOOLS_TOOL_NAME &&
                    item["toolName"]?.jsonPrimitive?.contentOrNull != SEARCH_TOOLS_TOOL_NAME)
            ) {
                return@map element
            }
            val result = item["result"]?.jsonArray ?: error("Successful search_tools result has no data array")
            val migratedResult = result.map { dataElement ->
                val data = dataElement as? JsonObject ?: return@map dataElement
                if (data["type"]?.jsonPrimitive?.contentOrNull != TEXT_RESULT_TYPE) return@map dataElement
                val original = data["content"]?.jsonPrimitive?.contentOrNull
                    ?: error("search_tools text result has no content")
                val migrated = canonicalResult(original)
                if (migrated == original) dataElement else {
                    changed = true
                    JsonObject(data + ("content" to JsonPrimitive(migrated)))
                }
            }
            if (migratedResult == result) element else JsonObject(item + ("result" to JsonArray(migratedResult)))
        }
        return if (changed) JsonObject(document + ("content" to JsonArray(migratedContent))) else document
    }

    private fun canonicalResult(original: String): String {
        val document = runCatching { Json.parseToJsonElement(original).jsonObject }
            .getOrElse { throw IllegalStateException("Successful search_tools text result is not a JSON object", it) }
        val tools = document["tools"]?.jsonArray
            ?: error("Successful search_tools result has no tools array")
        val names = tools.map { tool ->
            when (tool) {
                is JsonPrimitive -> tool.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> (tool["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull?.takeIf(String::isNotBlank)
                else -> null
            } ?: error("search_tools result contains a tool without a name")
        }
        return buildJsonObject {
            put("tools", JsonArray(names.map(::JsonPrimitive)))
        }.toString()
    }

    private fun migrateStoredMessage(stored: StoredMessage): MessageUpdate? {
        val original = Json.parseToJsonElement(stored.message).jsonObject
        val migrated = migrateDocument(original)
        if (migrated == original) return null
        val message = JSON.decodeFromJsonElement(Conversation.Message.serializer(), migrated)
        check(message.id.value == stored.id) { "Message ID mismatch during search tool result migration" }
        return MessageUpdate(
            id = stored.id,
            originalMessage = stored.message,
            originalHeader = stored.header,
            migratedMessage = migrated.toString(),
            migratedHeader = JSON.encodeToString(CompactionMessageHeader.from(message)),
            message = message,
        )
    }

    private fun readPage(connection: Connection, cursor: String): List<StoredMessage> {
        val candidates = connection.prepareStatement(
            """
            SELECT id, octet_length(message_json)
            FROM messages
            WHERE id > ? AND message_json LIKE '%search_tools%'
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
            SELECT id, message_json, compaction_header
            FROM messages
            WHERE id > ? AND id <= ? AND message_json LIKE '%search_tools%'
            ORDER BY id
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setString(2, candidates.last().id)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(StoredMessage(rows.getString(1), rows.getString(2), checkNotNull(rows.getString(3))))
                    }
                }
            }
        }
        check(messages.map { it.id } == candidates.map { it.id }) {
            "Conversation history changed while reading search tool result migration page"
        }
        return messages
    }

    private fun writeBatch(
        connection: Connection,
        batch: List<MessageUpdate>,
        artifactText: Map<String, String?>,
    ) {
        connection.autoCommit = false
        try {
            val counts = connection.prepareStatement(
                """
                UPDATE messages
                SET message_json = ?, compaction_header = ?, search_text = ?
                WHERE id = ? AND message_json = ? AND compaction_header = ?
                """.trimIndent(),
            ).use { update ->
                batch.forEach { item ->
                    update.setString(1, item.migratedMessage)
                    update.setString(2, item.migratedHeader)
                    update.setString(3, item.message.searchText(artifactText))
                    update.setString(4, item.id)
                    update.setString(5, item.originalMessage)
                    update.setString(6, item.originalHeader)
                    update.addBatch()
                }
                update.executeBatch()
            }
            check(counts.size == batch.size && counts.all { it == 1 }) {
                "Conversation history changed during search tool result migration"
            }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun verifyCanonicalResults(connection: Connection) {
        var cursor = ""
        while (true) {
            val page = readPage(connection, cursor)
            if (page.isEmpty()) break
            page.forEach { stored ->
                val document = Json.parseToJsonElement(stored.message).jsonObject
                check(migrateDocument(document) == document) {
                    "Legacy search_tools result remains after migration in message ${stored.id}"
                }
            }
            cursor = page.last().id
        }
    }

    private fun readArtifactText(connection: Connection, ids: Set<String>): Map<String, String?> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return connection.prepareStatement("SELECT id, search_text FROM artifacts WHERE id IN ($placeholders)").use { select ->
            ids.forEachIndexed { index, id -> select.setString(index + 1, id) }
            select.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) put(rows.getString(1), rows.getString(2))
                }
            }
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

    private data class SizedId(val id: String, val bytes: Long)
    private data class StoredMessage(val id: String, val message: String, val header: String)
    private data class MessageUpdate(
        val id: String,
        val originalMessage: String,
        val originalHeader: String,
        val migratedMessage: String,
        val migratedHeader: String,
        val message: Conversation.Message,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private companion object {
        const val SEARCH_TOOLS_TOOL_NAME = "search_tools"
        const val MAX_PAGE_ROWS = 1_000
        const val MAX_PAGE_BYTES = 4L * 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true }
        val TOOL_RESULT_TYPE = Conversation.Message.ContentItem.ToolResult.serializer().descriptor.serialName
        val TEXT_RESULT_TYPE = Conversation.Message.ContentItem.ToolResult.Data.Text.serializer().descriptor.serialName
    }
}
