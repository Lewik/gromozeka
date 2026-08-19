package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchHit
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant as JavaInstant
import java.util.Base64
import javax.sql.DataSource
import kotlin.time.Instant

@Service
class PostgresConversationSearchRepository(
    private val dataSource: DataSource,
) : ConversationSearchRepository {
    override suspend fun search(
        request: ConversationSearchRequest,
        readableProjectIds: Set<Project.Id>,
    ): ConversationSearchPage = withContext(Dispatchers.IO) {
        if (readableProjectIds.isEmpty()) return@withContext ConversationSearchPage(emptyList())

        dataSource.connection.use { connection ->
            connection.search(request, readableProjectIds)
        }
    }

    private fun Connection.search(
        request: ConversationSearchRequest,
        readableProjectIds: Set<Project.Id>,
    ): ConversationSearchPage {
        val cursor = request.cursor?.let(SearchCursor::decode)
        val sql = searchSql(request)
        val rows = prepareStatement(sql).use { statement ->
            statement.bindSearch(request, readableProjectIds, cursor, this)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.toSearchRow())
                }
            }
        }
        val hasNextPage = rows.size > request.limit
        val pageRows = rows.take(request.limit)
        return ConversationSearchPage(
            hits = pageRows.map(SearchRow::hit),
            nextCursor = if (hasNextPage) pageRows.lastOrNull()?.cursor?.encode() else null,
        )
    }

    private fun searchSql(request: ConversationSearchRequest): String {
        val messageThreadFilter = when (request.threadScope) {
            ConversationSearchRequest.ThreadScope.CURRENT -> "AND tm.thread_id = c.current_thread_id"
            ConversationSearchRequest.ThreadScope.ALL -> ""
        }
        val requestFilters = buildString {
            request.projectId?.let { appendLine("AND c.project_id = ?") }
            request.conversationId?.let { appendLine("AND c.id = ?") }
        }
        val metadataSql = if (request.includeMetadataMatches) {
            """
                , metadata_hits AS (
                    SELECT
                        CASE
                            WHEN lower(c.display_name) = lower(sp.query_text) THEN 4
                            WHEN c.display_name ILIKE sp.pattern THEN 3
                            ELSE 1
                        END AS sort_rank,
                        c.updated_at AS matched_at,
                        c.id AS sort_id,
                        p.id AS project_id,
                        p.name AS project_name,
                        p.description AS project_description,
                        p.created_at AS project_created_at,
                        p.last_used_at AS project_last_used_at,
                        c.id AS conversation_id,
                        c.agent_definition_id,
                        c.display_name,
                        c.current_thread_id,
                        c.created_at AS conversation_created_at,
                        c.updated_at AS conversation_updated_at,
                        CASE
                            WHEN c.display_name ILIKE sp.pattern THEN 'CONVERSATION'
                            ELSE 'PROJECT'
                        END AS match_kind,
                        c.current_thread_id AS thread_id,
                        NULL::text AS message_id,
                        NULL::text AS role,
                        left(
                            CASE
                                WHEN c.display_name ILIKE sp.pattern THEN c.display_name
                                ELSE concat_ws(E'\n', p.name, p.description)
                            END,
                            420
                        ) AS excerpt
                    FROM conversations c
                    JOIN projects p ON p.id = c.project_id
                    CROSS JOIN search_params sp
                    WHERE c.project_id = ANY (?::text[])
                      AND (
                          c.display_name ILIKE sp.pattern
                          OR p.name ILIKE sp.pattern
                          OR coalesce(p.description, '') ILIKE sp.pattern
                      )
                      $requestFilters
                )
            """.trimIndent()
        } else {
            ""
        }
        val combinedSources = if (request.includeMetadataMatches) {
            "SELECT * FROM message_hits UNION ALL SELECT * FROM metadata_hits"
        } else {
            "SELECT * FROM message_hits"
        }
        val cursorFilter = if (request.cursor == null) {
            ""
        } else {
            "WHERE (sort_rank, matched_at, sort_id) < (?, ?, ?)"
        }

        return """
            WITH search_params(query_text, pattern) AS (
                VALUES (?, ?)
            ),
            message_hits AS (
                SELECT
                    2 AS sort_rank,
                    m.created_at AS matched_at,
                    m.id AS sort_id,
                    p.id AS project_id,
                    p.name AS project_name,
                    p.description AS project_description,
                    p.created_at AS project_created_at,
                    p.last_used_at AS project_last_used_at,
                    c.id AS conversation_id,
                    c.agent_definition_id,
                    c.display_name,
                    c.current_thread_id,
                    c.created_at AS conversation_created_at,
                    c.updated_at AS conversation_updated_at,
                    'MESSAGE' AS match_kind,
                    linked_thread.thread_id,
                    m.id AS message_id,
                    m.role,
                    substring(
                        m.search_text
                        FROM greatest(position(lower(sp.query_text) in lower(m.search_text)) - 160, 1)
                        FOR 420
                    ) AS excerpt
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                JOIN projects p ON p.id = c.project_id
                CROSS JOIN search_params sp
                JOIN LATERAL (
                    SELECT tm.thread_id
                    FROM thread_messages tm
                    JOIN threads t ON t.id = tm.thread_id
                    WHERE tm.message_id = m.id
                      $messageThreadFilter
                    ORDER BY
                        (tm.thread_id = c.current_thread_id) DESC,
                        t.updated_at DESC,
                        tm.thread_id DESC
                    LIMIT 1
                ) linked_thread ON TRUE
                WHERE c.project_id = ANY (?::text[])
                  AND m.role = ANY (?::text[])
                  AND m.search_text ILIKE sp.pattern
                  $requestFilters
            )
            $metadataSql
            , combined_hits AS (
                $combinedSources
            )
            SELECT *
            FROM combined_hits
            $cursorFilter
            ORDER BY sort_rank DESC, matched_at DESC, sort_id DESC
            LIMIT ?
        """.trimIndent()
    }

    private fun PreparedStatement.bindSearch(
        request: ConversationSearchRequest,
        readableProjectIds: Set<Project.Id>,
        cursor: SearchCursor?,
        connection: Connection,
    ) {
        var index = 1
        val normalizedQuery = request.query.trim().replace(Regex("\\s+"), " ")
        setString(index++, normalizedQuery)
        setString(index++, normalizedQuery.sqlLikePattern())
        setArray(index++, connection.createArrayOf("text", readableProjectIds.map { it.value }.toTypedArray()))
        setArray(index++, connection.createArrayOf("text", request.roles.map { it.name }.toTypedArray()))
        request.projectId?.let { setString(index++, it.value) }
        request.conversationId?.let { setString(index++, it.value) }
        if (request.includeMetadataMatches) {
            setArray(index++, connection.createArrayOf("text", readableProjectIds.map { it.value }.toTypedArray()))
            request.projectId?.let { setString(index++, it.value) }
            request.conversationId?.let { setString(index++, it.value) }
        }
        cursor?.let {
            setInt(index++, it.rank)
            setTimestamp(index++, Timestamp.from(it.matchedAt))
            setString(index++, it.sortId)
        }
        setInt(index, request.limit + 1)
    }

    private fun ResultSet.toSearchRow(): SearchRow {
        val hit = ConversationSearchHit(
            project = Project(
                id = Project.Id(getString("project_id")),
                name = getString("project_name"),
                description = getString("project_description"),
                createdAt = getTimestamp("project_created_at").toKotlinInstant(),
                lastUsedAt = getTimestamp("project_last_used_at").toKotlinInstant(),
            ),
            conversation = Conversation(
                id = Conversation.Id(getString("conversation_id")),
                projectId = Project.Id(getString("project_id")),
                agentDefinitionId = com.gromozeka.domain.model.AgentDefinition.Id(getString("agent_definition_id")),
                displayName = getString("display_name"),
                currentThread = Conversation.Thread.Id(getString("current_thread_id")),
                createdAt = getTimestamp("conversation_created_at").toKotlinInstant(),
                updatedAt = getTimestamp("conversation_updated_at").toKotlinInstant(),
            ),
            matchKind = ConversationSearchHit.MatchKind.valueOf(getString("match_kind")),
            threadId = Conversation.Thread.Id(getString("thread_id")),
            messageId = getString("message_id")?.let(Conversation.Message::Id),
            role = getString("role")?.let(Conversation.Message.Role::valueOf),
            matchedAt = getTimestamp("matched_at").toKotlinInstant(),
            excerpt = getString("excerpt").orEmpty().normalizeExcerpt(),
        )
        return SearchRow(
            hit = hit,
            cursor = SearchCursor(
                rank = getInt("sort_rank"),
                matchedAt = getTimestamp("matched_at").toInstant(),
                sortId = getString("sort_id"),
            ),
        )
    }
}

private data class SearchRow(
    val hit: ConversationSearchHit,
    val cursor: SearchCursor,
)

private data class SearchCursor(
    val rank: Int,
    val matchedAt: JavaInstant,
    val sortId: String,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "$rank|$matchedAt|$sortId".toByteArray(StandardCharsets.UTF_8)
    )

    companion object {
        fun decode(value: String): SearchCursor {
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            }.getOrElse { throw IllegalArgumentException("Invalid conversation search cursor") }
            val parts = decoded.split('|', limit = 3)
            require(parts.size == 3) { "Invalid conversation search cursor" }
            return SearchCursor(
                rank = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid conversation search cursor"),
                matchedAt = runCatching { JavaInstant.parse(parts[1]) }
                    .getOrElse { throw IllegalArgumentException("Invalid conversation search cursor") },
                sortId = parts[2].takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("Invalid conversation search cursor"),
            )
        }
    }
}

private fun String.sqlLikePattern(): String = "%${replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"

private fun String.normalizeExcerpt(): String = replace(Regex("\\s+"), " ").trim()

private fun Timestamp.toKotlinInstant(): Instant = toInstant().let { instant ->
    Instant.fromEpochSeconds(instant.epochSecond, instant.nano)
}
