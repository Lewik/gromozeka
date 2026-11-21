package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V91__compaction_headers : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Compaction header migration requires a non-transactional Flyway connection" }
        connection.createStatement().use {
            it.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS compaction_header text")
        }
        val json = Json { ignoreUnknownKeys = true }
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

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
