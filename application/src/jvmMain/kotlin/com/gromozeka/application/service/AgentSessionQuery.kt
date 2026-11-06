package com.gromozeka.application.service

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.time.Instant

data class AgentSessionQuery(
    val openOnly: Boolean = true,
    val query: String? = null,
    val queryMode: Mode = Mode.CONTAINS,
    val messageGt: Instant? = null,
    val messageLt: Instant? = null,
    val limit: Int = DEFAULT_LIMIT,
    val cursor: String? = null,
) {
    enum class Mode { CONTAINS, REGEX }

    init {
        require(query == null || (query.isNotBlank() && query.length <= MAX_QUERY_LENGTH)) { "query must contain 1..$MAX_QUERY_LENGTH characters" }
        require(query != null || queryMode == Mode.CONTAINS) { "query_mode=regex requires query" }
        require(messageGt == null || messageLt == null || messageGt < messageLt) { "last_message_at.gt must be earlier than last_message_at.lt" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(cursor == null || (cursor.isNotBlank() && cursor.length <= MAX_CURSOR_LENGTH)) { "cursor must contain 1..$MAX_CURSOR_LENGTH characters" }
    }

    private val pattern = if (queryMode == Mode.REGEX) {
        try { Pattern.compile(requireNotNull(query), Pattern.CASE_INSENSITIVE) }
        catch (error: PatternSyntaxException) { throw IllegalArgumentException("Unsupported or invalid RE2 query: ${error.description}") }
    } else null

    fun matchesTitle(title: String): Boolean = query == null || when (queryMode) {
        Mode.CONTAINS -> title.contains(query, ignoreCase = true)
        Mode.REGEX -> requireNotNull(pattern).matcher(title).find()
    }

    fun matchesTime(at: Instant?): Boolean {
        if (messageGt == null && messageLt == null) return true
        return at != null && (messageGt == null || at > messageGt) && (messageLt == null || at < messageLt)
    }

    internal fun after(scope: String): AgentSessionPosition? {
        val value = cursor ?: return null
        val decoded = try {
            Json.decodeFromString<AgentSessionCursor>(Base64.getUrlDecoder().decode(value).decodeToString(throwOnInvalidSequence = true))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid session cursor")
        }
        require(decoded.version == 1 && decoded.scope == scope) { "Cursor does not match this query or caller" }
        require(decoded.position.conversationId.isNotBlank() && decoded.position.agentId.isNotBlank()) { "Invalid session cursor position" }
        return decoded.position
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        const val MAX_QUERY_LENGTH = 512
        const val MAX_CURSOR_LENGTH = 4096
    }
}

@Serializable
internal data class AgentSessionPosition(
    val lastMessageAt: Instant?,
    val conversationId: String,
    val agentId: String,
) : Comparable<AgentSessionPosition> {
    override fun compareTo(other: AgentSessionPosition): Int =
        compareValues(other.lastMessageAt, lastMessageAt).takeIf { it != 0 }
            ?: conversationId.compareTo(other.conversationId).takeIf { it != 0 }
            ?: agentId.compareTo(other.agentId)

    fun cursor(scope: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Json.encodeToString(AgentSessionCursor(1, scope, this)).encodeToByteArray())
}

@Serializable
private data class AgentSessionCursor(val version: Int, val scope: String, val position: AgentSessionPosition)
