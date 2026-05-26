package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_QUEUE_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_RUN_STATUS_TOOL_NAME
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

@Service
class GromozekaMcpServerFactory(
    private val aiToolProvider: AiToolProvider,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun create(): Server {
        val toolExposure = GromozekaMcpToolExposure.fromEnvironment()
        val providedTools = aiToolProvider.getTools()
        val availableToolNames = providedTools
            .map { it.definition.name }
            .toSet()
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
                val arguments = request.arguments
                    .withoutMcpContext()
                    .toMcpToolArguments(definition.name)
                val context = request.arguments["_context"]?.jsonObject?.let { ToolExecutionContext(it.toContextMap()) }
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

    private fun AiToolDefinition.toMcpDefinition(): AiToolDefinition =
        if (name == MEMORY_REMEMBER_TOOL_NAME) {
            copy(
                description = MCP_MEMORY_REMEMBER_DESCRIPTION,
                inputSchema = MCP_MEMORY_REMEMBER_INPUT_SCHEMA,
            )
        } else {
            this
        }

    private fun com.gromozeka.domain.tool.AiToolDefinition.toMcpInputSchema(): Tool.Input =
        runCatching {
            json.decodeFromString(Tool.Input.serializer(), inputSchema)
        }.getOrElse { error ->
            log.warn(error) { "Failed to parse input schema for MCP tool $name: ${error.message}" }
            Tool.Input()
        }

    private fun JsonObject.withoutMcpContext(): JsonObject =
        JsonObject(filterKeys { it != "_context" })

    private fun JsonObject.toMcpToolArguments(toolName: String): JsonObject =
        if (toolName == MEMORY_REMEMBER_TOOL_NAME) {
            toMcpMemoryRememberArguments()
        } else {
            this
        }

    private fun JsonObject.toMcpMemoryRememberArguments(): JsonObject {
        val forbiddenFields = setOf("target", "target_message_id", "user_consent_confirmed")
        val providedForbiddenFields = keys.intersect(forbiddenFields)
        require(providedForbiddenFields.isEmpty()) {
            "MCP memory_remember accepts only explicit text/file_path/raw_url content. Unsupported fields: ${providedForbiddenFields.sorted()}."
        }

        val hasDocumentInput = containsKey("file_path") ||
            containsKey("raw_url") ||
            this["document_type"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        val target = if (hasDocumentInput) "provided_document" else "provided_text"

        return JsonObject(buildMap {
            putAll(this@toMcpMemoryRememberArguments)
            put("target", JsonPrimitive(target))
            put("user_consent_confirmed", JsonPrimitive(true))
        })
    }

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
        const val MCP_MEMORY_REMEMBER_DESCRIPTION =
            "Persist explicit user-approved text or a raw markdown/text document into typed memory. MCP callers cannot target previous conversation messages: pass exactly one of text, file_path, or raw_url. The provided content is treated as user-confirmed input, captured as source evidence, and then routed by Gromozeka into claim/note/task/source memory when appropriate. Use document_type='markdown' for text that should be ingested as a document; file_path and raw_url are treated as markdown documents by default. raw_url must return raw text/markdown, not HTML. Optional namespace is a strict memory boundary such as global, user:lewik, work:hebrew, or project:<project-id>; omit it to use the configured default or current project namespace."

        val MCP_MEMORY_REMEMBER_INPUT_SCHEMA = """
            {
              "type": "object",
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
                  "description": "Force ingestion for this exact explicit content when the user says to remember/store/import it even if the router would normally choose no-op. Use sparingly."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                },
                "namespace": {
                  "type": "string",
                  "description": "Optional strict memory namespace to write into. Examples: global, user:lewik, work:hebrew, project:<project-id>. Omit to use the configured default or current project namespace."
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
            MEMORY_QUEUE_STATUS_TOOL_NAME,
            MEMORY_ENRICH_CONTEXT_TOOL_NAME,
            MEMORY_LIST_NAMESPACES_TOOL_NAME,
            MEMORY_MAINTENANCE_TOOL_NAME,
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
