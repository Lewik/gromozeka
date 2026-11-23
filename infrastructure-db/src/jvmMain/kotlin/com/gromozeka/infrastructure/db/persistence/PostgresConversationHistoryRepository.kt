package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.repository.ConversationHistoryRepository
import com.gromozeka.domain.repository.PositionedConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Repository
import javax.sql.DataSource

@Repository
class PostgresConversationHistoryRepository(
    private val dataSource: DataSource,
    private val json: Json,
) : ConversationHistoryRepository {
    override suspend fun latestMessageTimes(threadIds: Set<Conversation.Thread.Id>): Map<Conversation.Thread.Id, kotlin.time.Instant?> {
        if (threadIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val ids = connection.createArrayOf("varchar", threadIds.map { it.value }.toTypedArray())
                try {
                    connection.prepareStatement("""
                        SELECT t.id, last_message.created_at
                        FROM threads t
                        LEFT JOIN LATERAL (
                            SELECT m.created_at
                            FROM thread_messages tm JOIN messages m ON m.id = tm.message_id
                            WHERE tm.thread_id = t.id
                              AND m.role <> 'RUNTIME'
                            ORDER BY tm.position DESC LIMIT 1
                        ) last_message ON TRUE
                        WHERE t.id = ANY (?)
                    """.trimIndent()).use { statement ->
                        statement.setArray(1, ids)
                        statement.executeQuery().use { rows ->
                            buildMap {
                                while (rows.next()) {
                                    val timestamp = rows.getTimestamp(2)?.toInstant()
                                    put(Conversation.Thread.Id(rows.getString(1)), timestamp?.let {
                                        kotlin.time.Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong())
                                    })
                                }
                            }
                        }
                    }
                } finally { ids.free() }
            }
        }
    }

    override suspend fun page(threadId: Conversation.Thread.Id, before: Int?, after: Int?, limit: Int): List<PositionedConversationMessage> {
        require(limit in 1..101)
        require(before == null || after == null)
        val boundary = when { before != null -> "AND tm.position < ?"; after != null -> "AND tm.position > ?"; else -> "" }
        val direction = if (after != null) "ASC" else "DESC"
        return queryPage(
            "$PAGE_JOIN WHERE tm.thread_id = ? $boundary ORDER BY tm.position $direction LIMIT ?",
            listOfNotNull(threadId.value, before ?: after, limit),
        ).sortedBy { it.position }
    }

    override suspend fun position(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Int? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT position FROM thread_messages WHERE thread_id = ? AND message_id = ?").use { statement ->
                statement.setString(1, threadId.value)
                statement.setString(2, messageId.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
            }
        }
    }

    override suspend fun message(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): PositionedConversationMessage? =
        query("$JOIN WHERE tm.thread_id = ? AND tm.message_id = ?", listOf(threadId.value, messageId.value)).singleOrNull()

    override suspend fun toolResults(threadId: Conversation.Thread.Id, callIds: Set<String>): List<PositionedConversationMessage> {
        if (callIds.isEmpty()) return emptyList()
        val placeholders = callIds.joinToString(",") { "?" }
        return query("""$JOIN WHERE tm.thread_id = ? AND EXISTS (
            SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item
            WHERE item ->> 'type' = '$TOOL_RESULT_TYPE' AND item ->> 'toolUseId' IN ($placeholders)
        ) ORDER BY tm.position""", listOf(threadId.value) + callIds)
    }

    override suspend fun selectedIds(threadId: Conversation.Thread.Id, selection: ConversationMessageSelection): List<Conversation.Message.Id> = withContext(Dispatchers.IO) {
        val thinking = "EXISTS (SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item WHERE item ->> 'type' = '$THINKING_TYPE')"
        val tool = "EXISTS (SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item WHERE item ->> 'type' = '$TOOL_CALL_TYPE')"
        val visible = "EXISTS (SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item WHERE item ->> 'type' <> '$TOOL_RESULT_TYPE')"
        val predicate = when (selection) {
            ConversationMessageSelection.ALL -> "TRUE"
            ConversationMessageSelection.USER -> "m.role = 'USER' AND $visible"
            ConversationMessageSelection.ASSISTANT -> "m.role = 'ASSISTANT' AND $visible"
            ConversationMessageSelection.THINKING -> thinking
            ConversationMessageSelection.TOOL -> "$tool OR EXISTS (SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item WHERE item ->> 'type' = '$TOOL_RESULT_TYPE')"
            ConversationMessageSelection.PLAIN -> "NOT $thinking AND NOT $tool AND $visible AND m.role <> 'SYSTEM'"
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT tm.message_id FROM thread_messages tm JOIN messages m ON m.id = tm.message_id WHERE tm.thread_id = ? AND ($predicate) ORDER BY tm.position").use { statement ->
                statement.setString(1, threadId.value)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(Conversation.Message.Id(rows.getString(1))) } }
            }
        }
    }

    override suspend fun latestUserMessage(threadId: Conversation.Thread.Id): PositionedConversationMessage? = query(
        "$JOIN WHERE tm.thread_id = ? AND m.role = 'USER' AND EXISTS (SELECT 1 FROM jsonb_array_elements(m.message_json::jsonb -> 'content') item WHERE item ->> 'type' = 'Message') ORDER BY tm.position DESC LIMIT 1",
        listOf(threadId.value),
    ).singleOrNull()

    override suspend fun latestUserActivity(threadId: Conversation.Thread.Id, userId: com.gromozeka.domain.model.User.Id):
        com.gromozeka.domain.model.ConversationUserActivity? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT tm.position, m.created_at
                FROM thread_messages tm JOIN messages m ON m.id = tm.message_id
                WHERE tm.thread_id = ? AND m.role = 'USER'
                    AND m.message_json::jsonb -> 'author' ->> 'type' = 'user'
                    AND m.message_json::jsonb -> 'author' ->> 'userId' = ?
                    AND (m.message_json::jsonb -> 'providerMetadata' -> 'synthetic') IS DISTINCT FROM 'true'::jsonb
                ORDER BY tm.position DESC LIMIT 1
            """.trimIndent()).use { statement ->
                statement.setString(1, threadId.value)
                statement.setString(2, userId.value)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val at = rows.getTimestamp(2).toInstant()
                        com.gromozeka.domain.model.ConversationUserActivity(rows.getInt(1),
                            kotlin.time.Instant.fromEpochSeconds(at.epochSecond, at.nano.toLong()))
                    }
                }
            }
        }
    }

    override suspend fun latestUserInstructionId(
        threadId: Conversation.Thread.Id,
        instructionIds: Set<String>,
    ): String? {
        if (instructionIds.isEmpty()) return null
        val placeholders = instructionIds.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("""
                    SELECT item.instruction_id
                    FROM thread_messages tm
                    JOIN messages m ON m.id = tm.message_id
                    CROSS JOIN LATERAL jsonb_array_elements_text(
                        COALESCE(m.context_metadata_json::jsonb -> 'userInstructionIds', '[]'::jsonb)
                    ) WITH ORDINALITY AS item(instruction_id, ordinal)
                    WHERE tm.thread_id = ? AND item.instruction_id IN ($placeholders)
                    ORDER BY tm.position DESC, item.ordinal DESC
                    LIMIT 1
                """.trimIndent()).use { statement ->
                    statement.setString(1, threadId.value)
                    instructionIds.forEachIndexed { index, id -> statement.setString(index + 2, id) }
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
        }
    }

    private suspend fun query(sql: String, arguments: List<Any>): List<PositionedConversationMessage> {
        val messages = withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    arguments.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) add(PositionedConversationMessage(rows.getInt(1), json.decodeFromString<Conversation.Message>(rows.getString(2))))
                    } }
                }
            }
        }
        val authors = dbQuery { resolveCurrentMessageAuthors(messages.map { it.message }) }
        return messages.zip(authors) { positioned, message -> positioned.copy(message = message) }
    }

    /** Runtime rows are visible timeline entries, but their model-only bodies can be hundreds of KiB. */
    private suspend fun queryPage(sql: String, arguments: List<Any>): List<PositionedConversationMessage> {
        val messages = withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    arguments.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) {
                            val role = Conversation.Message.Role.valueOf(rows.getString(4))
                            val message = if (role == Conversation.Message.Role.RUNTIME) {
                                val createdAt = rows.getTimestamp(5).toInstant()
                                Conversation.Message(
                                    id = Conversation.Message.Id(rows.getString(2)),
                                    conversationId = Conversation.Id(rows.getString(3)),
                                    role = role,
                                    author = Conversation.Message.Author.Runtime(),
                                    content = emptyList(),
                                    createdAt = kotlin.time.Instant.fromEpochSeconds(
                                        createdAt.epochSecond,
                                        createdAt.nano.toLong(),
                                    ),
                                )
                            } else {
                                json.decodeFromString<Conversation.Message>(rows.getString(6))
                            }
                            add(PositionedConversationMessage(rows.getInt(1), message))
                        }
                    } }
                }
            }
        }
        val authors = dbQuery { resolveCurrentMessageAuthors(messages.map { it.message }) }
        return messages.zip(authors) { positioned, message -> positioned.copy(message = message) }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private companion object {
        val TOOL_RESULT_TYPE = Conversation.Message.ContentItem.ToolResult.serializer().descriptor.serialName
        val THINKING_TYPE = Conversation.Message.ContentItem.Thinking.serializer().descriptor.serialName
        val TOOL_CALL_TYPE = Conversation.Message.ContentItem.ToolCall.serializer().descriptor.serialName
        const val JOIN = "SELECT tm.position, m.message_json FROM thread_messages tm JOIN messages m ON m.id = tm.message_id"
        const val PAGE_JOIN = """
            SELECT tm.position, m.id, m.conversation_id, m.role, m.created_at,
                CASE WHEN m.role = 'RUNTIME' THEN NULL ELSE m.message_json END
            FROM thread_messages tm JOIN messages m ON m.id = tm.message_id
        """
    }
}
