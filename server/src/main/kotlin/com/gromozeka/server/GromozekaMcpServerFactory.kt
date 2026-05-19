package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_QUEUE_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_RUN_STATUS_TOOL_NAME
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
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

    private fun String.looksLikeToolError(): Boolean =
        startsWith("Error:", ignoreCase = true) ||
            contains("\"success\":false", ignoreCase = true) ||
            contains("\"isError\":true", ignoreCase = true)
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
