package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.tool.AiToolAccessPolicy
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolContextField
import com.gromozeka.domain.tool.AiToolContextRequirements
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_CALL_ID
import com.gromozeka.domain.tool.requiredProjectId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.time.Instant

@Service
@ConditionalOnProperty(name = ["gromozeka.command-journal.enabled"], havingValue = "true")
internal class CommandJournalToolContributor(
    private val toolFactory: ServerJsonToolFactory,
    private val reader: CommandJournal.Reader,
    private val recorder: CommandJournalRecorder,
    private val projectAccess: ProjectAccessService,
    private val authorization: GromozekaRemoteAuthorization,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        reportTool(),
        searchTool(),
        getTool(),
        readContentTool(),
        statisticsTool(),
    )

    private fun reportTool(): AiToolCallback = toolFactory.create(
        source = CORE_TOOL_SOURCE,
        name = REPORT_TOOL_NAME,
        description = """
            Record a command or Python invocation that the caller explicitly says already happened outside Gromozeka.
            This tool does not execute anything. Use it only for a direct caller/user report, never for an inference.
            The entry is permanently marked REPORTED_BY_CALLER and is not Worker-confirmed. Command Journal must be enabled.
        """.trimIndent(),
        inputSchema = REPORT_SCHEMA,
        readOnly = false,
        idempotent = true,
        loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
        contextRequirements = AiToolContextRequirements(
            required = setOf(
                AiToolContextField.USER,
                AiToolContextField.PROJECT,
                AiToolContextField.CONVERSATION,
                AiToolContextField.THREAD,
                AiToolContextField.TOOL_CALL_ID,
            ),
            optional = setOf(AiToolContextField.AGENT),
        ),
    ) { input ->
        requireKeys(
            input,
            setOf(
                "command", "code", "target", "working_directory", "output", "exit_code", "status",
                "observed_at", "comment",
            ),
        )
        val command = input.optionalStringPreservingWhitespace("command")?.takeIf { it.isNotBlank() }
        val code = input.optionalStringPreservingWhitespace("code")?.takeIf { it.isNotBlank() }
        if ((command == null) == (code == null)) {
            throw ServerToolException("invalid_argument", "Provide exactly one of 'command' or 'code'")
        }
        val commandOrCode = command ?: checkNotNull(code)
        requireUtf8Limit(if (command != null) "command" else "code", commandOrCode, MAX_COMMAND_BYTES)
        val output = input.optionalStringPreservingWhitespace("output")
        output?.let { requireUtf8Limit("output", it, MAX_OUTPUT_BYTES) }
        val workingDirectory = input.optionalString("working_directory")?.also {
            requireUtf8Limit("working_directory", it, MAX_SMALL_TEXT_BYTES)
        }
        input.optionalString("target")?.let { requireUtf8Limit("target", it, MAX_SMALL_TEXT_BYTES) }
        val status = input.optionalString("status")?.also { requireUtf8Limit("status", it, MAX_STATUS_BYTES) }
        val comment = input.optionalString("comment")?.also { requireUtf8Limit("comment", it, MAX_STATUS_BYTES) }
        val statusMessage = listOfNotNull(status, comment).joinToString("\n").takeIf(String::isNotEmpty)?.also {
            requireUtf8Limit("combined status and comment", it, MAX_STATUS_BYTES)
        }
        val exitCode = input.optionalInteger("exit_code")
        val observedAt = input.optionalString("observed_at")?.let {
            runCatching { Instant.parse(it) }.getOrElse {
                throw ServerToolException("invalid_argument", "'observed_at' must be an ISO-8601 timestamp")
            }
        }
        val conversationId = requireNotNull(conversationId) { "Conversation context is required" }
        val conversation = authorization.requireConversation(user, conversationId, ProjectPermission.WRITE)
        val projectId = toolContext.requiredProjectId()
        require(conversation.projectId == projectId) { "Command Journal project context changed" }
        val threadId = toolContext.getString(TOOL_CONTEXT_THREAD_ID)?.let(Conversation.Thread::Id)
            ?: error("Command Journal thread context is required")
        require(conversation.currentThread == threadId) { "Command Journal thread changed before report" }
        val sourceToolCallId = toolContext.getString(TOOL_CONTEXT_TOOL_CALL_ID)
            ?.takeIf(String::isNotBlank)
            ?.let(Conversation.Message.ContentItem.ToolCall::Id)
            ?: error("Command Journal report requires its trusted tool call id")
        val recorded = recorder.reportByCaller(
            sourceToolCallId = sourceToolCallId,
            toolName = REPORT_TOOL_NAME,
            kind = if (code != null) CommandJournal.Call.Kind.PYTHON else CommandJournal.Call.Kind.COMMAND,
            commandOrCode = commandOrCode,
            rawArguments = input,
            workingDirectory = workingDirectory,
            output = output,
            statusMessage = statusMessage,
            exitCode = exitCode,
            observedAt = observedAt,
            context = toolContext,
        )
        buildJsonObject {
            put("entry_id", recorded.entry.id.value)
            put("call_id", recorded.call.id.value)
            put("origin", recorded.entry.origin.name)
            put("recorded_at", recorded.entry.createdAt.toString())
            put("executed_by_gromozeka", false)
        }
    }

    private fun searchTool(): AiToolCallback = toolFactory.create(
        source = CORE_TOOL_SOURCE,
        name = SEARCH_TOOL_NAME,
        description = """
            Search Command Journal metadata and bounded text for the current project, newest first.
            Results are audit records, not live process state. Pass the returned cursor object as before to continue.
        """.trimIndent(),
        inputSchema = SEARCH_SCHEMA,
        readOnly = true,
        idempotent = true,
        contextRequirements = AiToolContextRequirements(
            required = setOf(AiToolContextField.USER, AiToolContextField.PROJECT),
        ),
    ) { input ->
        requireKeys(
            input,
            setOf(
                "conversation_id", "actor_user_id", "agent_definition_id", "worker_id", "origin", "tool_name",
                "event_kind", "text", "from", "to", "before", "limit",
            ),
        )
        val projectId = toolContext.requiredProjectId()
        projectAccess.requirePermission(user.id, projectId, ProjectPermission.READ)
        val conversationFilter = input.optionalString("conversation_id")?.let(Conversation::Id)
        conversationFilter?.let {
            val conversation = authorization.requireConversation(user, it, ProjectPermission.READ)
            require(conversation.projectId == projectId) { "Conversation belongs to another project" }
        }
        val before = input["before"]?.let {
            val cursor = input.requiredObject("before")
            requireKeys(cursor, setOf("createdAt", "entryId"))
            CommandJournal.Cursor(
                createdAt = runCatching { Instant.parse(cursor.requiredString("createdAt")) }.getOrElse {
                    throw ServerToolException("invalid_argument", "'before.createdAt' must be an ISO-8601 timestamp")
                },
                entryId = CommandJournal.Entry.Id(cursor.requiredString("entryId")),
            )
        }
        val page = reader.search(
            CommandJournal.SearchQuery(
                projectId = projectId,
                conversationId = conversationFilter,
                actorUserId = input.optionalString("actor_user_id")?.let(User::Id),
                agentDefinitionId = input.optionalString("agent_definition_id")?.let(AgentDefinition::Id),
                workerId = input.optionalString("worker_id")?.let(::ConversationRuntimeWorkerId),
                origin = input.optionalString("origin")?.let { parseEnum<CommandJournal.Entry.Origin>("origin", it) },
                toolName = input.optionalString("tool_name"),
                eventKind = input.optionalString("event_kind")?.let { parseEnum<CommandJournal.Event.Kind>("event_kind", it) },
                text = input.optionalString("text"),
                from = input.optionalString("from")?.let { parseInstant("from", it) },
                to = input.optionalString("to")?.let { parseInstant("to", it) },
                before = before,
                limit = input.optionalInt("limit", 20, 1..50),
            )
        )
        serverToolJson.encodeToJsonElement(CommandJournal.SearchPage.serializer(), page).jsonObject
    }

    private fun getTool(): AiToolCallback = toolFactory.create(
        source = CORE_TOOL_SOURCE,
        name = GET_TOOL_NAME,
        description = "Read one Command Journal entry with bounded calls and lifecycle events. Content bodies are read separately.",
        inputSchema = GET_SCHEMA,
        readOnly = true,
        idempotent = true,
        contextRequirements = AiToolContextRequirements(
            required = setOf(AiToolContextField.USER, AiToolContextField.PROJECT),
        ),
    ) { input ->
        requireKeys(input, setOf("entry_id", "call_limit", "event_limit"))
        val projectId = toolContext.requiredProjectId()
        projectAccess.requirePermission(user.id, projectId, ProjectPermission.READ)
        val entryId = CommandJournal.Entry.Id(input.requiredString("entry_id"))
        val snapshot = reader.get(
            projectId = projectId,
            entryId = entryId,
            callLimit = input.optionalInt("call_limit", 100, 1..200),
            eventLimit = input.optionalInt("event_limit", 200, 1..500),
        ) ?: throw ServerToolException("not_found", "Command Journal entry not found in the current project")
        serverToolJson.encodeToJsonElement(CommandJournal.Snapshot.serializer(), snapshot).jsonObject
    }

    private fun readContentTool(): AiToolCallback = toolFactory.create(
        source = CORE_TOOL_SOURCE,
        name = READ_CONTENT_TOOL_NAME,
        description = "Read bounded Command Journal command, code, arguments or output text. Use nextLinkId as after_link_id to continue.",
        inputSchema = READ_CONTENT_SCHEMA,
        readOnly = true,
        idempotent = true,
        contextRequirements = AiToolContextRequirements(
            required = setOf(AiToolContextField.USER, AiToolContextField.PROJECT),
        ),
    ) { input ->
        requireKeys(
            input,
            setOf("entry_id", "call_id", "event_id", "kinds", "after_link_id", "limit", "max_characters_per_item"),
        )
        val projectId = toolContext.requiredProjectId()
        projectAccess.requirePermission(user.id, projectId, ProjectPermission.READ)
        val entryId = CommandJournal.Entry.Id(input.requiredString("entry_id"))
        val page = reader.readContent(
            CommandJournal.ContentQuery(
                projectId = projectId,
                entryId = entryId,
                callId = input.optionalString("call_id")?.let(CommandJournal.Call::Id),
                eventId = input.optionalString("event_id")?.let(CommandJournal.Event::Id),
                kinds = input.optionalStringList("kinds").mapTo(linkedSetOf()) {
                    parseEnum<CommandJournal.Content.Kind>("kinds", it)
                },
                afterLinkId = input["after_link_id"]?.let { input.requiredLong("after_link_id") },
                limit = input.optionalInt("limit", 10, 1..50),
                maxCharactersPerItem = input.optionalInt("max_characters_per_item", 8_192, 256..65_536),
            )
        ) ?: throw ServerToolException("not_found", "Command Journal entry not found in the current project")
        serverToolJson.encodeToJsonElement(CommandJournal.ContentPage.serializer(), page).jsonObject
    }

    private fun statisticsTool(): AiToolCallback = toolFactory.create(
        source = CORE_TOOL_SOURCE,
        name = STATS_TOOL_NAME,
        description = "Read Command Journal logical counts and PostgreSQL storage sizes for the current project. Physical journal and database sizes are global and therefore server-owner only.",
        inputSchema = ServerToolSchemas.empty,
        readOnly = true,
        idempotent = true,
        accessPolicy = AiToolAccessPolicy.SERVER_OWNER,
        contextRequirements = AiToolContextRequirements(
            required = setOf(AiToolContextField.USER, AiToolContextField.PROJECT),
        ),
    ) { input ->
        require(input.isEmpty()) { "Command Journal stats takes no arguments" }
        val projectId = toolContext.requiredProjectId()
        projectAccess.requirePermission(user.id, projectId, ProjectPermission.READ)
        serverToolJson.encodeToJsonElement(
            CommandJournal.Statistics.serializer(),
            reader.statistics(projectId),
        ).jsonObject
    }

    private fun parseInstant(name: String, value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse {
            throw ServerToolException("invalid_argument", "'$name' must be an ISO-8601 timestamp")
        }

    private inline fun <reified T : Enum<T>> parseEnum(name: String, value: String): T =
        enumValues<T>().singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ServerToolException(
                "invalid_argument",
                "'$name' must be one of ${enumValues<T>().joinToString { it.name }}",
            )

    private fun JsonObject.optionalInteger(name: String): Int? {
        val value = this[name] ?: return null
        return (value as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.toIntOrNull()
            ?: throw ServerToolException("invalid_argument", "'$name' must be an integer")
    }

    private fun JsonObject.optionalStringPreservingWhitespace(name: String): String? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: throw ServerToolException("invalid_argument", "'$name' must be a string")
        if (!primitive.isString) throw ServerToolException("invalid_argument", "'$name' must be a string")
        return primitive.content.takeIf(String::isNotEmpty)
    }

    private fun requireKeys(input: JsonObject, allowed: Set<String>) {
        val unsupported = input.keys - allowed
        if (unsupported.isNotEmpty()) {
            throw ServerToolException("invalid_argument", "Unsupported fields: ${unsupported.sorted().joinToString()}")
        }
    }

    private fun requireUtf8Limit(name: String, value: String, maximumBytes: Int) {
        if (value.encodeToByteArray().size > maximumBytes) {
            throw ServerToolException("invalid_argument", "'$name' exceeds $maximumBytes UTF-8 bytes")
        }
    }

    private companion object {
        const val CORE_TOOL_SOURCE = "gromozeka"
        const val REPORT_TOOL_NAME = "grz_command_journal_report"
        const val SEARCH_TOOL_NAME = "grz_command_journal_search"
        const val GET_TOOL_NAME = "grz_command_journal_get"
        const val READ_CONTENT_TOOL_NAME = "grz_command_journal_read_content"
        const val STATS_TOOL_NAME = "grz_command_journal_stats"

        const val MAX_COMMAND_BYTES = 262_144
        const val MAX_OUTPUT_BYTES = 1_048_576
        const val MAX_SMALL_TEXT_BYTES = 4_096
        const val MAX_STATUS_BYTES = 4_096

        val REPORT_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("command", ServerToolSchemas.string("Exact shell or console command reported by the caller."))
                put("code", ServerToolSchemas.string("Exact Python source reported by the caller."))
                put("target", ServerToolSchemas.string("Optional caller-reported machine, cluster, service or environment."))
                put("working_directory", ServerToolSchemas.string("Optional caller-reported working directory."))
                put("output", ServerToolSchemas.string("Optional caller-reported textual output."))
                put("exit_code", ServerToolSchemas.integer("Optional caller-reported process exit code."))
                put("status", ServerToolSchemas.string("Optional caller-reported status."))
                put("observed_at", ServerToolSchemas.string("Optional time when the caller says execution occurred."))
                put("comment", ServerToolSchemas.string("Optional caller comment or qualification."))
            })
            put("oneOf", JsonArray(listOf(
                buildJsonObject {
                    put("required", JsonArray(listOf(JsonPrimitive("command"))))
                    put("not", buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("code")))) })
                },
                buildJsonObject {
                    put("required", JsonArray(listOf(JsonPrimitive("code"))))
                    put("not", buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive("command")))) })
                },
            )))
            put("additionalProperties", false)
        }

        val SEARCH_SCHEMA = ServerToolSchemas.objectSchema(
            properties = mapOf(
                "conversation_id" to ServerToolSchemas.string("Optional conversation id in the current project."),
                "actor_user_id" to ServerToolSchemas.string("Optional exact actor user id."),
                "agent_definition_id" to ServerToolSchemas.string("Optional exact agent definition id."),
                "worker_id" to ServerToolSchemas.string("Optional exact Worker id used by any call in the entry."),
                "origin" to ServerToolSchemas.string("Optional entry origin.", CommandJournal.Entry.Origin.entries.map { it.name }),
                "tool_name" to ServerToolSchemas.string("Optional exact tool name used by any call."),
                "event_kind" to ServerToolSchemas.string("Optional latest lifecycle event kind.", CommandJournal.Event.Kind.entries.map { it.name }),
                "text" to ServerToolSchemas.string("Optional case-insensitive literal substring in tool name, content or status."),
                "from" to ServerToolSchemas.string("Optional inclusive entry creation time."),
                "to" to ServerToolSchemas.string("Optional inclusive entry creation time."),
                "before" to ServerToolSchemas.objectSchema(
                    properties = mapOf(
                        "createdAt" to ServerToolSchemas.string("Cursor creation time returned by the previous page."),
                        "entryId" to ServerToolSchemas.string("Cursor entry id returned by the previous page."),
                    ),
                    required = listOf("createdAt", "entryId"),
                ),
                "limit" to ServerToolSchemas.integer("Maximum entries in this page.", 1, 50),
            ),
        )

        val GET_SCHEMA = ServerToolSchemas.objectSchema(
            properties = mapOf(
                "entry_id" to ServerToolSchemas.string("Exact Command Journal entry id."),
                "call_limit" to ServerToolSchemas.integer("Maximum calls returned; calls plus events may not exceed 500.", 1, 200),
                "event_limit" to ServerToolSchemas.integer("Maximum events returned; calls plus events may not exceed 500.", 1, 500),
            ),
            required = listOf("entry_id"),
        )

        val READ_CONTENT_SCHEMA = ServerToolSchemas.objectSchema(
            properties = mapOf(
                "entry_id" to ServerToolSchemas.string("Exact Command Journal entry id."),
                "call_id" to ServerToolSchemas.string("Optional exact call id."),
                "event_id" to ServerToolSchemas.string("Optional exact event id."),
                "kinds" to ServerToolSchemas.stringArray("Optional content kinds to include."),
                "after_link_id" to ServerToolSchemas.integer("Return links after this cursor.", 0),
                "limit" to ServerToolSchemas.integer("Maximum content links returned; limit multiplied by max_characters_per_item must not exceed 131072.", 1, 50),
                "max_characters_per_item" to ServerToolSchemas.integer("Maximum returned characters per item; the complete requested page is capped at 131072 characters.", 256, 65_536),
            ),
            required = listOf("entry_id"),
        )
    }
}
