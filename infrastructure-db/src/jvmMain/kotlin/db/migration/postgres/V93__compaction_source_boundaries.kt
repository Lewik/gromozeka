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

class V93__compaction_source_boundaries : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Compaction boundary migration requires a non-transactional Flyway connection" }
        val updates = readFullUpdates(connection)
        updates.filter { it.prefixes.isNotEmpty() }.forEach { validateFullPrefixes(connection, it) }
        updates.chunked(BATCH_SIZE).forEach { writeFullBatch(connection, it) }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM messages WHERE compaction_header IS NULL").use { rows ->
                check(rows.next() && rows.getLong(1) == 0L) { "Compaction boundary migration found unindexed messages" }
            }
        }
    }

    private fun readFullUpdates(connection: Connection): List<FullUpdate> {
        val candidates = connection.prepareStatement(
            """
                SELECT id, message_json, compaction_header
                FROM messages
                WHERE message_json LIKE '%ContextCompactionResult%'
                ORDER BY id
            """.trimIndent(),
        ).use { select ->
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Triple(rows.getString(1), rows.getString(2), rows.getString(3)))
                    }
                }
            }
        }
        return candidates.mapNotNull { (id, original, originalHeader) ->
            fullUpdate(connection, id, original, originalHeader)
        }
    }

    private fun fullUpdate(
        connection: Connection,
        id: String,
        original: String,
        originalHeader: String?,
    ): FullUpdate? {
        val document = Json.parseToJsonElement(original).jsonObject
        check(document["id"]?.jsonPrimitive?.content == id) {
            "Message ID mismatch during full compaction migration"
        }
        val prefixes = mutableListOf<List<String>>()
        var inferredBoundary: String? = null
        var hasFullCompaction = false
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
            hasFullCompaction = true
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
        if (!hasFullCompaction) return null
        val migrated = if (changed) {
            JsonObject(document + ("content" to JsonArray(content))).toString()
        } else original
        val message = Json { ignoreUnknownKeys = true }.decodeFromString<Conversation.Message>(migrated)
        val migratedHeader = Json.encodeToString(CompactionMessageHeader.from(message))
        if (!changed && originalHeader == migratedHeader) return null
        return FullUpdate(id, original, originalHeader, migrated, migratedHeader, prefixes)
    }

    private fun inferBoundary(connection: Connection, id: String): String {
        val validation = connection.prepareStatement(
            """
                WITH branch_prefixes AS (
                    SELECT checkpoint.thread_id,
                           array_agg(prefix.message_id ORDER BY prefix.position)
                               FILTER (WHERE prefix.message_id IS NOT NULL) AS prefix
                    FROM thread_messages checkpoint
                    LEFT JOIN thread_messages prefix
                      ON prefix.thread_id = checkpoint.thread_id
                     AND prefix.position < checkpoint.position
                    WHERE checkpoint.message_id = ?
                    GROUP BY checkpoint.thread_id
                )
                SELECT count(*),
                       count(*) FILTER (WHERE prefix IS NULL OR cardinality(prefix) = 0),
                       count(DISTINCT prefix),
                       min(prefix[cardinality(prefix)])
                FROM branch_prefixes
            """.trimIndent(),
        ).use { select ->
            select.setString(1, id)
            select.executeQuery().use { rows ->
                check(rows.next())
                PrefixValidation(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getString(4))
            }
        }
        check(validation.branchCount > 0) { "Full compaction $id is not linked to a thread" }
        check(validation.emptyPrefixCount == 0) {
            "Full compaction $id has no preceding source in one of its threads"
        }
        check(validation.distinctPrefixCount == 1) {
            "Full compaction $id has different source prefixes across threads"
        }
        return checkNotNull(validation.boundary)
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
                """
                    UPDATE messages
                    SET message_json = ?, compaction_header = ?
                    WHERE id = ? AND message_json = ? AND compaction_header IS NOT DISTINCT FROM ?
                """.trimIndent(),
            ).use { update ->
                batch.forEach { item ->
                    update.setString(1, item.migrated)
                    update.setString(2, item.migratedHeader)
                    update.setString(3, item.id)
                    update.setString(4, item.original)
                    update.setString(5, item.originalHeader)
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

    private data class PrefixValidation(
        val branchCount: Int,
        val emptyPrefixCount: Int,
        val distinctPrefixCount: Int,
        val boundary: String?,
    )

    private data class FullUpdate(
        val id: String,
        val original: String,
        val originalHeader: String?,
        val migrated: String,
        val migratedHeader: String,
        val prefixes: List<List<String>>,
    )

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
