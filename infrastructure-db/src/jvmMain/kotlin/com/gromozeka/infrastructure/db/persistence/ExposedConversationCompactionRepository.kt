package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.ToolSequenceFacts
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.infrastructure.db.persistence.tables.Messages
import com.gromozeka.infrastructure.db.persistence.tables.ThreadMessages
import com.gromozeka.infrastructure.db.persistence.tables.Threads
import kotlinx.serialization.decodeFromString
import com.gromozeka.domain.service.ConversationRuntimeSchedulingState
import com.gromozeka.domain.service.ProactiveCompaction
import com.gromozeka.domain.model.compactions
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedConversationCompactionRepository(
    private val json: Json,
) : ConversationCompactionRepository {
    override suspend fun commitIfCurrent(
        expectedThreadId: Conversation.Thread.Id,
        compactionMessages: List<Conversation.Message>,
        newThread: Conversation.Thread,
        insertionAnchorId: Conversation.Message.Id,
        preparedCompactionId: String?,
    ): Boolean = dbQuery {
        require(compactionMessages.isNotEmpty()) { "Compaction commit requires a checkpoint" }
        val compactionMessage = compactionMessages.first()
        require(compactionMessages.map { it.id }.distinct().size == compactionMessages.size) {
            "Compaction commit contains duplicate message IDs"
        }
        require(compactionMessages.all { it.conversationId == compactionMessage.conversationId }) {
            "Compaction commit messages belong to different conversations"
        }
        require(compactionMessages.drop(1).all { it.role == Conversation.Message.Role.RUNTIME }) {
            "Only Runtime continuation messages may follow a compaction checkpoint"
        }
        // Lock Runtime before Conversation, matching collaboration's admission order.
        // Stop and COW publication have one linearization point, not a check-then-write race.
        val preparedState = if (preparedCompactionId != null) {
            require(preparedCompactionId == compactionMessage.id.value)
            val state = TransactionManager.current().exec(
                "SELECT scheduling::text FROM conversation_runtime_records WHERE conversation_id = ? FOR UPDATE",
                listOf(TextColumnType() to compactionMessage.conversationId.value),
            ) { rows -> if (rows.next()) json.decodeFromString<ConversationRuntimeSchedulingState>(rows.getString(1)) else null }
                ?: return@dbQuery false
            val prepared = state.proactiveCompaction
            if (prepared?.id != preparedCompactionId || prepared.status != ProactiveCompaction.Status.READY) return@dbQuery false
            val captured = requireNotNull(prepared.checkpoint).compactions().single()
            val actual = compactionMessage.compactions().single()
            check(captured.payload == actual.payload && captured.sourceMessageIds == actual.sourceMessageIds &&
                captured.sourceBoundaryMessageId == actual.sourceBoundaryMessageId && captured.coverage == actual.coverage &&
                captured.strategy == actual.strategy && captured.origin == actual.origin && captured.providerScope == actual.providerScope &&
                captured.continuationState == actual.continuationState) {
                "Prepared checkpoint does not match the committed compaction"
            }
            state
        } else null
        require(newThread.conversationId == compactionMessage.conversationId && newThread.originalThread == expectedThreadId) {
            "Compaction thread must derive from the captured conversation history"
        }
        val updated = Conversations.update({
            (Conversations.id eq compactionMessage.conversationId.value) and
                (Conversations.currentThreadId eq expectedThreadId.value)
        }) {
            it[currentThreadId] = newThread.id.value
            it[updatedAt] = newThread.updatedAt
        }
        if (updated != 1) return@dbQuery false

        // Clone links in SQL under the Conversation CAS lock. The archive can be arbitrarily long;
        // appends accepted before this transaction survive without materializing every message ID.
        val anchor = ThreadMessages.select(ThreadMessages.position)
            .where {
                (ThreadMessages.threadId eq expectedThreadId.value) and
                    (ThreadMessages.messageId eq insertionAnchorId.value)
            }
            .singleOrNull()
            ?.get(ThreadMessages.position)
            ?: error("Compaction insertion anchor is missing from the captured thread")
        val lastTurnNumber = Threads.selectAll().where { Threads.id eq expectedThreadId.value }
            .single()[Threads.lastTurnNumber]

        Messages.batchInsert(compactionMessages, shouldReturnGeneratedValues = false) { message ->
            val toolSequence = ToolSequenceFacts.from(message)
            this[Messages.id] = message.id.value
            this[Messages.conversationId] = message.conversationId.value
            this[Messages.originalIdsJson] = json.encodeToString(message.originalIds.map { id -> id.value })
            this[Messages.replyToId] = message.replyTo?.value
            this[Messages.role] = message.role.name
            this[Messages.createdAt] = message.createdAt
            this[Messages.searchText] = message.searchText()
            this[Messages.messageText] = message.searchText(includeToolResults = false)
            this[Messages.compactionHeader] = Json.encodeToString(CompactionMessageHeader.from(message))
            this[Messages.toolCallIds] = toolSequence.callIds
            this[Messages.toolResultIds] = toolSequence.resultIds
            this[Messages.providerManagedTool] = toolSequence.providerManaged
            this[Messages.toolResultsOnly] = toolSequence.toolResultsOnly
            this[Messages.fullCompactionAnchor] = toolSequence.fullCompactionAnchor
            this[Messages.responseReviewContinuation] = message.isResponseReviewContinuation()
            this[Messages.contextMetadataJson] = message.contextMetadataJson(json)
            this[Messages.messageJson] = json.encodeToString(message)
        }
        Threads.insert {
            it[id] = newThread.id.value
            it[conversationId] = newThread.conversationId.value
            it[originalThreadId] = newThread.originalThread?.value
            it[Threads.lastTurnNumber] = lastTurnNumber
            it[createdAt] = newThread.createdAt
            it[updatedAt] = newThread.updatedAt
        }
        TransactionManager.current().exec(
            """
            INSERT INTO thread_messages(thread_id, message_id, position)
            SELECT ?, message_id, CASE WHEN position <= ? THEN position ELSE position + ? END
            FROM thread_messages
            WHERE thread_id = ?
            ORDER BY position
            """.trimIndent(),
            listOf(
                TextColumnType() to newThread.id.value,
                org.jetbrains.exposed.v1.core.IntegerColumnType() to anchor,
                org.jetbrains.exposed.v1.core.IntegerColumnType() to compactionMessages.size,
                TextColumnType() to expectedThreadId.value,
            ),
        )
        ThreadMessages.batchInsert(compactionMessages.withIndex(), shouldReturnGeneratedValues = false) { indexed ->
            this[ThreadMessages.threadId] = newThread.id.value
            this[ThreadMessages.messageId] = indexed.value.id.value
            this[ThreadMessages.position] = anchor + indexed.index + 1
        }
        if (preparedState != null) {
            val prepared = requireNotNull(preparedState.proactiveCompaction)
            val completed = preparedState.updateProactiveCompaction(prepared,
                prepared.copy(status = ProactiveCompaction.Status.APPLIED, checkpoint = null, updatedAt = newThread.createdAt)).state.finishIfIdle().state
            val ready = completed.readyWorkItem()
            val readiness = if (ready == null) "ready_task_id = NULL, ready_at = NULL" else "ready_task_id = ?, ready_at = ?::timestamptz"
            TransactionManager.current().exec(
                "UPDATE conversation_runtime_records SET scheduling = ?::jsonb, revision = revision + 1, $readiness WHERE conversation_id = ?",
                buildList {
                    add(TextColumnType() to json.encodeToString(completed))
                    if (ready != null) {
                        add(TextColumnType() to ready.taskId.value)
                        add(TextColumnType() to ready.createdAt.toString())
                    }
                    add(TextColumnType() to compactionMessage.conversationId.value)
                },
            )
        }
        true
    }
}
