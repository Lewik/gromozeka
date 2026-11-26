package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextHeader
import com.gromozeka.domain.model.MessageContextMetadata
import com.gromozeka.domain.model.ToolSequenceFacts
import com.gromozeka.domain.model.ToolSequenceMessageHeader
import com.gromozeka.domain.model.compactions
import com.gromozeka.domain.model.selectModelHistoryMessageIds
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import java.sql.Connection
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessagePageEntry
import com.gromozeka.domain.repository.ThreadMessageReplacement
import com.gromozeka.domain.repository.ThreadContextMessages
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.RecentVisibleMessage
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.stereotype.Service

@Service
class ExposedThreadMessageRepository(
    private val json: Json
) : ThreadMessageRepository {

    override suspend fun add(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
        position: Int
    ): Unit = dbQuery {
        ThreadMessages.insert {
            it[ThreadMessages.threadId] = threadId.value
            it[ThreadMessages.messageId] = messageId.value
            it[ThreadMessages.position] = position
        }
    }

    override suspend fun addBatch(links: List<ThreadMessageLink>): Unit = dbQuery {
        links.chunked(256).forEach { batch ->
            ThreadMessages.batchInsert(batch, shouldReturnGeneratedValues = false) { link ->
                this[ThreadMessages.threadId] = link.threadId.value
                this[ThreadMessages.messageId] = link.messageId.value
                this[ThreadMessages.position] = link.position
            }
        }
    }

    override suspend fun contains(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Boolean = dbQuery {
        ThreadMessages.select(ThreadMessages.messageId)
            .where { (ThreadMessages.threadId eq threadId.value) and (ThreadMessages.messageId eq messageId.value) }
            .singleOrNull() != null
    }

    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? = dbQuery {
        val maximum = ThreadMessages.position.max()
        ThreadMessages.select(maximum)
            .where { ThreadMessages.threadId eq threadId.value }
            .single()[maximum]
    }

    override suspend fun getLinkPage(
        threadId: Conversation.Thread.Id,
        afterPositionExclusive: Int,
        limit: Int,
    ): List<ThreadMessageLink> = dbQuery {
        require(afterPositionExclusive >= -1)
        require(limit in 1..1_000)
        ThreadMessages.select(ThreadMessages.messageId, ThreadMessages.position)
            .where {
                (ThreadMessages.threadId eq threadId.value) and
                    (ThreadMessages.position greater afterPositionExclusive)
            }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .limit(limit)
            .map { row ->
                ThreadMessageLink(
                    threadId = threadId,
                    messageId = Conversation.Message.Id(row[ThreadMessages.messageId]),
                    position = row[ThreadMessages.position],
                )
            }
    }

    override suspend fun getMessagePage(
        threadId: Conversation.Thread.Id,
        afterPositionExclusive: Int,
        limit: Int,
    ): List<ThreadMessagePageEntry> = dbQuery {
        require(afterPositionExclusive >= -1)
        require(limit in 1..1_000)
        val rows = (ThreadMessages innerJoin Messages)
            .select(ThreadMessages.position, Messages.messageJson)
            .where {
                (ThreadMessages.threadId eq threadId.value) and
                    (ThreadMessages.position greater afterPositionExclusive)
            }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .limit(limit)
            .map { row -> row[ThreadMessages.position] to json.decodeFromString<Conversation.Message>(row[Messages.messageJson]) }
        val resolved = resolveCurrentMessageAuthors(rows.map { it.second })
        rows.zip(resolved) { (position, _), message -> ThreadMessagePageEntry(position, message) }
    }

    override suspend fun findMessage(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
    ): Conversation.Message? = dbQuery {
        (ThreadMessages innerJoin Messages)
            .select(Messages.messageJson)
            .where {
                (ThreadMessages.threadId eq threadId.value) and
                    (ThreadMessages.messageId eq messageId.value)
            }
            .singleOrNull()
            ?.get(Messages.messageJson)
            ?.let { json.decodeFromString<Conversation.Message>(it) }
            ?.let { resolveCurrentMessageAuthors(listOf(it)).single() }
    }

    override suspend fun findMessageByIdOrOriginal(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
    ): Conversation.Message? = dbQuery {
        TransactionManager.current().exec(
            """
            SELECT m.message_json
            FROM thread_messages tm
            JOIN messages m ON m.id = tm.message_id
            WHERE tm.thread_id = ? AND (
                tm.message_id = ? OR
                m.original_ids_json::jsonb @> jsonb_build_array(CAST(? AS text))
            )
            ORDER BY tm.position
            LIMIT 1
            """.trimIndent(),
            listOf(
                TextColumnType() to threadId.value,
                TextColumnType() to messageId.value,
                TextColumnType() to messageId.value,
            ),
            StatementType.SELECT,
        ) { rows ->
            if (!rows.next()) null
            else resolveCurrentMessageAuthors(listOf(json.decodeFromString<Conversation.Message>(rows.getString(1)))).single()
        }
    }

    override suspend fun copyWithChanges(
        sourceThreadId: Conversation.Thread.Id,
        targetThreadId: Conversation.Thread.Id,
        replacement: ThreadMessageReplacement?,
        excludedMessageIds: Set<Conversation.Message.Id>,
    ): Unit = dbQuery {
        require(replacement == null || replacement.sourceMessageId !in excludedMessageIds) {
            "A copied message cannot be both replaced and excluded"
        }
        check(ThreadMessages.selectAll().where { ThreadMessages.threadId eq targetThreadId.value }.empty()) {
            "Replacement thread must be empty"
        }
        TransactionManager.current().exec(
            COPY_THREAD_MESSAGES_SQL,
            listOf(
                TextColumnType() to targetThreadId.value,
                TextColumnType() to sourceThreadId.value,
            ),
        )

        val deleted = excludedMessageIds.asSequence()
            .map { it.value }
            .chunked(MUTATION_BATCH_SIZE)
            .sumOf { excludedIds ->
                ThreadMessages.deleteWhere {
                    (ThreadMessages.threadId eq targetThreadId.value) and
                        (ThreadMessages.messageId inList excludedIds)
                }
            }

        replacement?.let { change ->
            val updated = ThreadMessages.update({
                (ThreadMessages.threadId eq targetThreadId.value) and
                    (ThreadMessages.messageId eq change.sourceMessageId.value)
            }) {
                it[ThreadMessages.messageId] = change.replacementMessageId.value
            }
            check(updated == 1) {
                "Message ${change.sourceMessageId.value} is not in copied thread ${targetThreadId.value}"
            }
        }

        if (deleted > 0) {
            TransactionManager.current().exec(
                REINDEX_THREAD_MESSAGES_SQL,
                listOf(
                    TextColumnType() to targetThreadId.value,
                    TextColumnType() to targetThreadId.value,
                ),
            )
        }
    }

    override suspend fun hasAtLeastResponseReviewContinuations(
        threadId: Conversation.Thread.Id,
        turnId: String,
        requiredCount: Int,
    ): Boolean = newSuspendedTransaction(
        Dispatchers.IO,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        readOnly = true,
    ) {
        require(turnId.isNotBlank())
        require(requiredCount > 0)
        checkNotNull(TransactionManager.current().exec(
            RESPONSE_REVIEW_CONTINUATION_THRESHOLD_SQL,
            listOf(
                IntegerColumnType() to requiredCount,
                TextColumnType() to threadId.value,
                TextColumnType() to "$turnId:",
                IntegerColumnType() to requiredCount,
            ),
            StatementType.SELECT,
        ) { rows ->
            check(rows.next())
            rows.getBoolean("threshold_reached")
        })
    }

    override suspend fun compactionWindowHeaders(threadId: Conversation.Thread.Id): List<CompactionMessageHeader> =
        newSuspendedTransaction(Dispatchers.IO, transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            val bounds = readContextWindowBounds(threadId, throughMessageId = null) ?: return@newSuspendedTransaction emptyList()
            val rows = (ThreadMessages innerJoin Messages).select(ThreadMessages.messageId, Messages.compactionHeader)
                .where {
                    (ThreadMessages.threadId eq threadId.value) and
                        (ThreadMessages.position greaterEq bounds.startPosition) and
                        (ThreadMessages.position lessEq bounds.endPosition)
                }
                .orderBy(ThreadMessages.position, SortOrder.ASC)
                .map { row ->
                    val id = Conversation.Message.Id(row[ThreadMessages.messageId])
                    val header = row[Messages.compactionHeader]?.let { json.decodeFromString<CompactionMessageHeader>(it) }
                    check(header == null || header.id == id) { "Compaction header has a different message ID" }
                    id to header
                }
            // Imports written without the optional index can still be read with bounded memory.
            val missing = rows.filter { it.second == null }.map { it.first }.chunked(32).flatMap { ids ->
                readContextBodies(threadId, ids).map(CompactionMessageHeader::from)
            }.associateBy { it.id }
            val headers = rows.map { (id, header) -> header ?: missing.getValue(id) }
            val authors = resolveAuthors(headers.mapNotNull { it.author }.distinct())
            headers.map { header ->
                val current = authors[header.author]
                if (current == null || current == header.author) header else header.copy(author = current)
            }
        }

    override suspend fun compactionBodies(threadId: Conversation.Thread.Id, ids: List<Conversation.Message.Id>): List<Conversation.Message> = dbQuery {
        val found = readContextBodies(threadId, ids).associateBy { it.id }
        resolveCurrentMessageAuthors(ids.map(found::getValue))
    }

    override suspend fun recentVisibleMessageTextThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        limit: Int,
        maxCharacters: Int,
    ): List<RecentVisibleMessage> = newSuspendedTransaction(
        Dispatchers.IO,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        readOnly = true,
    ) {
        require(limit in 1..100)
        require(maxCharacters in 1..100_000)
        val newestFirst = TransactionManager.current().exec(
            RECENT_VISIBLE_MESSAGES_SQL,
            listOf(
                TextColumnType() to threadId.value,
                TextColumnType() to throughMessageId.value,
                IntegerColumnType() to maxCharacters,
                TextColumnType() to threadId.value,
                IntegerColumnType() to limit,
            ),
            StatementType.SELECT,
        ) { rows ->
            buildList {
                while (rows.next()) {
                    add(RecentVisibleMessage(
                        id = Conversation.Message.Id(rows.getString("message_id")),
                        role = Conversation.Message.Role.valueOf(rows.getString("role")),
                        text = rows.getString("message_text"),
                    ))
                }
            }
        }.orEmpty()
        newestFirst.asReversed()
    }

    override suspend fun invalidToolSequenceWindow(
        threadId: Conversation.Thread.Id,
    ): List<ToolSequenceMessageHeader> = newSuspendedTransaction(
        Dispatchers.IO,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        readOnly = true,
    ) {
        TransactionManager.current().exec(
            TOOL_SEQUENCE_VALIDATION_SQL,
            listOf(
                TextColumnType() to threadId.value,
                TextColumnType() to threadId.value,
            ),
            StatementType.SELECT,
        ) { rows ->
            buildList {
                while (rows.next()) {
                    fun ids(column: String): List<String> = (rows.getArray(column).array as Array<*>)
                        .map { value -> value as String }
                    add(
                        ToolSequenceMessageHeader(
                            position = rows.getInt("position"),
                            id = Conversation.Message.Id(rows.getString("message_id")),
                            role = Conversation.Message.Role.valueOf(rows.getString("role")),
                            facts = ToolSequenceFacts(
                                callIds = ids("tool_call_ids"),
                                resultIds = ids("tool_result_ids"),
                                providerManaged = rows.getBoolean("provider_managed_tool"),
                                toolResultsOnly = rows.getBoolean("tool_results_only"),
                                fullCompactionAnchor = rows.getBoolean("full_compaction_anchor"),
                            ),
                            requiresRepair = rows.getBoolean("requires_repair"),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    override suspend fun copyPrefixAndAddSuffix(
        sourceThreadId: Conversation.Thread.Id,
        targetThreadId: Conversation.Thread.Id,
        suffixStartPosition: Int,
        suffixMessageIds: List<Conversation.Message.Id>,
    ): Unit = dbQuery {
        require(suffixStartPosition >= 0)
        require(suffixMessageIds.distinct().size == suffixMessageIds.size) { "Replacement suffix has duplicate message IDs" }
        check(ThreadMessages.selectAll().where { ThreadMessages.threadId eq targetThreadId.value }.empty()) {
            "Replacement thread must be empty"
        }
        TransactionManager.current().exec(
            """
            INSERT INTO thread_messages(thread_id, message_id, position)
            SELECT ?, message_id, position
            FROM thread_messages
            WHERE thread_id = ? AND position < ?
            ORDER BY position
            """.trimIndent(),
            listOf(
                TextColumnType() to targetThreadId.value,
                TextColumnType() to sourceThreadId.value,
                IntegerColumnType() to suffixStartPosition,
            ),
        )
        ThreadMessages.batchInsert(suffixMessageIds.withIndex(), shouldReturnGeneratedValues = false) { indexed ->
            this[ThreadMessages.threadId] = targetThreadId.value
            this[ThreadMessages.messageId] = indexed.value.value
            this[ThreadMessages.position] = suffixStartPosition + indexed.index
        }
    }

    override suspend fun getContextMessagesByThread(
        threadId: Conversation.Thread.Id,
        nativeProvider: String?,
    ): ThreadContextMessages = newSuspendedTransaction(
        Dispatchers.IO, transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true,
    ) {
        readContextMessages(threadId, throughMessageId = null, nativeProvider)
    }

    override suspend fun getContextMessagesThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        nativeProvider: String?,
    ): ThreadContextMessages = newSuspendedTransaction(
        Dispatchers.IO, transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true,
    ) {
        readContextMessages(threadId, throughMessageId, nativeProvider)
    }

    private fun readContextMessages(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id?,
        nativeProvider: String?,
    ): ThreadContextMessages {
        val captured = readContextHeaders(threadId, throughMessageId)
        val checkpointIds = captured.headers.filter { it.metadata.hasCompaction }.map { it.id }
        readContextBodies(threadId, checkpointIds.filterNot(captured.bodies::containsKey)).forEach {
            captured.bodies[it.id] = it
        }
        val checkpoints = checkpointIds.map(captured.bodies::getValue)
        val required = selectModelHistoryMessageIds(
            captured.headers,
            checkpoints,
            nativeProvider,
        )
        return ThreadContextMessages(
            messages = materializeContextSelection(threadId, captured, required),
            sourceMessageCount = captured.sourceMessageCount,
            continuationState = checkpoints.asSequence()
                .flatMap { it.compactions().asSequence() }
                .lastOrNull { it.coversAllPrevious }
                ?.continuationState
                ?: Conversation.Message.ContentItem.ContextCompactionResult.ContinuationState(),
        )
    }

    private fun readContextHeaders(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id?,
    ): CapturedContextHeaders {
        val bounds = readContextWindowBounds(threadId, throughMessageId)
            ?: return CapturedContextHeaders(emptyList(), mutableMapOf(), 0)
        val headers = (ThreadMessages innerJoin Messages)
            .select(ThreadMessages.messageId, Messages.contextMetadataJson)
            .where {
                (ThreadMessages.threadId eq threadId.value) and
                    (ThreadMessages.position greaterEq bounds.startPosition) and
                    (ThreadMessages.position lessEq bounds.endPosition)
            }
            .orderBy(ThreadMessages.position, SortOrder.ASC)
            .map { row ->
                MessageContextHeader(
                    Conversation.Message.Id(row[ThreadMessages.messageId]),
                    json.decodeFromString<MessageContextMetadata>(row[Messages.contextMetadataJson]),
                )
            }
        return CapturedContextHeaders(headers, mutableMapOf(), bounds.endPosition + 1)
    }

    /** One model read begins at the latest full checkpoint at or before its captured end. */
    private fun readContextWindowBounds(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id?,
    ): ContextWindowBounds? {
        val endPosition = if (throughMessageId == null) {
            val maximum = ThreadMessages.position.max()
            ThreadMessages.select(maximum)
                .where { ThreadMessages.threadId eq threadId.value }
                .single()[maximum]
        } else {
            ThreadMessages.select(ThreadMessages.position)
                .where {
                    (ThreadMessages.threadId eq threadId.value) and
                        (ThreadMessages.messageId eq throughMessageId.value)
                }
                .singleOrNull()
                ?.get(ThreadMessages.position)
                ?: error("Message ${throughMessageId.value} is not in thread ${threadId.value}")
        } ?: return null
        val startPosition = (ThreadMessages innerJoin Messages)
            .select(ThreadMessages.position)
            .where {
                (ThreadMessages.threadId eq threadId.value) and
                    (ThreadMessages.position lessEq endPosition) and
                    (Messages.fullCompactionAnchor eq true)
            }
            .orderBy(ThreadMessages.position, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(ThreadMessages.position)
            ?: 0
        return ContextWindowBounds(startPosition, endPosition)
    }

    private fun materializeContextSelection(
        threadId: Conversation.Thread.Id,
        captured: CapturedContextHeaders,
        required: Set<Conversation.Message.Id>,
    ): List<Conversation.Message> {
        readContextBodies(threadId, required.filterNot(captured.bodies::containsKey)).forEach {
            captured.bodies[it.id] = it
        }
        val selected = captured.headers.filter { it.id in required }.map { header ->
            val message = captured.bodies[header.id] ?: error("Missing message in captured context: ${header.id.value}")
            check(message.id == header.id && MessageContextMetadata.from(message) == header.metadata) {
                "Message context metadata does not match its canonical body: ${header.id.value}"
            }
            message
        }
        return resolveCurrentMessageAuthors(selected)
    }

    private fun readContextBodies(threadId: Conversation.Thread.Id, ids: List<Conversation.Message.Id>): List<Conversation.Message> =
        ids.chunked(512).flatMap { chunk ->
            (ThreadMessages innerJoin Messages).select(Messages.messageJson)
                .where { (ThreadMessages.threadId eq threadId.value) and (ThreadMessages.messageId inList chunk.map { it.value }) }
                .map { json.decodeFromString<Conversation.Message>(it[Messages.messageJson]) }
        }

    override suspend fun deleteByThread(threadId: Conversation.Thread.Id): Unit = dbQuery {
        ThreadMessages.deleteWhere { ThreadMessages.threadId eq threadId.value }
    }

    private data class CapturedContextHeaders(
        val headers: List<MessageContextHeader>,
        val bodies: MutableMap<Conversation.Message.Id, Conversation.Message>,
        val sourceMessageCount: Int,
    )

    private data class ContextWindowBounds(
        val startPosition: Int,
        val endPosition: Int,
    )

    private companion object {
        const val MUTATION_BATCH_SIZE = 512

        val COPY_THREAD_MESSAGES_SQL = """
            INSERT INTO thread_messages(thread_id, message_id, position)
            SELECT ?, tm.message_id, tm.position
            FROM thread_messages tm
            WHERE tm.thread_id = ?
            ORDER BY tm.position
        """.trimIndent()

        val REINDEX_THREAD_MESSAGES_SQL = """
            WITH ordered AS (
                SELECT message_id,
                    (row_number() OVER (ORDER BY position) - 1)::integer AS new_position
                FROM thread_messages
                WHERE thread_id = ?
            )
            UPDATE thread_messages target
            SET position = ordered.new_position
            FROM ordered
            WHERE target.thread_id = ?
                AND target.message_id = ordered.message_id
                AND target.position <> ordered.new_position
        """.trimIndent()

        val RECENT_VISIBLE_MESSAGES_SQL = """
            WITH source AS (
                SELECT position
                FROM thread_messages
                WHERE thread_id = ? AND message_id = ?
            )
            SELECT tm.message_id, m.role, left(m.message_text, ?) AS message_text
            FROM thread_messages tm
            JOIN messages m ON m.id = tm.message_id
            CROSS JOIN source s
            WHERE tm.thread_id = ?
                AND tm.position <= s.position
                AND m.role IN ('USER', 'ASSISTANT')
                AND btrim(m.message_text) <> ''
            ORDER BY tm.position DESC
            LIMIT ?
        """.trimIndent()

        val RESPONSE_REVIEW_CONTINUATION_THRESHOLD_SQL = """
            SELECT count(*) >= ? AS threshold_reached
            FROM (
                SELECT 1
                FROM thread_messages tm
                JOIN messages m ON m.id = tm.message_id
                WHERE tm.thread_id = ?
                    AND m.role = 'ASSISTANT'
                    AND starts_with(tm.message_id, ?)
                    AND m.response_review_continuation
                LIMIT ?
            ) matching
        """.trimIndent()

        val TOOL_SEQUENCE_VALIDATION_SQL = """
            WITH boundary AS (
                SELECT COALESCE((
                    SELECT tm.position
                    FROM thread_messages tm
                    JOIN messages m ON m.id = tm.message_id
                    WHERE tm.thread_id = ? AND m.full_compaction_anchor
                    ORDER BY tm.position DESC
                    LIMIT 1
                ), -1) AS position
            ), history AS (
                SELECT tm.position, tm.message_id, m.role, m.tool_call_ids, m.tool_result_ids,
                    m.provider_managed_tool, m.tool_results_only, m.full_compaction_anchor
                FROM thread_messages tm
                JOIN messages m ON m.id = tm.message_id
                CROSS JOIN boundary b
                WHERE tm.thread_id = ? AND tm.position > b.position
            ), provider_calls AS (
                SELECT DISTINCT call_id
                FROM history h
                CROSS JOIN LATERAL unnest(h.tool_call_ids) AS call(call_id)
                WHERE h.provider_managed_tool
            ), classified AS (
                SELECT h.*,
                    h.provider_managed_tool OR (
                        h.tool_results_only AND NOT EXISTS (
                            SELECT 1
                            FROM unnest(h.tool_result_ids) AS result(call_id)
                            WHERE NOT EXISTS (
                                SELECT 1 FROM provider_calls p WHERE p.call_id = result.call_id
                            )
                        )
                    ) AS provider_row
                FROM history h
            ), ordinary AS (
                SELECT c.*,
                    lead(c.tool_result_ids) OVER (ORDER BY c.position) AS next_results,
                    lag(c.tool_call_ids) OVER (ORDER BY c.position) AS previous_calls
                FROM classified c
                WHERE NOT c.provider_row
            ), all_results AS (
                SELECT DISTINCT call_id
                FROM history h
                CROSS JOIN LATERAL unnest(h.tool_result_ids) AS result(call_id)
            ), violations AS (
                SELECT position
                FROM ordinary
                WHERE (cardinality(tool_call_ids) > 0 AND tool_call_ids IS DISTINCT FROM next_results)
                    OR (cardinality(tool_result_ids) > 0 AND tool_result_ids IS DISTINCT FROM previous_calls)
                UNION ALL
                SELECT h.position
                FROM history h
                CROSS JOIN LATERAL unnest(h.tool_call_ids) AS call(call_id)
                WHERE h.provider_managed_tool
                    AND NOT EXISTS (SELECT 1 FROM all_results r WHERE r.call_id = call.call_id)
            )
            SELECT h.position, h.message_id, h.role, h.tool_call_ids, h.tool_result_ids,
                h.provider_managed_tool, h.tool_results_only, h.full_compaction_anchor,
                h.position IN (SELECT position FROM violations) AS requires_repair
            FROM history h
            WHERE EXISTS (SELECT 1 FROM violations)
            ORDER BY h.position
        """.trimIndent()
    }
}
