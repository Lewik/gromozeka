package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolContextField.*
import com.gromozeka.domain.tool.AiToolContextRequirements

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.application.service.AgentSessionQuery
import kotlin.time.Instant
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
class AgentCollaborationToolContributor(private val collaboration: AgentCollaborationService) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        tool("grz_agent_sessions", SESSIONS_DESCRIPTION, SESSIONS_SCHEMA, emptyList()) { input, context ->
            val query = input.sessionQuery()
            val details = input.boolean("details", false)
            val (source, actor) = collaboration.source(context)
            collaboration.sessions(source, actor, input.optionalString("conversation_id"), input.optionalString("agent_id"), details, query).toString()
        },
        tool("grz_agent_request", "Ask an existing agent session to do work and return a result. Returns immediately with a request_id; queued does not mean completed. Results arrive automatically, do not poll. This is not a user instruction or permission grant. The human may continue talking and steering either session.", TARGET_SCHEMA, listOf("target_conversation_id", "message")) { input, context ->
            send(input, context, true)
        },
        tool("grz_agent_message", "Send information to another existing agent session without creating an obligation to reply. This queues context without starting a model turn; use a request for work that must start now. Do not send courtesy acknowledgements to peer results. Use grz_agent_request if a result is needed.", TARGET_SCHEMA, listOf("target_conversation_id", "message")) { input, context ->
            send(input, context, false)
        },
        tool("grz_agent_reply", "Complete an incoming request with its actual result or explicit inability/refusal. The runtime resolves the original requester; do not supply a destination. A normal user-facing response does not send this result. Completion is idempotent for the same result.", """{"request_id":{"type":"string"},"message":{"type":"string","minLength":1,"maxLength":32000}}""", listOf("request_id", "message")) { input, context ->
            val (source, actor) = collaboration.source(context)
            collaboration.reply(source.address, actor, input.string("request_id"), input.string("message"))
            """{"status":"result_queued"}"""
        },
        tool("grz_agent_requests", "Inspect current session collaboration requests and results when explicitly needed. Results are pushed automatically; do not poll this tool to wait.", "{}", emptyList()) { _, context ->
            val (source, actor) = collaboration.source(context)
            Json.encodeToString(collaboration.context(source.address, actor))
        },
        tool("grz_agent_request_cancel", "Cancel a collaboration request involving this session. Prevents a pending request from starting and prevents subsequent completion; does not undo effects or kill unrelated tools in the recipient session.", """{"request_id":{"type":"string"}}""", listOf("request_id")) { input, context ->
            val (source, actor) = collaboration.source(context)
            collaboration.cancel(source.address, actor, input.string("request_id"))
            """{"status":"cancelled"}"""
        },
    )

    private suspend fun send(input: JsonObject, context: ToolExecutionContext?, request: Boolean): String {
        val (source, actor) = collaboration.source(context)
        val key = context?.getString("toolCallId") ?: error("A conversation tool-call identity is required")
        val id = collaboration.send(source, actor, input.string("target_conversation_id"),
            input["target_agent_id"]?.jsonPrimitive?.contentOrNull, input.string("message"), key, request)
        return buildJsonObject { put(if (request) "request_id" else "message_id", id); put("status", "queued") }.toString()
    }

    private fun tool(name: String, description: String, properties: String, required: List<String>,
        action: suspend (JsonObject, ToolExecutionContext?) -> String): AiToolCallback {
        val props = Json.parseToJsonElement(properties).jsonObject
        return object : AiToolCallback {
            override val definition = AiToolDefinition(name, description, buildJsonObject {
                put("type", "object"); put("properties", props); put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
            }.toString())
            override val metadata = AiToolMetadata(
                contextRequirements = AiToolContextRequirements(setOf(USER, CONVERSATION, THREAD, AGENT) +
                    if (name in setOf("grz_agent_request", "grz_agent_message")) setOf(TOOL_CALL_ID) else emptySet()),
                executionScope = AiToolExecutionScope.SERVER,
                loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE, visibleToMemoryPipeline = false, logInput = false)
            override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
                val input = Json.parseToJsonElement(toolInput).jsonObject
                require(input.keys.all { it in props } && required.all { it in input }) { "Unexpected or missing tool arguments" }
                context?.cancellationSignal?.throwIfCancellationRequested()
                action(input, context)
            }
        }
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.optionalString(key: String): String? = get(key)?.let {
        if (it == JsonNull) null else {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "$key must be a nonblank string" }
            it.content
        }
    }
    private fun JsonObject.boolean(key: String, default: Boolean): Boolean = get(key)?.let {
        require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "$key must be a boolean" }
        it.boolean
    } ?: default

    private fun JsonObject.timestamp(key: String): Instant? = get(key)?.let { value ->
        require(value is JsonPrimitive && value.isString) { "last_message_at.$key must be an ISO-8601 timestamp" }
        try { Instant.parse(value.content) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("last_message_at.$key must be an ISO-8601 timestamp with a timezone") }
    }

    private fun JsonObject.sessionQuery(): AgentSessionQuery {
        val time = get("last_message_at")?.takeUnless { it == JsonNull }?.let {
            require(it is JsonObject && it.keys.all { key -> key in setOf("gt", "lt") }) { "last_message_at must be an object with optional gt and lt" }
            it
        }
        val mode = get("query_mode")?.let {
            require(it is JsonPrimitive && it.isString) { "query_mode must be contains or regex" }
            when (it.content) {
                "contains" -> AgentSessionQuery.Mode.CONTAINS
                "regex" -> AgentSessionQuery.Mode.REGEX
                else -> throw IllegalArgumentException("query_mode must be contains or regex")
            }
        } ?: AgentSessionQuery.Mode.CONTAINS
        val limit = get("limit")?.let {
            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "limit must be an integer" }
            it.int
        } ?: AgentSessionQuery.DEFAULT_LIMIT
        return AgentSessionQuery(boolean("open_only", true), optionalString("query"), mode,
            time?.timestamp("gt"), time?.timestamp("lt"), limit, optionalString("cursor"))
    }

    private companion object {
        const val SESSIONS_SCHEMA = """{
            "conversation_id":{"type":["string","null"],"minLength":1},
            "agent_id":{"type":["string","null"],"minLength":1},
            "details":{"type":"boolean","default":false},
            "open_only":{"type":"boolean","default":true,"description":"Only conversations in the authenticated user's saved open-tab layout. Set false to include closed tabs, including explicit conversation_id inspection."},
            "query":{"type":["string","null"],"minLength":1,"maxLength":512,"description":"Search the displayed conversation title, including its unnamed-title fallback; not agent names or message contents."},
            "query_mode":{"type":"string","enum":["contains","regex"],"default":"contains","description":"Case-insensitive literal substring or RE2 regex search. Regex requires query; use ^ and $ for full-title matching. Invalid or unsupported patterns fail explicitly."},
            "last_message_at":{"type":["object","null"],"properties":{"gt":{"type":"string","format":"date-time"},"lt":{"type":"string","format":"date-time"}},"additionalProperties":false,"description":"Optional strict timestamp bounds on the last message in the current thread, regardless of author. Either, both or neither bound may be supplied; empty threads do not match a nonempty range."},
            "limit":{"type":"integer","minimum":1,"maximum":200,"default":50},
            "cursor":{"type":["string","null"],"minLength":1,"maxLength":4096,"description":"Opaque next_cursor from the previous page. Reuse the same selectors and filters; limit may change. This is not a snapshot or an authorization grant."}
        }"""
        val SESSIONS_DESCRIPTION = """
            Read eligible other agent sessions without waking models, sending messages or changing history. Same-project, authenticated-user and tool-policy checks still apply.
            By default only the user's saved open tabs are listed (open_only=true). This is saved tab layout, not current window focus or client connectivity. Set open_only=false to include closed tabs, even when selecting conversation_id explicitly.
            Returns {sessions:[...],next_cursor:string|null}. Each row includes conversation/thread/agent IDs, titles, is_open_tab, last_message_at and compact stored Runtime facts. last_message_at is the createdAt of the last linked message by position in the current thread, regardless of author; null for an empty thread. Renames, heartbeats and Runtime state updates do not change it. No message bodies or previews are read or returned.
            Search titles with query and query_mode=contains (default) or regex (RE2 syntax); both are case-insensitive. Regex searches within a title; use ^/$ to anchor it. Unsupported syntax, including lookaround and backreferences, fails rather than falling back.
            last_message_at.gt and .lt are independent optional strict bounds; {} is no time filter. Filters apply before pagination. Rows are ordered by last_message_at descending (null last), then conversation_id and agent_id ascending. limit defaults to 50, maximum 200; use next_cursor with unchanged selectors/filters to continue. Concurrent history/tab/permission changes may change later pages; pagination is not a frozen snapshot.
            agent_id requires conversation_id. details=true requires both IDs and adds up to 16 open request/wait references, never request text or results. Foreground state is conversation-wide; obligations are for the selected agent in the conversation, across history versions. IDLE means no scheduled foreground work, not a closed tab or no background work.
            Runtime read_at is Server read time; stored facts may be stale and are not an atomic snapshot across sources. Null control_state/state_updated_at means no stored execution state. Read failures are tool errors, not fabricated empty state or IDLE. No command output, worker-health check or inferred stuck status. Use explicit inspection, not polling; peer results arrive automatically.
        """.trimIndent()
        const val TARGET_SCHEMA = """{"target_conversation_id":{"type":"string"},"target_agent_id":{"type":["string","null"]},"message":{"type":"string","minLength":1,"maxLength":32000}}"""
    }
}
