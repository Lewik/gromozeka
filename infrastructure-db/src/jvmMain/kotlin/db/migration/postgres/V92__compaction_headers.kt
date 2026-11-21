package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V92__compaction_headers : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Compaction header migration requires a non-transactional Flyway connection" }
        val json = Json { ignoreUnknownKeys = true }
        val fullUpdates = readFullUpdates(connection)
        fullUpdates.filter { it.prefixes.isNotEmpty() }.forEach { validateFullPrefixes(connection, it) }
        connection.createStatement().use {
            it.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS compaction_header text")
        }
        fullUpdates.chunked(BATCH_SIZE).forEach { writeFullBatch(connection, it) }
        while (true) {
            val batch = readBatch(connection, json)
            if (batch.isEmpty()) break
            writeBatch(connection, batch)
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM messages WHERE compaction_header IS NULL").use { rows ->
                check(rows.next() && rows.getLong(1) == 0L) { "Compaction header migration left messages unindexed" }
            }
        }
    }

    private fun readFullUpdates(connection: Connection): List<FullUpdate> {
        val candidates = connection.prepareStatement(
            """
                SELECT id, message_json
                FROM messages
                WHERE message_json LIKE '%ContextCompactionResult%'
                ORDER BY id
            """.trimIndent(),
        ).use { select ->
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.getString(1) to rows.getString(2))
                    }
                }
            }
        }
        return candidates.mapNotNull { (id, original) -> fullUpdate(connection, id, original) }
    }

    private fun fullUpdate(connection: Connection, id: String, original: String): FullUpdate? {
        val document = Json.parseToJsonElement(original).jsonObject
        check(document["id"]?.jsonPrimitive?.content == id) {
            "Message ID mismatch during full compaction migration"
        }
        val prefixes = mutableListOf<List<String>>()
        var inferredBoundary: String? = null
        var changed = false
        val content = document.getValue("content").jsonArray.map { element ->
            val item = element as? JsonObject ?: return@map element
            if (item["type"] != JsonPrimitive("ContextCompactionResult")) return@map element
            val coverage = item["coverage"]?.jsonPrimitive?.content
            check(coverage == "SELECTED_MESSAGES" || coverage == "ALL_PREVIOUS") {
                "Compaction $id has unrecognized coverage"
            }
            val boundary = item["sourceBoundaryMessageId"]
                ?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            if (coverage == "SELECTED_MESSAGES") {
                check(boundary == null) { "Selective compaction $id unexpectedly has a source boundary" }
                return@map element
            }
            val sourceIds = when (val source = item["sourceMessageIds"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> source.map { it.jsonPrimitive.content }
                else -> error("Full compaction $id has invalid source IDs")
            }
            val migratedBoundary = if (sourceIds.isEmpty()) {
                boundary ?: inferredBoundary ?: inferBoundary(connection, id).also { inferredBoundary = it }
            } else {
                check(sourceIds.distinct().size == sourceIds.size) {
                    "Full compaction $id has duplicate source IDs"
                }
                sourceIds.last().also {
                    check(boundary == null || boundary == it) {
                        "Full compaction $id has conflicting source representations"
                    }
                    prefixes += sourceIds
                }
            }
            if (sourceIds.isEmpty() && boundary != null) return@map element
            changed = true
            JsonObject(item.filterKeys {
                it != "sourceMessageIds" && it != "sourceBoundaryMessageId"
            } + ("sourceBoundaryMessageId" to JsonPrimitive(migratedBoundary)))
        }
        if (!changed) return null
        return FullUpdate(
            id = id,
            original = original,
            migrated = JsonObject(document + ("content" to JsonArray(content))).toString(),
            prefixes = prefixes,
        )
    }

    private fun inferBoundary(connection: Connection, id: String): String {
        val boundaries = mutableListOf<String>()
        connection.prepareStatement(
            """
                SELECT previous.message_id
                FROM thread_messages checkpoint
                LEFT JOIN thread_messages previous
                  ON previous.thread_id = checkpoint.thread_id
                 AND previous.position = checkpoint.position - 1
                WHERE checkpoint.message_id = ?
            """.trimIndent(),
        ).use { select ->
            select.setString(1, id)
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    boundaries += checkNotNull(rows.getString(1)) {
                        "Full compaction $id has no preceding source in one of its threads"
                    }
                }
            }
        }
        check(boundaries.isNotEmpty()) { "Full compaction $id is not linked to a thread" }
        check(boundaries.distinct().size == 1) {
            "Full compaction $id has different source boundaries across threads"
        }
        return boundaries.first()
    }

    private fun validateFullPrefixes(connection: Connection, update: FullUpdate) {
        val branches = linkedMapOf<String, MutableList<String>>()
        connection.prepareStatement(
            """
                SELECT checkpoint.thread_id, prefix.message_id
                FROM thread_messages checkpoint
                LEFT JOIN thread_messages prefix
                  ON prefix.thread_id = checkpoint.thread_id
                 AND prefix.position < checkpoint.position
                WHERE checkpoint.message_id = ?
                ORDER BY checkpoint.thread_id, prefix.position
            """.trimIndent(),
        ).use { select ->
            select.setString(1, update.id)
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val prefix = branches.getOrPut(rows.getString(1), ::mutableListOf)
                    rows.getString(2)?.let(prefix::add)
                }
            }
        }
        for ((thread, prefix) in branches) {
            update.prefixes.forEach { expected ->
                check(prefix == expected) {
                    "Full compaction ${update.id} is not an exact prefix of thread $thread"
                }
            }
        }
    }

    private fun writeFullBatch(connection: Connection, batch: List<FullUpdate>) {
        check(connection.autoCommit) { "Full compaction batch started inside an existing transaction" }
        connection.autoCommit = false
        try {
            val counts = connection.prepareStatement(
                "UPDATE messages SET message_json = ? WHERE id = ? AND message_json = ?",
            ).use { update ->
                batch.forEach { item ->
                    update.setString(1, item.migrated)
                    update.setString(2, item.id)
                    update.setString(3, item.original)
                    update.addBatch()
                }
                update.executeBatch()
            }
            check(counts.size == batch.size && counts.all { it == 1 }) {
                "Compaction history changed after migration preflight"
            }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun readBatch(connection: Connection, json: Json): List<HeaderUpdate> =
        connection.prepareStatement(
            "SELECT id, message_json FROM messages WHERE compaction_header IS NULL ORDER BY id LIMIT ?",
        ).use { select ->
            select.setInt(1, BATCH_SIZE)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val message = json.decodeFromString<Conversation.Message>(rows.getString(2))
                        check(message.id.value == id) { "Message ID mismatch during compaction header migration" }
                        add(HeaderUpdate(id, Json.encodeToString(CompactionMessageHeader.from(message))))
                    }
                }
            }
        }

    private fun writeBatch(connection: Connection, batch: List<HeaderUpdate>) {
        check(connection.autoCommit) { "Compaction header batch started inside an existing transaction" }
        connection.autoCommit = false
        try {
            val counts = connection.prepareStatement(
                "UPDATE messages SET compaction_header = ? WHERE id = ? AND compaction_header IS NULL",
            ).use { update ->
                batch.forEach { item ->
                    update.setString(1, item.header)
                    update.setString(2, item.id)
                    update.addBatch()
                }
                update.executeBatch()
            }
            check(counts.size == batch.size && counts.all { it == 1 }) {
                "Compaction header migration lost a message while writing a batch"
            }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private data class HeaderUpdate(val id: String, val header: String)
    private data class FullUpdate(
        val id: String,
        val original: String,
        val migrated: String,
        val prefixes: List<List<String>>,
    )

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
