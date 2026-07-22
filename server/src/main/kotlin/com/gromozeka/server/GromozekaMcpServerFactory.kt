package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_QUEUE_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ANSWER_QUESTION_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_EMBEDDING_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_RUN_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_WRITE_SURFACE_CONTEXT_KEY
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolCancellationSignal
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

internal const val MCP_MEMORY_HELP_TOOL_NAME = "memory_help"

@Service
class GromozekaMcpServerFactory(
    private val providedTools: List<AiToolCallback>,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun create(): Server {
        val toolExposure = GromozekaMcpToolExposure.fromEnvironment()
        val availableToolNames = providedTools
            .map { it.definition.name }
            .toSet() + MCP_MEMORY_HELP_TOOL_NAME
        toolExposure.validateAgainst(availableToolNames)

        val server = Server(
            serverInfo = Implementation(
                name = "gromozeka",
                version = "dev",
            ),
            options = ServerOptions(
                ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                )
            )
        )

        val exposedProvidedTools = providedTools
            .filter { toolExposure.exposes(it.definition.name) }
            .sortedBy { it.definition.name }
        exposedProvidedTools.forEach { callback -> server.addAiToolCallback(callback) }
        if (toolExposure.exposes(MCP_MEMORY_HELP_TOOL_NAME)) {
            server.addMemoryHelpTool()
        }

        val hiddenToolNames = availableToolNames - server.tools.keys
        log.info {
            "Created Gromozeka MCP server with ${server.tools.size}/${availableToolNames.size} tools: " +
                "mode=${toolExposure.description} exposed=${server.tools.keys.sorted()} hidden=${hiddenToolNames.sorted()}"
        }
        return server
    }

    private fun Server.addAiToolCallback(callback: AiToolCallback) {
        val definition = callback.definition.toMcpDefinition()
        addTool(
            name = definition.name,
            description = definition.description,
            inputSchema = definition.toMcpInputSchema(),
        ) { request ->
            runCatching {
                val rawArguments = request.arguments ?: JsonObject(emptyMap())
                val arguments = rawArguments
                    .withoutMcpContext()
                    .toMcpToolArguments(definition.name)
                val context = rawArguments["_context"]
                    ?.jsonObject
                    ?.let { ToolExecutionContext(it.toContextMap()) }
                    .toMcpToolContext(definition.name)
                val result = callback.call(
                    toolInput = json.encodeToString(JsonObject.serializer(), arguments),
                    context = context,
                )
                CallToolResult(
                    content = listOf(TextContent(result)),
                    isError = result.looksLikeToolError(),
                )
            }.getOrElse { error ->
                log.warn(error) { "MCP tool failed: name=${callback.definition.name} error=${error.message}" }
                CallToolResult(
                    content = listOf(TextContent("Error: ${error.message ?: "tool failed"}")),
                    isError = true,
                )
            }
        }
    }

    private fun Server.addMemoryHelpTool() {
        addTool(
            name = MCP_MEMORY_HELP_TOOL_NAME,
            description = "Read the Gromozeka typed-memory MCP guide: memory concepts, the global namespace, write/read workflows, and when to use each memory tool.",
            inputSchema = ToolSchema(),
        ) {
            CallToolResult(
                content = listOf(TextContent(MCP_MEMORY_HELP_CONTENT)),
                isError = false,
            )
        }
    }

    private fun AiToolDefinition.toMcpDefinition(): AiToolDefinition =
        when (name) {
            MEMORY_REMEMBER_TOOL_NAME -> copy(
                description = MCP_MEMORY_REMEMBER_DESCRIPTION,
                inputSchema = MCP_MEMORY_REMEMBER_INPUT_SCHEMA,
            )
            MEMORY_ENRICH_CONTEXT_TOOL_NAME -> copy(description = MCP_MEMORY_ENRICH_CONTEXT_DESCRIPTION)
            MEMORY_ANSWER_QUESTION_TOOL_NAME -> copy(description = MCP_MEMORY_ANSWER_QUESTION_DESCRIPTION)
            MEMORY_LIST_NAMESPACES_TOOL_NAME -> copy(description = MCP_MEMORY_LIST_NAMESPACES_DESCRIPTION)
            MEMORY_MAINTENANCE_TOOL_NAME -> copy(description = MCP_MEMORY_MAINTENANCE_DESCRIPTION)
            MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME -> copy(description = MCP_MEMORY_REBUILD_EMBEDDINGS_DESCRIPTION)
            MEMORY_EMBEDDING_STATUS_TOOL_NAME -> copy(description = MCP_MEMORY_EMBEDDING_STATUS_DESCRIPTION)
            MEMORY_QUEUE_STATUS_TOOL_NAME -> copy(description = MCP_MEMORY_QUEUE_STATUS_DESCRIPTION)
            MEMORY_RUN_STATUS_TOOL_NAME -> copy(description = MCP_MEMORY_RUN_STATUS_DESCRIPTION)
            else -> this
        }

    private fun com.gromozeka.domain.tool.AiToolDefinition.toMcpInputSchema(): ToolSchema =
        runCatching {
            json.decodeFromString(ToolSchema.serializer(), inputSchema)
        }.getOrElse { error ->
            log.warn(error) { "Failed to parse input schema for MCP tool $name: ${error.message}" }
            ToolSchema()
        }

    private fun JsonObject.withoutMcpContext(): JsonObject =
        JsonObject(filterKeys { it != "_context" })

    private fun JsonObject.toMcpToolArguments(toolName: String): JsonObject =
        if (toolName == MEMORY_REMEMBER_TOOL_NAME) {
            GromozekaMcpMemoryRememberAdapter.toInternalToolArguments(this)
        } else {
            this
        }

    private fun ToolExecutionContext?.toMcpToolContext(toolName: String): ToolExecutionContext? =
        if (toolName == MEMORY_REMEMBER_TOOL_NAME) {
            val values = asMapOrEmpty() +
                (MEMORY_WRITE_SURFACE_CONTEXT_KEY to GromozekaMcpMemoryRememberAdapter.writeSurface.wireName)
            ToolExecutionContext(
                values = values,
                cancellationSignal = this?.cancellationSignal ?: ToolCancellationSignal.None,
            )
        } else {
            this
        }

    private fun ToolExecutionContext?.asMapOrEmpty(): Map<String, Any?> =
        this?.asMap().orEmpty()

    private fun JsonObject.toContextMap(): Map<String, Any> =
        mapValues { (_, value) -> value.toPlainValue() }

    private fun JsonElement.toPlainValue(): Any =
        when (this) {
            is JsonObject -> toContextMap()
            is JsonArray -> map { it.toPlainValue() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull!!
                intOrNull != null -> intOrNull!!
                else -> content
            }
            else -> toString()
        }

    private fun String.looksLikeToolError(): Boolean =
        startsWith("Error:", ignoreCase = true) ||
            contains("\"success\":false", ignoreCase = true) ||
            contains("\"isError\":true", ignoreCase = true)

    private companion object {
        val MCP_MEMORY_HELP_CONTENT = """
            # Gromozeka typed memory MCP guide

            Use this guide before calling memory tools from an external MCP client. Tool descriptions stay focused on each tool; this guide explains the domain model and safe workflows.

            ## Core model

            Gromozeka stores typed memory, not raw chat history. A write may create or update:

            - `source`: original evidence text or document section kept as provenance.
            - `claim`: durable reusable fact, preference, constraint, decision, relationship, or project knowledge.
            - `note`: softer contextual memory that is useful but not a clean factual claim.
            - `action_item`: Gromozeka-internal remembered work item, todo, follow-up, or tracked intention.
            - `episode`: event-like memory about something that happened in a specific interaction or period.
            - `entity`: canonical person, project, file/document, technology, concept, organization, place, or other named thing referenced by memories.

            The router decides which typed records are appropriate. Do not force a type from the MCP side unless a tool explicitly asks for it. External Jira stories, GitHub issues, tickets, or backlog rows are external records; they are not `action_item` memory unless the user explicitly asks Gromozeka to track a follow-up.

            ## Namespaces

            A namespace is a strict memory boundary. The current Gromozeka runtime uses the single `global` namespace for every production memory operation. MCP callers cannot select or override it. `memory_list_namespaces` is diagnostic and reports `global` plus any unexpected stored namespaces.

            ## Writing memory

            Use `memory_remember` only when the user explicitly asks to remember, store, import, or preserve information.

            MCP `memory_remember` accepts explicit content only:

            - `text`: exact user-approved text to remember.
            - `file_path`: local raw text/markdown file to ingest as a document.
            - `raw_url`: URL returning raw text/markdown, not HTML.
            - `document_type`: currently `markdown`; use it when `text` should be treated as a document.

            MCP callers cannot target previous conversation messages. If the user wants a prior message remembered, pass the relevant text explicitly.

            `force_write=true` explicitly forces ingestion after technical/structure preflight. `force_write=false` disables the configured document force for this call; omit it to use the configured document-ingest default. Force never bypasses blank, size, format, or safe-segmentation validation.

            If `memory_run_status` returns top-level `status=needs_user_input` (`run_status=needs_input`), follow the result's `required_action`. Never approve a proposed structure yourself. Ask the user, and only after explicit approval repeat `memory_remember` with `confirmed_preflight_run_id` set to that run id and the same source content.

            `memory_remember` returns a queued `run_id` immediately. The accepted operation continues locally in the background. Call `memory_run_status` with that id until `poll_again=false`, then stop polling and consume `result` or follow `next_action`.

            ## Reading memory

            Use `memory_enrich_context` to enrich a context, not to ask a question.
            Use `memory_answer_question` when you need a direct answer that must be derived from persisted memory only.

            Good inputs look like:

            - `project memory ingestion pipeline and document chunking`
            - `what I know about Lewik's Toyota RunX`
            - `current action item: debug Bedrock JSON schema errors in memory note constructor`

            Both read tools return a queued `run_id` immediately. Call `memory_run_status` with that id until `poll_again=false`, then stop polling. The terminal `result` contains the structured payload. For enrichment, inject the returned `memory_context` into your reasoning or answer only when it is relevant. Treat selected memory as strong remembered context, but still reject it if it is clearly stale, contradicted, irrelevant, or insufficient.

            `memory_answer_question` returns a compact structured result containing `answer`, `sufficiency`, reasoning, and evidence refs. Use it for "what do you remember / what do you know about..." style questions when the answer itself should be produced by the memory subsystem.

            ## Maintenance and status

            - `memory_run_status`: inspect one memory run by id.
            - `memory_queue_status`: inspect queued/running memory operations and maintenance work.
            - `memory_embedding_status`: inspect vector embedding coverage for the global namespace.
            - `memory_maintenance`: schedule maintenance actions such as consolidation, entity maintenance, stale/supersede cleanup, targeted repairs, or embedding rebuild.
            - `memory_rebuild_embeddings`: rebuild vector embeddings for the global namespace (`full` reset/replace or `missing` fill-only).

            Memory remember/read operations and maintenance tools return immediately with a run id. Use `memory_run_status` or `memory_queue_status` to observe completion. Stop polling a run when `poll_again=false`. Prefer status tools before starting broad maintenance if a run is already active.

            ## Practical workflow

            1. Call `memory_help` if unsure how to use the memory MCP surface.
            2. Call `memory_list_namespaces` only to inspect namespace status or diagnose unexpected stored namespaces.
            3. Call `memory_answer_question` for direct memory-only questions.
            4. Call `memory_enrich_context` before answering or acting when remembered context may matter.
            5. Call `memory_remember` only for explicit user-approved content.
            6. Call `memory_run_status` with the returned run id until `poll_again=false`, then consume the completed result or follow `next_action`.
        """.trimIndent()

        const val MCP_MEMORY_REMEMBER_DESCRIPTION =
            "Queue persistence of explicit user-approved text or a raw markdown/text document in the global namespace and return a run_id. MCP callers must pass explicit content: text, file_path, or raw_url. Use memory_run_status until poll_again=false, then consume the final result or follow next_action. Use memory_help for typed-memory concepts and workflow."

        const val MCP_MEMORY_ENRICH_CONTEXT_DESCRIPTION =
            "Queue retrieval of persisted memory relevant to a supplied context and return a run_id. This enriches a topic/action item/current turn; it is not a question-answering tool. Use memory_run_status until poll_again=false, then consume memory_context."

        const val MCP_MEMORY_ANSWER_QUESTION_DESCRIPTION =
            "Queue a direct question answered from persisted memory only and return a run_id. Use memory_run_status until poll_again=false, then consume the compact answer, sufficiency, reasoning, evidence refs, and selected refs."

        const val MCP_MEMORY_LIST_NAMESPACES_DESCRIPTION =
            "Inspect the global memory namespace, item counts, and any unexpected stored namespaces. This runtime does not support selecting a namespace per operation."

        const val MCP_MEMORY_MAINTENANCE_DESCRIPTION =
            "Schedule one explicit maintenance action over existing memory and return a run_id: consolidate, repair, maintain_entities, apply_retention, or rebuild_embeddings. The rebuild_embeddings action supports embedding_mode=full for reset/replace and embedding_mode=missing for fill-only."

        const val MCP_MEMORY_REBUILD_EMBEDDINGS_DESCRIPTION =
            "Rebuild memory vector embeddings for the global namespace. mode=full generates a fresh complete set and then replaces existing rows; mode=missing inserts only absent rows. Returns a run_id immediately; use memory_run_status or memory_queue_status to observe completion."

        const val MCP_MEMORY_EMBEDDING_STATUS_DESCRIPTION =
            "Inspect vector embedding coverage for the global memory namespace under the currently configured embedding model: embeddable items, expected rows, existing rows, and missing rows. Read-only."

        const val MCP_MEMORY_QUEUE_STATUS_DESCRIPTION =
            "Read durable queued/running memory operations and the live Workers capable of processing them."

        const val MCP_MEMORY_RUN_STATUS_DESCRIPTION =
            "Read persisted status, public operation result, timings, errors, and child runs for one memory run by run_id. Internal child-run working payloads are omitted. Stop polling when poll_again=false."

        val MCP_MEMORY_REMEMBER_INPUT_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Exact user-approved text to remember. If document_type is omitted, the router extracts typed memory from this text as a normal explicit memory source."
                },
                "file_path": {
                  "type": "string",
                  "description": "Absolute or working-directory-relative path to a local raw text/markdown file to ingest as a document."
                },
                "raw_url": {
                  "type": "string",
                  "description": "HTTP(S) URL that returns raw text/markdown, not an HTML web page, to ingest as a document."
                },
                "document_type": {
                  "type": "string",
                  "description": "Optional document type. Currently supports 'markdown'. Required only when text should be ingested as a document; file_path/raw_url default to markdown."
                },
                "title": {
                  "type": "string",
                  "description": "Optional human-readable document title."
                },
                "source_ref": {
                  "type": "string",
                  "description": "Optional source reference such as a file path, raw URL, Confluence page label, or import id."
                },
                "force_write": {
                  "type": "boolean",
                  "description": "Optional explicit override. true forces ingestion after preflight; false disables configured document force; omit to use the configured document-ingest default. Never bypasses technical or structure validation."
                },
                "confirmed_preflight_run_id": {
                  "type": "string",
                  "description": "Run id whose run_status is needs_input and whose proposed structure the user explicitly approved. Never set without explicit user confirmation."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                }
              }
            }
        """.trimIndent()
    }
}

internal class GromozekaMcpToolExposure private constructor(
    private val exposedToolNames: Set<String>?,
    val description: String,
) {
    fun exposes(toolName: String): Boolean =
        exposedToolNames == null || toolName in exposedToolNames

    fun validateAgainst(availableToolNames: Set<String>) {
        val unknownToolNames = exposedToolNames.orEmpty() - availableToolNames
        require(unknownToolNames.isEmpty()) {
            "Unknown Gromozeka MCP exposed tools: ${unknownToolNames.sorted()}. Available tools: ${availableToolNames.sorted()}"
        }
    }

    companion object {
        val DEFAULT_TOOL_NAMES = setOf(
            MCP_MEMORY_HELP_TOOL_NAME,
            MEMORY_QUEUE_STATUS_TOOL_NAME,
            MEMORY_ENRICH_CONTEXT_TOOL_NAME,
            MEMORY_ANSWER_QUESTION_TOOL_NAME,
            MEMORY_LIST_NAMESPACES_TOOL_NAME,
            MEMORY_MAINTENANCE_TOOL_NAME,
            MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME,
            MEMORY_EMBEDDING_STATUS_TOOL_NAME,
            MEMORY_REMEMBER_TOOL_NAME,
            MEMORY_RUN_STATUS_TOOL_NAME,
        )

        fun fromEnvironment(): GromozekaMcpToolExposure =
            fromConfiguredValue(
                System.getProperty("gromozeka.mcp.exposed.tools")
                    ?: System.getenv("GROMOZEKA_MCP_EXPOSED_TOOLS")
            )

        fun fromConfiguredValue(value: String?): GromozekaMcpToolExposure {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                return GromozekaMcpToolExposure(
                    exposedToolNames = DEFAULT_TOOL_NAMES,
                    description = "default:${DEFAULT_TOOL_NAMES.sorted().joinToString(",")}",
                )
            }

            if (normalized.equals("all", ignoreCase = true) || normalized == "*") {
                return GromozekaMcpToolExposure(
                    exposedToolNames = null,
                    description = "all",
                )
            }

            val names = normalized
                .split(',', ';', '\n', '\t', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            require(names.isNotEmpty()) { "Gromozeka MCP exposed tools list is empty" }

            return GromozekaMcpToolExposure(
                exposedToolNames = names,
                description = "allowlist:${names.sorted().joinToString(",")}",
            )
        }
    }
}
