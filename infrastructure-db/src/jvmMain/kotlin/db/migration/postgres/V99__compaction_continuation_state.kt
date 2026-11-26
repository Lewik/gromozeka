package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V99__compaction_continuation_state : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Compaction continuation migration requires a non-transactional Flyway connection" }
        val json = Json { ignoreUnknownKeys = true }
        var checkpointCursor = ""
        while (true) {
            val checkpoints = readIdPage(connection, checkpointCursor, fullCompactionOnly = true)
            if (checkpoints.isEmpty()) break
            checkpoints.forEach { checkpoint ->
                val id = checkpoint.id
                val original = checkpoint.message
                val document = Json.parseToJsonElement(original).jsonObject
                val message = json.decodeFromString<Conversation.Message>(original)
                check(message.id.value == id) { "Message ID mismatch during compaction continuation migration" }
                val fullResults = message.content.filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()
                    .filter { it.coversAllPrevious }
                check(fullResults.isNotEmpty()) { "Full compaction anchor $id has no full checkpoint" }
                val boundaries = fullResults.map { requireNotNull(it.sourceBoundaryMessageId).value }.distinct()
                check(boundaries.size == 1) { "Full compaction anchor $id has conflicting source boundaries" }
                val state = continuationState(connection, id, boundaries.single(), json)
                val content = document.getValue("content").jsonArray.map { element ->
                    val item = element as? JsonObject ?: return@map element
                    if (item["type"] == JsonPrimitive("ContextCompactionResult") &&
                        item["coverage"] == JsonPrimitive("ALL_PREVIOUS")) {
                        JsonObject(item + ("continuationState" to json.encodeToJsonElement(state)))
                    } else element
                }
                val encoded = JsonObject(document + ("content" to JsonArray(content))).toString()
                if (encoded != original) writeMessage(connection, id, original, encoded)
            }
            checkpointCursor = checkpoints.last().id
        }
        rewriteHeaders(connection, json)
    }

    private fun continuationState(
        connection: Connection,
        checkpointId: String,
        boundaryId: String,
        json: Json,
    ): Conversation.Message.ContentItem.ContextCompactionResult.ContinuationState {
        val branch = connection.prepareStatement(
            """
            SELECT checkpoint.thread_id, boundary.position
            FROM thread_messages checkpoint
            JOIN thread_messages boundary
              ON boundary.thread_id = checkpoint.thread_id
             AND boundary.message_id = ?
             AND boundary.position < checkpoint.position
            WHERE checkpoint.message_id = ?
            ORDER BY checkpoint.thread_id
            LIMIT 1
            """.trimIndent(),
        ).use { select ->
            select.setString(1, boundaryId)
            select.setString(2, checkpointId)
            select.executeQuery().use { rows ->
                check(rows.next()) { "Full checkpoint $checkpointId has no branch containing boundary $boundaryId" }
                rows.getString(1) to rows.getInt(2)
            }
        }
        val latestInstructions = linkedMapOf<String, Conversation.Message.Instruction.UserInstruction>()
        var previousHumanAt: kotlin.time.Instant? = null
        var afterPosition = -1
        while (afterPosition < branch.second) {
            val page = readPositionPage(connection, branch.first, afterPosition, branch.second)
            check(page.isNotEmpty()) { "Full checkpoint $checkpointId has a gap before its source boundary" }
            page.forEach { entry ->
                val message = json.decodeFromString<Conversation.Message>(entry.message)
                message.instructions.filterIsInstance<Conversation.Message.Instruction.UserInstruction>().forEach { instruction ->
                    latestInstructions.remove(instruction.id)
                    latestInstructions[instruction.id] = instruction
                }
                if (MessageContextMetadata.from(message).humanInput) previousHumanAt = message.createdAt
                afterPosition = entry.position
            }
        }
        return Conversation.Message.ContentItem.ContextCompactionResult.ContinuationState(
            stickyInstructionCandidates = latestInstructions.values.toList(),
            previousHumanMessageAt = previousHumanAt,
        )
    }

    private fun writeMessage(connection: Connection, id: String, original: String, migrated: String) {
        connection.autoCommit = false
        try {
            val updated = connection.prepareStatement(
                "UPDATE messages SET message_json = ? WHERE id = ? AND message_json = ?",
            ).use { update ->
                update.setString(1, migrated)
                update.setString(2, id)
                update.setString(3, original)
                update.executeUpdate()
            }
            check(updated == 1) { "Compaction checkpoint $id changed during continuation migration" }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun rewriteHeaders(connection: Connection, json: Json) {
        var cursor = ""
        while (true) {
            val page = readIdPage(connection, cursor, fullCompactionOnly = false)
            if (page.isEmpty()) break
            val batch = page.map { entry ->
                val message = json.decodeFromString<Conversation.Message>(entry.message)
                check(message.id.value == entry.id) { "Message ID mismatch during continuation header migration" }
                HeaderUpdate(entry.id, entry.message, json.encodeToString(CompactionMessageHeader.from(message)))
            }
            connection.autoCommit = false
            try {
                val counts = connection.prepareStatement(
                    "UPDATE messages SET compaction_header = ? WHERE id = ? AND message_json = ?",
                ).use { update ->
                    batch.forEach { item ->
                        update.setString(1, item.header)
                        update.setString(2, item.id)
                        update.setString(3, item.message)
                        update.addBatch()
                    }
                    update.executeBatch()
                }
                check(counts.size == batch.size && counts.all { it == 1 }) {
                    "Conversation history changed during continuation header migration"
                }
                connection.commit()
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally {
                connection.autoCommit = true
            }
            cursor = batch.last().id
        }
    }

    private fun readIdPage(
        connection: Connection,
        cursor: String,
        fullCompactionOnly: Boolean,
    ): List<StoredMessage> {
        val anchorFilter = if (fullCompactionOnly) "full_compaction_anchor AND " else ""
        val candidates = connection.prepareStatement(
            """
                SELECT id, octet_length(message_json)
                FROM messages
                WHERE ${anchorFilter}id > ?
                ORDER BY id
                LIMIT ?
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setInt(2, MAX_PAGE_ROWS)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(SizedCursor(rows.getString(1), rows.getLong(2)))
                }
            }
        }.byteBoundedPrefix()
        if (candidates.isEmpty()) return emptyList()
        val messages = connection.prepareStatement(
            """
                SELECT id, message_json
                FROM messages
                WHERE ${anchorFilter}id > ? AND id <= ?
                ORDER BY id
            """.trimIndent(),
        ).use { select ->
            select.setString(1, cursor)
            select.setString(2, candidates.last().cursor)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(StoredMessage(rows.getString(1), rows.getString(2)))
                }
            }
        }
        check(messages.map { it.id } == candidates.map { it.cursor }) {
            "Conversation history changed while reading continuation migration page"
        }
        return messages
    }

    private fun readPositionPage(
        connection: Connection,
        threadId: String,
        afterPosition: Int,
        boundaryPosition: Int,
    ): List<PositionedMessage> {
        val candidates = connection.prepareStatement(
            """
                SELECT tm.position, octet_length(m.message_json)
                FROM thread_messages tm
                JOIN messages m ON m.id = tm.message_id
                WHERE tm.thread_id = ? AND tm.position > ? AND tm.position <= ?
                ORDER BY tm.position
                LIMIT ?
            """.trimIndent(),
        ).use { select ->
            select.setString(1, threadId)
            select.setInt(2, afterPosition)
            select.setInt(3, boundaryPosition)
            select.setInt(4, MAX_PAGE_ROWS)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(SizedCursor(rows.getInt(1), rows.getLong(2)))
                }
            }
        }.byteBoundedPrefix()
        if (candidates.isEmpty()) return emptyList()
        val messages = connection.prepareStatement(
            """
                SELECT tm.position, m.message_json
                FROM thread_messages tm
                JOIN messages m ON m.id = tm.message_id
                WHERE tm.thread_id = ? AND tm.position > ? AND tm.position <= ?
                ORDER BY tm.position
            """.trimIndent(),
        ).use { select ->
            select.setString(1, threadId)
            select.setInt(2, afterPosition)
            select.setInt(3, candidates.last().cursor)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(PositionedMessage(rows.getInt(1), rows.getString(2)))
                }
            }
        }
        check(messages.map { it.position } == candidates.map { it.cursor }) {
            "Conversation history changed while reading compaction continuation window"
        }
        return messages
    }

    private fun <T> List<SizedCursor<T>>.byteBoundedPrefix(): List<SizedCursor<T>> {
        val selected = mutableListOf<SizedCursor<T>>()
        var bytes = 0L
        for (candidate in this) {
            check(candidate.bytes >= 0L) { "Conversation message has a negative encoded size" }
            // An oversized legacy message still has to make progress as a one-row page.
            if (selected.isNotEmpty() && bytes + candidate.bytes > MAX_PAGE_BYTES) break
            selected += candidate
            bytes += candidate.bytes
        }
        return selected
    }

    private data class HeaderUpdate(val id: String, val message: String, val header: String)

    private data class StoredMessage(val id: String, val message: String)

    private data class PositionedMessage(val position: Int, val message: String)

    private data class SizedCursor<T>(val cursor: T, val bytes: Long)

    private companion object {
        const val MAX_PAGE_ROWS = 1_000
        const val MAX_PAGE_BYTES = 4L * 1024 * 1024
    }
}
