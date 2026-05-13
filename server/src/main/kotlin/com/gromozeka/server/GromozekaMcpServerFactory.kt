package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.memory.SearchScope
import com.gromozeka.domain.tool.memory.UnifiedSearchRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import com.gromozeka.domain.tool.memory.UnifiedSearchTool as DomainUnifiedSearchTool

@Service
class GromozekaMcpServerFactory(
    private val toolCallbacks: List<AiToolCallback>,
    private val unifiedSearchTool: DomainUnifiedSearchTool,
) {
    private val log = KLoggers.logger(this)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun create(): Server {
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

        toolCallbacks
            .filterNot { it.definition.name == UNIFIED_SEARCH_TOOL_NAME }
            .sortedBy { it.definition.name }
            .forEach { callback -> server.addAiToolCallback(callback) }

        server.addTool(
            name = UNIFIED_SEARCH_TOOL_NAME,
            description = unifiedSearchTool.description,
            inputSchema = unifiedSearchInputSchema(),
        ) { request ->
            runCatching { callUnifiedSearch(request) }
                .getOrElse { error ->
                    log.warn(error) { "MCP unified_search failed: ${error.message}" }
                    CallToolResult(
                        content = listOf(TextContent("Error: ${error.message ?: "unified_search failed"}")),
                        isError = true,
                    )
                }
        }

        log.info {
            "Created Gromozeka MCP server with ${server.tools.size} tools: ${server.tools.keys.sorted()}"
        }
        return server
    }

    private fun Server.addAiToolCallback(callback: AiToolCallback) {
        addTool(
            name = callback.definition.name,
            description = callback.definition.description,
            inputSchema = callback.definition.toMcpInputSchema(),
        ) { request ->
            runCatching {
                val arguments = request.arguments.withoutMcpContext()
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

    private fun com.gromozeka.domain.tool.AiToolDefinition.toMcpInputSchema(): Tool.Input =
        runCatching {
            json.decodeFromString(Tool.Input.serializer(), inputSchema)
        }.getOrElse { error ->
            log.warn(error) { "Failed to parse input schema for MCP tool $name: ${error.message}" }
            Tool.Input()
        }

    private fun callUnifiedSearch(request: CallToolRequest): CallToolResult {
        val input = request.arguments.toUnifiedSearchRequest()
        val result = unifiedSearchTool.execute(input, null)
        val text = result["text"]?.toString() ?: result.toString()
        return CallToolResult(
            content = listOf(TextContent(text)),
            isError = result["error"] != null || text.looksLikeToolError(),
        )
    }

    private fun JsonObject.toUnifiedSearchRequest(): UnifiedSearchRequest =
        UnifiedSearchRequest(
            query = stringValue("query").orEmpty(),
            scopes = stringList("scopes")
                .mapNotNull { scope ->
                    runCatching { SearchScope.valueOf(scope.uppercase()) }.getOrNull()
                }
                .ifEmpty { listOf(SearchScope.ALL) },
            knowledgeKinds = stringListOrNull("knowledgeKinds"),
            standings = stringListOrNull("standings"),
            bases = stringListOrNull("bases"),
            relationRoles = stringListOrNull("relationRoles"),
            perspectiveKind = stringValue("perspectiveKind"),
            perspectiveValue = stringValue("perspectiveValue"),
            includeInvalidated = this["includeInvalidated"]?.jsonPrimitive?.booleanOrNull,
            limit = this["limit"]?.jsonPrimitive?.intOrNull,
        )

    private fun unifiedSearchInputSchema(): Tool.Input =
        Tool.Input(
            properties = buildJsonObject {
                put("query", stringSchema("Search query."))
                put("scopes", arraySchema("Memory scopes to search: ALL, SOURCES, ENTITIES, CLAIMS, NOTES, TASKS, PROFILES, RUNS, EPISODES, PROCEDURES, EDGES."))
                put("knowledgeKinds", arraySchema("Optional memory kind filters."))
                put("standings", arraySchema("Optional standing/status filters."))
                put("bases", arraySchema("Optional basis filters."))
                put("relationRoles", arraySchema("Optional relation role filters."))
                put("perspectiveKind", stringSchema("Optional perspective kind filter."))
                put("perspectiveValue", stringSchema("Optional perspective value filter."))
                put("includeInvalidated", booleanSchema("Whether invalidated/stale memory can be returned."))
                put("limit", integerSchema("Maximum number of results."))
            },
            required = listOf("query"),
        )

    private fun JsonObject.withoutMcpContext(): JsonObject =
        JsonObject(filterKeys { it != "_context" })

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

    private fun JsonObject.stringValue(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringListOrNull(name: String): List<String>? =
        stringList(name).takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(name: String): List<String> =
        this[name]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun String.looksLikeToolError(): Boolean =
        startsWith("Error:", ignoreCase = true) ||
            contains("\"success\":false", ignoreCase = true) ||
            contains("\"isError\":true", ignoreCase = true)

    private fun stringSchema(description: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
        }

    private fun booleanSchema(description: String): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

    private fun integerSchema(description: String): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

    private fun arraySchema(description: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put(
                "items",
                buildJsonObject {
                    put("type", "string")
                }
            )
        }

    private companion object {
        const val UNIFIED_SEARCH_TOOL_NAME = "unified_search"
    }
}
