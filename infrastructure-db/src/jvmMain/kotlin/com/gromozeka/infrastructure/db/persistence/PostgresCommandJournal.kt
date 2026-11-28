package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

@Service
@ConditionalOnProperty(name = ["gromozeka.command-journal.enabled"], havingValue = "true")
internal class PostgresCommandJournal(
    private val dataSource: DataSource,
) : CommandJournal, CommandJournal.Reader {
    override suspend fun admit(
        entry: CommandJournal.Entry,
        rootCall: CommandJournal.Call,
        content: List<CommandJournal.Content>,
    ): CommandJournal.Entry = transaction { connection ->
        require(rootCall.entryId == entry.id && rootCall.parentId == null) {
            "A Command Journal entry requires its root call"
        }
        requireContentOwners(entry.id, rootCall.id, null, content)
        val existing = connection.findEntry(entry.id, forUpdate = true)
        if (existing != null) {
            require(existing == entry) { "Command Journal entry identity cannot be rebound" }
            require(connection.findCall(rootCall.id) == rootCall) {
                "Command Journal root call identity cannot be rebound"
            }
            connection.storeContent(content)
            return@transaction existing
        }
        connection.insert(entry)
        val storedEntry = requireNotNull(connection.findEntry(entry.id, forUpdate = true))
        require(storedEntry == entry) { "Command Journal entry identity cannot be rebound" }
        connection.insert(rootCall)
        require(connection.findCall(rootCall.id) == rootCall) {
            "Command Journal root call identity cannot be rebound"
        }
        connection.storeContent(content)
        storedEntry
    }

    override suspend fun append(
        call: CommandJournal.Call,
        content: List<CommandJournal.Content>,
    ): CommandJournal.Call = transaction { connection ->
        requireNotNull(connection.findEntry(call.entryId, forUpdate = true)) {
            "Command Journal entry is missing: ${call.entryId.value}"
        }
        call.parentId?.let { parentId ->
            val parent = requireNotNull(connection.findCall(parentId)) {
                "Command Journal parent call is missing: ${parentId.value}"
            }
            require(parent.entryId == call.entryId) { "Command Journal parent call belongs to another entry" }
        }
        requireContentOwners(call.entryId, call.id, null, content)
        val existing = connection.findCall(call.id)
        if (existing != null) {
            require(existing == call) { "Command Journal call identity cannot be rebound" }
            connection.storeContent(content)
            return@transaction existing
        }
        connection.insert(call)
        val storedCall = requireNotNull(connection.findCall(call.id))
        require(storedCall == call) { "Command Journal call identity cannot be rebound" }
        connection.storeContent(content)
        storedCall
    }

    override suspend fun append(
        event: CommandJournal.Event,
        content: List<CommandJournal.Content>,
    ): CommandJournal.Event = transaction { connection ->
        requireNotNull(connection.findEntry(event.entryId, forUpdate = true)) {
            "Command Journal entry is missing: ${event.entryId.value}"
        }
        val call = requireNotNull(connection.findCall(event.callId)) {
            "Command Journal call is missing: ${event.callId.value}"
        }
        require(call.entryId == event.entryId) { "Command Journal event call belongs to another entry" }
        requireContentOwners(event.entryId, event.callId, event.id, content)
        val existing = connection.findEvent(event.entryId, event.sourceKey)
        if (existing != null) {
            require(existing == event) { "Command Journal event source identity cannot be rebound" }
            connection.storeContent(content)
            return@transaction existing
        }
        connection.insert(event)
        val storedEvent = requireNotNull(connection.findEvent(event.entryId, event.sourceKey))
        require(storedEvent == event) { "Command Journal event source identity cannot be rebound" }
        connection.storeContent(content)
        storedEvent
    }

    override suspend fun findEntry(id: CommandJournal.Entry.Id): CommandJournal.Entry? = io {
        dataSource.connection.use { it.findEntry(id, forUpdate = false) }
    }

    override suspend fun findEntryBySource(
        conversationId: Conversation.Id,
        sourceToolCallId: ContentItem.ToolCall.Id,
    ): CommandJournal.Entry? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM command_journal_entries WHERE conversation_id=? AND source_tool_call_id=?"
            ).use { statement ->
                statement.setString(1, conversationId.value)
                statement.setString(2, sourceToolCallId.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.entry() else null }
            }
        }
    }

    override suspend fun findCallBySource(
        conversationId: Conversation.Id,
        sourceToolCallId: ContentItem.ToolCall.Id,
    ): CommandJournal.Call? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT call.*
                FROM command_journal_calls call
                JOIN command_journal_entries entry ON entry.id=call.entry_id
                WHERE entry.conversation_id=? AND call.source_tool_call_id=?
                ORDER BY call.id
                LIMIT 2
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, conversationId.value)
                statement.setString(2, sourceToolCallId.value)
                statement.executeQuery().use { rows ->
                    val matches = buildList { while (rows.next()) add(rows.call()) }
                    check(matches.size <= 1) {
                        "Command Journal source tool call is not unique in conversation ${conversationId.value}"
                    }
                    matches.singleOrNull()
                }
            }
        }
    }

    override suspend fun findCallByCommandTask(commandTaskId: CommandTask.Id): CommandJournal.Call? = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT call.*
                FROM command_journal_calls call
                JOIN (
                    SELECT event.call_id, min(event.append_sequence) AS first_sequence
                    FROM command_journal_events event
                    WHERE event.command_task_id=?
                    GROUP BY event.call_id
                    ORDER BY first_sequence
                    LIMIT 2
                ) mapping ON mapping.call_id=call.id
                ORDER BY mapping.first_sequence
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, commandTaskId.value)
                statement.executeQuery().use { rows ->
                    val matches = buildList { while (rows.next()) add(rows.call()) }
                    check(matches.size <= 1) {
                        "Command Journal task ${commandTaskId.value} is bound to more than one call"
                    }
                    matches.singleOrNull()
                }
            }
        }
    }

    override suspend fun calls(entryId: CommandJournal.Entry.Id): List<CommandJournal.Call> = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM command_journal_calls WHERE entry_id=? ORDER BY created_at,id"
            ).use { statement ->
                statement.setString(1, entryId.value)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.call()) } }
            }
        }
    }

    override suspend fun events(entryId: CommandJournal.Entry.Id): List<CommandJournal.Event> = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM command_journal_events WHERE entry_id=? ORDER BY append_sequence"
            ).use { statement ->
                statement.setString(1, entryId.value)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.event()) } }
            }
        }
    }

    override suspend fun content(entryId: CommandJournal.Entry.Id): List<CommandJournal.Content> = io {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT link.*, content.text_content, content.size_bytes
                FROM command_journal_content_links link
                JOIN command_journal_contents content ON content.id=link.content_id
                WHERE link.entry_id=?
                ORDER BY link.call_id,link.event_id NULLS FIRST,link.kind,link.sequence,link.id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, entryId.value)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.content()) } }
            }
        }
    }

    override suspend fun search(query: CommandJournal.SearchQuery): CommandJournal.SearchPage = io {
        dataSource.connection.use { connection ->
            val conditions = mutableListOf("entry.project_id=?")
            val parameters = mutableListOf<Any>(query.projectId.value)
            fun add(sql: String, vararg values: Any) {
                conditions += sql
                parameters.addAll(values)
            }
            query.conversationId?.let { add("entry.conversation_id=?", it.value) }
            query.actorUserId?.let { add("entry.actor_user_id=?", it.value) }
            query.agentDefinitionId?.let { add("entry.agent_definition_id=?", it.value) }
            query.workerId?.let {
                add(
                    "EXISTS (SELECT 1 FROM command_journal_calls worker_call WHERE worker_call.entry_id=entry.id AND worker_call.worker_id=?)",
                    it.value,
                )
            }
            query.origin?.let { add("entry.origin=?", it.name) }
            query.toolName?.let {
                add(
                    "EXISTS (SELECT 1 FROM command_journal_calls tool_call WHERE tool_call.entry_id=entry.id AND tool_call.tool_name=?)",
                    it,
                )
            }
            query.eventKind?.let { add("latest_event.kind=?", it.name) }
            query.text?.lowercase()?.let { text ->
                add(
                    """
                    (
                        POSITION(? IN LOWER(root_call.tool_name)) > 0 OR
                        EXISTS (
                            SELECT 1 FROM command_journal_content_links text_link
                            JOIN command_journal_contents text_content ON text_content.id=text_link.content_id
                            WHERE text_link.entry_id=entry.id
                              AND POSITION(? IN LOWER(text_content.text_content)) > 0
                        ) OR
                        EXISTS (
                            SELECT 1 FROM command_journal_events text_event
                            WHERE text_event.entry_id=entry.id
                              AND text_event.status_message IS NOT NULL
                              AND POSITION(? IN LOWER(text_event.status_message)) > 0
                        )
                    )
                    """.trimIndent(),
                    text,
                    text,
                    text,
                )
            }
            query.from?.let { add("entry.created_at>=?", it) }
            query.to?.let { add("entry.created_at<=?", it) }
            query.before?.let {
                add(
                    "(entry.created_at<? OR (entry.created_at=? AND entry.id<?))",
                    it.createdAt,
                    it.createdAt,
                    it.entryId.value,
                )
            }
            val sql = """
                SELECT
                    entry.id AS entry_id,
                    entry.origin AS entry_origin,
                    entry.actor_user_id AS entry_actor_user_id,
                    entry.project_id AS entry_project_id,
                    entry.conversation_id AS entry_conversation_id,
                    entry.thread_id AS entry_thread_id,
                    entry.agent_definition_id AS entry_agent_definition_id,
                    entry.source_runtime_task_id AS entry_source_runtime_task_id,
                    entry.source_tool_call_id AS entry_source_tool_call_id,
                    entry.parent_entry_id AS entry_parent_entry_id,
                    entry.created_at AS entry_created_at,
                    root_call.id AS root_id,
                    root_call.entry_id AS root_entry_id,
                    root_call.parent_id AS root_parent_id,
                    root_call.source_tool_call_id AS root_source_tool_call_id,
                    root_call.kind AS root_kind,
                    root_call.tool_name AS root_tool_name,
                    root_call.worker_id AS root_worker_id,
                    root_call.workspace_id AS root_workspace_id,
                    root_call.workspace_mount_id AS root_workspace_mount_id,
                    root_call.workspace_root_path AS root_workspace_root_path,
                    root_call.working_directory AS root_working_directory,
                    root_call.created_at AS root_created_at,
                    latest_event.id AS latest_id,
                    latest_event.entry_id AS latest_entry_id,
                    latest_event.call_id AS latest_call_id,
                    latest_event.source_key AS latest_source_key,
                    latest_event.kind AS latest_kind,
                    latest_event.occurred_at AS latest_occurred_at,
                    latest_event.command_task_id AS latest_command_task_id,
                    latest_event.status_message AS latest_status_message,
                    latest_event.exit_code AS latest_exit_code,
                    (SELECT count(*) FROM command_journal_calls count_call WHERE count_call.entry_id=entry.id) AS call_count,
                    (SELECT count(*) FROM command_journal_events count_event WHERE count_event.entry_id=entry.id) AS event_count,
                    (
                        SELECT COALESCE(sum(content.size_bytes), 0)
                        FROM command_journal_content_links content_link
                        JOIN command_journal_contents content ON content.id=content_link.content_id
                        WHERE content_link.entry_id=entry.id
                    ) AS linked_content_bytes,
                    (
                        SELECT LEFT(content.text_content, 1000)
                        FROM command_journal_content_links preview_link
                        JOIN command_journal_contents content ON content.id=preview_link.content_id
                        WHERE preview_link.entry_id=entry.id
                          AND preview_link.kind IN ('COMMAND','PYTHON_SOURCE','REPORTED_OUTPUT','OUTPUT')
                        ORDER BY CASE preview_link.kind
                            WHEN 'COMMAND' THEN 0
                            WHEN 'PYTHON_SOURCE' THEN 1
                            WHEN 'REPORTED_OUTPUT' THEN 2
                            ELSE 3
                        END, preview_link.id
                        LIMIT 1
                    ) AS content_excerpt
                FROM command_journal_entries entry
                JOIN LATERAL (
                    SELECT * FROM command_journal_calls root
                    WHERE root.entry_id=entry.id AND root.parent_id IS NULL
                    ORDER BY root.created_at,root.id LIMIT 1
                ) root_call ON TRUE
                LEFT JOIN LATERAL (
                    SELECT * FROM command_journal_events latest
                    WHERE latest.entry_id=entry.id
                    ORDER BY latest.append_sequence DESC LIMIT 1
                ) latest_event ON TRUE
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY entry.created_at DESC,entry.id DESC
                LIMIT ?
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.bind(parameters + (query.limit + 1))
                statement.executeQuery().use { rows ->
                    val values = buildList {
                        while (rows.next()) {
                            add(
                                CommandJournal.Summary(
                                    entry = rows.entry("entry_"),
                                    rootCall = rows.call("root_"),
                                    latestEvent = rows.getString("latest_id")?.let { rows.event("latest_") },
                                    callCount = rows.getLong("call_count"),
                                    eventCount = rows.getLong("event_count"),
                                    linkedContentBytes = rows.getLong("linked_content_bytes"),
                                    excerpt = rows.getString("content_excerpt"),
                                )
                            )
                        }
                    }
                    val page = values.take(query.limit)
                    CommandJournal.SearchPage(
                        entries = page,
                        nextCursor = if (values.size > query.limit) page.lastOrNull()?.entry?.let {
                            CommandJournal.Cursor(it.createdAt, it.id)
                        } else null,
                    )
                }
            }
        }
    }

    override suspend fun get(
        projectId: Project.Id,
        entryId: CommandJournal.Entry.Id,
        callLimit: Int,
        eventLimit: Int,
    ): CommandJournal.Snapshot? = io {
        require(callLimit in 1..200) { "Command Journal call limit must be between 1 and 200" }
        require(eventLimit in 1..500) { "Command Journal event limit must be between 1 and 500" }
        require(callLimit + eventLimit <= 500) { "Command Journal snapshot may request at most 500 records" }
        dataSource.connection.use { connection ->
            val entry = connection.findEntry(projectId, entryId) ?: return@use null
            val calls = connection.prepareStatement(
                "SELECT * FROM command_journal_calls WHERE entry_id=? ORDER BY created_at,id LIMIT ?"
            ).use { statement ->
                statement.setString(1, entryId.value)
                statement.setInt(2, callLimit + 1)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.call()) } }
            }
            val events = connection.prepareStatement(
                "SELECT * FROM command_journal_events WHERE entry_id=? ORDER BY append_sequence LIMIT ?"
            ).use { statement ->
                statement.setString(1, entryId.value)
                statement.setInt(2, eventLimit + 1)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.event()) } }
            }
            val callCount = connection.count("command_journal_calls", entryId)
            val eventCount = connection.count("command_journal_events", entryId)
            CommandJournal.Snapshot(
                entry = entry,
                calls = calls.take(callLimit),
                events = events.take(eventLimit),
                callCount = callCount,
                eventCount = eventCount,
                callsTruncated = calls.size > callLimit,
                eventsTruncated = events.size > eventLimit,
            )
        }
    }

    override suspend fun readContent(query: CommandJournal.ContentQuery): CommandJournal.ContentPage? = io {
        dataSource.connection.use { connection ->
            connection.findEntry(query.projectId, query.entryId) ?: return@use null
            val cursor = query.cursor
            val conditions = mutableListOf("link.entry_id=?", "link.id>=?")
            val parameters = mutableListOf<Any>(query.entryId.value, cursor?.linkId ?: 1L)
            query.callId?.let {
                conditions += "link.call_id=?"
                parameters += it.value
            }
            query.eventId?.let {
                conditions += "link.event_id=?"
                parameters += it.value
            }
            if (query.kinds.isNotEmpty()) {
                conditions += "link.kind IN (${query.kinds.joinToString(",") { "?" }})"
                parameters.addAll(query.kinds.map { it.name })
            }
            val sql = """
                WITH selected AS (
                    SELECT
                        link.id AS link_id,
                        link.entry_id,
                        link.call_id,
                        link.event_id,
                        link.content_id,
                        link.kind,
                        link.sequence,
                        content.text_content,
                        content.size_bytes,
                        link.start_byte,
                        link.end_byte,
                        link.total_bytes,
                        link.truncated_before,
                        link.truncated_after,
                        link.created_at,
                        CASE WHEN link.id=? THEN ? ELSE 0 END AS character_start
                    FROM command_journal_content_links link
                    JOIN command_journal_contents content ON content.id=link.content_id
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY link.id
                    LIMIT ?
                )
                SELECT
                    selected.*,
                    SUBSTRING(text_content FROM character_start + 1 FOR ?) AS visible_text,
                    CHAR_LENGTH(text_content) AS total_characters,
                    character_start + CHAR_LENGTH(
                        SUBSTRING(text_content FROM character_start + 1 FOR ?)
                    ) AS character_end,
                    OCTET_LENGTH(SUBSTRING(text_content FROM 1 FOR character_start)) AS skipped_bytes,
                    OCTET_LENGTH(
                        SUBSTRING(text_content FROM character_start + 1 FOR ?)
                    ) AS visible_bytes
                FROM selected
                ORDER BY link_id
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, cursor?.linkId ?: -1L)
                statement.setInt(2, cursor?.characterOffset ?: 0)
                val bound = parameters + (query.limit + 2)
                statement.bind(bound, startIndex = 3)
                val maximumIndex = 3 + bound.size
                statement.setInt(maximumIndex, query.maxCharactersPerItem)
                statement.setInt(maximumIndex + 1, query.maxCharactersPerItem)
                statement.setInt(maximumIndex + 2, query.maxCharactersPerItem)
                statement.executeQuery().use { rows ->
                    val values = buildList {
                        while (rows.next()) {
                            val characterStart = rows.getInt("character_start")
                            val characterEnd = rows.getInt("character_end")
                            val totalCharacters = rows.getInt("total_characters")
                            val sourceStart = rows.nullableLong("start_byte")
                            val visibleStart = sourceStart?.plus(rows.getLong("skipped_bytes"))
                            val visibleEnd = visibleStart?.plus(rows.getLong("visible_bytes"))
                            add(
                                CommandJournal.ContentRecord(
                                    linkId = rows.getLong("link_id"),
                                    entryId = CommandJournal.Entry.Id(rows.getString("entry_id")),
                                    callId = CommandJournal.Call.Id(rows.getString("call_id")),
                                    eventId = rows.getString("event_id")?.let(CommandJournal.Event::Id),
                                    contentId = CommandJournal.Content.Id(rows.getString("content_id")),
                                    kind = CommandJournal.Content.Kind.valueOf(rows.getString("kind")),
                                    sequence = rows.getInt("sequence"),
                                    text = rows.getString("visible_text"),
                                    sizeBytes = rows.getInt("size_bytes"),
                                    characterStart = characterStart,
                                    characterEnd = characterEnd,
                                    totalCharacters = totalCharacters,
                                    textTruncated = characterStart > 0 || characterEnd < totalCharacters,
                                    startByte = visibleStart,
                                    endByte = visibleEnd,
                                    totalBytes = rows.nullableLong("total_bytes"),
                                    truncatedBefore = rows.getBoolean("truncated_before") || characterStart > 0,
                                    truncatedAfter = rows.getBoolean("truncated_after") || characterEnd < totalCharacters,
                                    createdAt = rows.getTimestamp("created_at").instant(),
                                )
                            )
                        }
                    }
                    val page = mutableListOf<CommandJournal.ContentRecord>()
                    var nextCursor: CommandJournal.ContentCursor? = null
                    for (value in values) {
                        if (cursor != null && value.linkId == cursor.linkId) {
                            require(value.characterStart <= value.totalCharacters) {
                                "Command Journal content cursor exceeds link ${value.linkId}"
                            }
                        }
                        if (value.characterStart == value.totalCharacters) continue
                        if (page.size == query.limit) {
                            nextCursor = CommandJournal.ContentCursor(value.linkId, value.characterStart)
                            break
                        }
                        page += value
                        if (value.characterEnd < value.totalCharacters) {
                            nextCursor = CommandJournal.ContentCursor(value.linkId, value.characterEnd)
                            break
                        }
                    }
                    CommandJournal.ContentPage(items = page, nextCursor = nextCursor)
                }
            }
        }
    }

    override suspend fun statistics(projectId: Project.Id): CommandJournal.Statistics = io {
        dataSource.connection.use { connection ->
            val logical = connection.prepareStatement(
                """
                WITH project_entries AS (
                    SELECT id FROM command_journal_entries WHERE project_id=?
                ), project_contents AS (
                    SELECT DISTINCT link.content_id
                    FROM command_journal_content_links link
                    JOIN project_entries entry ON entry.id=link.entry_id
                )
                SELECT
                    (SELECT count(*) FROM project_entries) AS entry_count,
                    (SELECT count(*) FROM command_journal_calls call JOIN project_entries entry ON entry.id=call.entry_id) AS call_count,
                    (SELECT count(*) FROM command_journal_events event JOIN project_entries entry ON entry.id=event.entry_id) AS event_count,
                    (SELECT count(*) FROM command_journal_content_links link JOIN project_entries entry ON entry.id=link.entry_id) AS content_link_count,
                    (SELECT count(*) FROM project_contents) AS unique_content_count,
                    (
                        SELECT COALESCE(sum(content.size_bytes),0)
                        FROM command_journal_contents content
                        JOIN project_contents selected ON selected.content_id=content.id
                    ) AS unique_content_bytes
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, projectId.value)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    longArrayOf(
                        rows.getLong("entry_count"),
                        rows.getLong("call_count"),
                        rows.getLong("event_count"),
                        rows.getLong("content_link_count"),
                        rows.getLong("unique_content_count"),
                        rows.getLong("unique_content_bytes"),
                    )
                }
            }
            val journalStorageBytes = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COALESCE(sum(pg_total_relation_size(to_regclass(current_schema() || '.' || relation_name))),0)
                    FROM unnest(ARRAY[
                        'command_journal_entries',
                        'command_journal_calls',
                        'command_journal_events',
                        'command_journal_contents',
                        'command_journal_content_links'
                    ]) relation_name
                    """.trimIndent()
                ).use { rows -> check(rows.next()); rows.getLong(1) }
            }
            val databaseBytes = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_database_size(current_database())").use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
            CommandJournal.Statistics(
                projectId = projectId,
                entryCount = logical[0],
                callCount = logical[1],
                eventCount = logical[2],
                contentLinkCount = logical[3],
                uniqueContentCount = logical[4],
                uniqueContentBytes = logical[5],
                journalStorageBytes = journalStorageBytes,
                databaseBytes = databaseBytes,
                measuredAt = Clock.System.now(),
            )
        }
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T = io {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (error: Throwable) {
                runCatching(connection::rollback).onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (failure: SQLException) {
            throw CommandJournal.StorageException(failure)
        }
    }

    private fun requireContentOwners(
        entryId: CommandJournal.Entry.Id,
        callId: CommandJournal.Call.Id,
        eventId: CommandJournal.Event.Id?,
        content: List<CommandJournal.Content>,
    ) {
        content.forEach {
            require(it.entryId == entryId && it.callId == callId && it.eventId == eventId) {
                "Command Journal content belongs to another owner"
            }
        }
    }

    private fun Connection.insert(entry: CommandJournal.Entry) {
        prepareStatement(
            """
            INSERT INTO command_journal_entries(
                id,origin,actor_user_id,project_id,conversation_id,thread_id,agent_definition_id,
                source_runtime_task_id,source_tool_call_id,parent_entry_id,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entry.id.value)
            statement.setString(2, entry.origin.name)
            statement.setString(3, entry.actorUserId.value)
            statement.setString(4, entry.projectId.value)
            statement.setString(5, entry.conversationId.value)
            statement.setString(6, entry.threadId.value)
            statement.setString(7, entry.agentDefinitionId?.value)
            statement.setString(8, entry.sourceRuntimeTaskId?.value)
            statement.setString(9, entry.sourceToolCallId?.value)
            statement.setString(10, entry.parentEntryId?.value)
            statement.setTimestamp(11, entry.createdAt.sql())
            statement.executeUpdate()
        }
    }

    private fun Connection.insert(call: CommandJournal.Call) {
        prepareStatement(
            """
            INSERT INTO command_journal_calls(
                id,entry_id,parent_id,source_tool_call_id,kind,tool_name,worker_id,workspace_id,workspace_mount_id,
                workspace_root_path,working_directory,created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, call.id.value)
            statement.setString(2, call.entryId.value)
            statement.setString(3, call.parentId?.value)
            statement.setString(4, call.sourceToolCallId?.value)
            statement.setString(5, call.kind.name)
            statement.setString(6, call.toolName)
            statement.setString(7, call.target.workerId?.value)
            statement.setString(8, call.target.workspaceId?.value)
            statement.setString(9, call.target.workspaceMountId?.value)
            statement.setString(10, call.target.workspaceRootPath)
            statement.setString(11, call.target.workingDirectory)
            statement.setTimestamp(12, call.createdAt.sql())
            statement.executeUpdate()
        }
    }

    private fun Connection.insert(event: CommandJournal.Event) {
        prepareStatement(
            """
            INSERT INTO command_journal_events(
                id,entry_id,call_id,source_key,kind,occurred_at,command_task_id,status_message,exit_code
            ) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, event.id.value)
            statement.setString(2, event.entryId.value)
            statement.setString(3, event.callId.value)
            statement.setString(4, event.sourceKey)
            statement.setString(5, event.kind.name)
            statement.setTimestamp(6, event.occurredAt.sql())
            statement.setString(7, event.commandTaskId?.value)
            statement.setString(8, event.statusMessage)
            event.exitCode?.let { statement.setInt(9, it) } ?: statement.setNull(9, java.sql.Types.INTEGER)
            statement.executeUpdate()
        }
    }

    private fun Connection.storeContent(items: List<CommandJournal.Content>) {
        items.forEach { item ->
            prepareStatement(
                """
                INSERT INTO command_journal_contents(id,text_content,size_bytes,created_at)
                VALUES (?,?,?,?) ON CONFLICT (id) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, item.id.value)
                statement.setString(2, item.text)
                statement.setInt(3, item.sizeBytes)
                statement.setTimestamp(4, item.createdAt.sql())
                statement.executeUpdate()
            }
            prepareStatement("SELECT text_content,size_bytes FROM command_journal_contents WHERE id=?").use { statement ->
                statement.setString(1, item.id.value)
                statement.executeQuery().use { rows ->
                    check(rows.next() && rows.getString(1) == item.text && rows.getInt(2) == item.sizeBytes) {
                        "Command Journal content hash collision"
                    }
                }
            }
            prepareStatement(
                """
                INSERT INTO command_journal_content_links(
                    entry_id,call_id,event_id,content_id,kind,sequence,start_byte,end_byte,total_bytes,
                    truncated_before,truncated_after,created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, item.entryId.value)
                statement.setString(2, item.callId.value)
                statement.setString(3, item.eventId?.value)
                statement.setString(4, item.id.value)
                statement.setString(5, item.kind.name)
                statement.setInt(6, item.sequence)
                item.startByte?.let { statement.setLong(7, it) } ?: statement.setNull(7, java.sql.Types.BIGINT)
                item.endByte?.let { statement.setLong(8, it) } ?: statement.setNull(8, java.sql.Types.BIGINT)
                item.totalBytes?.let { statement.setLong(9, it) } ?: statement.setNull(9, java.sql.Types.BIGINT)
                statement.setBoolean(10, item.truncatedBefore)
                statement.setBoolean(11, item.truncatedAfter)
                statement.setTimestamp(12, item.createdAt.sql())
                statement.executeUpdate()
            }
            val eventPredicate = if (item.eventId == null) "event_id IS NULL" else "event_id=?"
            prepareStatement(
                """
                SELECT content_id,start_byte,end_byte,total_bytes,truncated_before,truncated_after
                FROM command_journal_content_links
                WHERE call_id=? AND $eventPredicate AND kind=? AND sequence=?
                """.trimIndent()
            ).use { statement ->
                var index = 1
                statement.setString(index++, item.callId.value)
                item.eventId?.let { statement.setString(index++, it.value) }
                statement.setString(index++, item.kind.name)
                statement.setInt(index, item.sequence)
                statement.executeQuery().use { rows ->
                    check(rows.next() && rows.getString("content_id") == item.id.value &&
                        rows.nullableLong("start_byte") == item.startByte &&
                        rows.nullableLong("end_byte") == item.endByte &&
                        rows.nullableLong("total_bytes") == item.totalBytes &&
                        rows.getBoolean("truncated_before") == item.truncatedBefore &&
                        rows.getBoolean("truncated_after") == item.truncatedAfter
                    ) { "Command Journal content link identity cannot be rebound" }
                }
            }
        }
    }

    private fun Connection.findEntry(id: CommandJournal.Entry.Id, forUpdate: Boolean): CommandJournal.Entry? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return prepareStatement("SELECT * FROM command_journal_entries WHERE id=?$suffix").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.entry() else null }
        }
    }

    private fun Connection.findEntry(
        projectId: Project.Id,
        entryId: CommandJournal.Entry.Id,
    ): CommandJournal.Entry? =
        prepareStatement("SELECT * FROM command_journal_entries WHERE project_id=? AND id=?").use { statement ->
            statement.setString(1, projectId.value)
            statement.setString(2, entryId.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.entry() else null }
        }

    private fun Connection.findCall(id: CommandJournal.Call.Id): CommandJournal.Call? =
        prepareStatement("SELECT * FROM command_journal_calls WHERE id=?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.call() else null }
        }

    private fun Connection.findEvent(entryId: CommandJournal.Entry.Id, sourceKey: String): CommandJournal.Event? =
        prepareStatement("SELECT * FROM command_journal_events WHERE entry_id=? AND source_key=?").use { statement ->
            statement.setString(1, entryId.value)
            statement.setString(2, sourceKey)
            statement.executeQuery().use { rows -> if (rows.next()) rows.event() else null }
        }

    private fun ResultSet.entry(prefix: String = "") = CommandJournal.Entry(
        id = CommandJournal.Entry.Id(getString("${prefix}id")),
        origin = CommandJournal.Entry.Origin.valueOf(getString("${prefix}origin")),
        actorUserId = User.Id(getString("${prefix}actor_user_id")),
        projectId = Project.Id(getString("${prefix}project_id")),
        conversationId = Conversation.Id(getString("${prefix}conversation_id")),
        threadId = Conversation.Thread.Id(getString("${prefix}thread_id")),
        agentDefinitionId = getString("${prefix}agent_definition_id")?.let(AgentDefinition::Id),
        sourceRuntimeTaskId = getString("${prefix}source_runtime_task_id")?.let(ConversationRuntimeTask::Id),
        sourceToolCallId = getString("${prefix}source_tool_call_id")?.let(ContentItem.ToolCall::Id),
        parentEntryId = getString("${prefix}parent_entry_id")?.let(CommandJournal.Entry::Id),
        createdAt = getTimestamp("${prefix}created_at").instant(),
    )

    private fun ResultSet.call(prefix: String = "") = CommandJournal.Call(
        id = CommandJournal.Call.Id(getString("${prefix}id")),
        entryId = CommandJournal.Entry.Id(getString("${prefix}entry_id")),
        parentId = getString("${prefix}parent_id")?.let(CommandJournal.Call::Id),
        sourceToolCallId = getString("${prefix}source_tool_call_id")?.let(ContentItem.ToolCall::Id),
        kind = CommandJournal.Call.Kind.valueOf(getString("${prefix}kind")),
        toolName = getString("${prefix}tool_name"),
        target = CommandJournal.Call.Target(
            workerId = getString("${prefix}worker_id")?.let(::ConversationRuntimeWorkerId),
            workspaceId = getString("${prefix}workspace_id")?.let(Workspace::Id),
            workspaceMountId = getString("${prefix}workspace_mount_id")?.let(WorkspaceMount::Id),
            workspaceRootPath = getString("${prefix}workspace_root_path"),
            workingDirectory = getString("${prefix}working_directory"),
        ),
        createdAt = getTimestamp("${prefix}created_at").instant(),
    )

    private fun ResultSet.event(prefix: String = "") = CommandJournal.Event(
        id = CommandJournal.Event.Id(getString("${prefix}id")),
        entryId = CommandJournal.Entry.Id(getString("${prefix}entry_id")),
        callId = CommandJournal.Call.Id(getString("${prefix}call_id")),
        sourceKey = getString("${prefix}source_key"),
        kind = CommandJournal.Event.Kind.valueOf(getString("${prefix}kind")),
        occurredAt = getTimestamp("${prefix}occurred_at").instant(),
        commandTaskId = getString("${prefix}command_task_id")?.let(CommandTask::Id),
        statusMessage = getString("${prefix}status_message"),
        exitCode = getInt("${prefix}exit_code").takeUnless { wasNull() },
    )

    private fun ResultSet.content() = CommandJournal.Content(
        id = CommandJournal.Content.Id(getString("content_id")),
        entryId = CommandJournal.Entry.Id(getString("entry_id")),
        callId = CommandJournal.Call.Id(getString("call_id")),
        eventId = getString("event_id")?.let(CommandJournal.Event::Id),
        kind = CommandJournal.Content.Kind.valueOf(getString("kind")),
        sequence = getInt("sequence"),
        text = getString("text_content"),
        startByte = nullableLong("start_byte"),
        endByte = nullableLong("end_byte"),
        totalBytes = nullableLong("total_bytes"),
        truncatedBefore = getBoolean("truncated_before"),
        truncatedAfter = getBoolean("truncated_after"),
        createdAt = getTimestamp("created_at").instant(),
    )

    private fun Connection.count(table: String, entryId: CommandJournal.Entry.Id): Long {
        require(table in setOf("command_journal_calls", "command_journal_events")) { "Unsupported journal count table" }
        return prepareStatement("SELECT count(*) FROM $table WHERE entry_id=?").use { statement ->
            statement.setString(1, entryId.value)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
    }

    private fun PreparedStatement.bind(values: List<Any>, startIndex: Int = 1) {
        values.forEachIndexed { offset, value ->
            val index = startIndex + offset
            when (value) {
                is String -> setString(index, value)
                is Int -> setInt(index, value)
                is Long -> setLong(index, value)
                is Instant -> setTimestamp(index, value.sql())
                else -> error("Unsupported Command Journal SQL parameter: ${value::class.simpleName}")
            }
        }
    }

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun Instant.sql() = Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))

    private fun Timestamp.instant(): Instant = toInstant().let {
        Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong())
    }
}
