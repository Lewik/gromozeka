package db.migration.postgres

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V97__tool_sequence_facts : BaseJavaMigration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val connection = context.connection
        check(connection.autoCommit) { "Tool sequence migration requires a non-transactional Flyway connection" }
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_call_ids text[]")
            statement.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_result_ids text[]")
            statement.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS provider_managed_tool boolean")
            statement.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_results_only boolean")
            statement.execute("ALTER TABLE messages ADD COLUMN IF NOT EXISTS full_compaction_anchor boolean")
            statement.execute("""
                CREATE OR REPLACE FUNCTION gromozeka_tool_results_only(document json)
                RETURNS boolean
                LANGUAGE plpgsql IMMUTABLE STRICT
                AS ${'$'}${'$'}
                BEGIN
                    RETURN json_array_length(document -> 'content') > 0
                        AND NOT EXISTS (
                            SELECT 1
                            FROM json_array_elements(document -> 'content') item
                            WHERE item ->> 'type' IS DISTINCT FROM '$TOOL_RESULT_TYPE'
                        );
                EXCEPTION WHEN SQLSTATE '22P05' THEN
                    RETURN NULL;
                END
                ${'$'}${'$'}
            """.trimIndent())
        }
        val json = Json { ignoreUnknownKeys = true }
        try {
            var afterId: String? = null
            while (true) {
                val batch = readBatch(connection, json, afterId)
                if (batch.isEmpty()) break
                writeBatch(connection, batch)
                afterId = batch.last().id
            }
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE messages ALTER COLUMN tool_call_ids SET NOT NULL")
                statement.execute("ALTER TABLE messages ALTER COLUMN tool_result_ids SET NOT NULL")
                statement.execute("ALTER TABLE messages ALTER COLUMN provider_managed_tool SET NOT NULL")
                statement.execute("ALTER TABLE messages ALTER COLUMN tool_results_only SET NOT NULL")
                statement.execute("ALTER TABLE messages ALTER COLUMN full_compaction_anchor SET NOT NULL")
            }
        } finally {
            connection.createStatement().use { statement ->
                statement.execute("DROP FUNCTION IF EXISTS gromozeka_tool_results_only(json)")
            }
        }
    }

    private fun readBatch(connection: Connection, json: Json, afterId: String?): List<FactUpdate> =
        connection.prepareStatement(
            """
            WITH pending AS (
                SELECT id, compaction_header, message_json,
                    gromozeka_tool_results_only(message_json::json) AS tool_results_only
                FROM messages
                WHERE tool_call_ids IS NULL
                    ${if (afterId == null) "" else "AND id > ?"}
                ORDER BY id
                LIMIT ?
            )
            SELECT id, compaction_header, tool_results_only,
                CASE WHEN compaction_header IS NULL OR tool_results_only IS NULL THEN message_json ELSE NULL END
            FROM pending
            ORDER BY id
            """.trimIndent(),
        ).use { select ->
            var parameter = 1
            afterId?.let { select.setString(parameter++, it) }
            select.setInt(parameter, BATCH_SIZE)
            select.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val body = rows.getString(4)?.let { json.decodeFromString<Conversation.Message>(it) }
                        val header = rows.getString(2)?.let { json.decodeFromString<CompactionMessageHeader>(it) }
                            ?: CompactionMessageHeader.from(checkNotNull(body) { "Message $id has no body or header" })
                        check(header.id.value == id) { "Compaction header ID mismatch for $id" }
                        val callIds = header.calls.map { it.value }.distinct().sorted()
                        val resultIds = header.results.map { it.value }.distinct().sorted()
                        add(FactUpdate(
                            id = id,
                            callIds = callIds,
                            resultIds = resultIds,
                            providerManaged = header.providerManaged,
                            toolResultsOnly = (rows.getObject(3) as? Boolean)
                                ?: checkNotNull(body) { "Message $id needs a body fallback" }.content
                                    .let { content -> content.isNotEmpty() && content.all { it is ContentItem.ToolResult } },
                            fullCompactionAnchor = header.node.isFull,
                        ))
                    }
                }
            }
        }

    private fun writeBatch(connection: Connection, batch: List<FactUpdate>) {
        check(connection.autoCommit) { "Tool sequence batch started inside an existing transaction" }
        connection.autoCommit = false
        val arrays = mutableListOf<java.sql.Array>()
        try {
            val counts = connection.prepareStatement(
                """
                UPDATE messages
                SET tool_call_ids = ?, tool_result_ids = ?, provider_managed_tool = ?,
                    tool_results_only = ?, full_compaction_anchor = ?
                WHERE id = ? AND tool_call_ids IS NULL
                """.trimIndent(),
            ).use { update ->
                batch.forEach { item ->
                    val calls = connection.createArrayOf("text", item.callIds.toTypedArray()).also(arrays::add)
                    val results = connection.createArrayOf("text", item.resultIds.toTypedArray()).also(arrays::add)
                    update.setArray(1, calls)
                    update.setArray(2, results)
                    update.setBoolean(3, item.providerManaged)
                    update.setBoolean(4, item.toolResultsOnly)
                    update.setBoolean(5, item.fullCompactionAnchor)
                    update.setString(6, item.id)
                    update.addBatch()
                }
                update.executeBatch()
            }
            check(counts.size == batch.size && counts.all { it == 1 }) {
                "Tool sequence migration lost a message while writing a batch"
            }
            connection.commit()
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            arrays.forEach { runCatching { it.free() } }
            connection.autoCommit = true
        }
    }

    private data class FactUpdate(
        val id: String,
        val callIds: List<String>,
        val resultIds: List<String>,
        val providerManaged: Boolean,
        val toolResultsOnly: Boolean,
        val fullCompactionAnchor: Boolean,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private companion object {
        const val BATCH_SIZE = 1_000
        val TOOL_RESULT_TYPE = ContentItem.ToolResult.serializer().descriptor.serialName
    }
}
