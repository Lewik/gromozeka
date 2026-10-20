package com.gromozeka.server

import com.gromozeka.domain.tool.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.*
import io.ktor.util.AttributeKey

internal val exactMcpToolDefinitions = AttributeKey<Map<String, AiToolDefinition>>("gromozeka-exact-mcp-tool-definitions")

// SDK 0.15 ToolSchema models only these root keywords. This registry view is NOT
// the HTTP schema: the tools/list response adapter below exports the original JSON verbatim.
private fun String.sdkSchemaView(): ToolSchema {
    val original = Json.parseToJsonElement(this).jsonObject
    require(original["type"] == null || original["type"] == JsonPrimitive("object")) { "MCP tool schema must describe an object" }
    return serverToolJson.decodeFromJsonElement(ToolSchema.serializer(), JsonObject(original.filterKeys {
        it in setOf("type", "properties", "required", "\$schema", "\$defs")
    }))
}

internal fun JSONRPCResponse.withExactToolSchemas(definitions: Map<String, AiToolDefinition>): JsonObject {
    require(result is ListToolsResult)
    return checkNotNull(McpJson.encodeToJsonElement(JSONRPCResponse.serializer(), this).withExactToolSchemas(definitions)).jsonObject
}

/** Applied only to tools/list envelopes, including SDK-supported JSON batches; never to tool content. */
internal fun JsonElement.withExactToolSchemas(definitions: Map<String, AiToolDefinition>): JsonElement? {
    if (this is JsonArray) {
        val patched = map { it.withExactToolSchemas(definitions) ?: it }
        return if (patched == toList()) null else JsonArray(patched)
    }
    val envelope = this as? JsonObject ?: return null
    if (envelope["jsonrpc"] != JsonPrimitive("2.0") || "id" !in envelope) return null
    val result = envelope["result"] as? JsonObject ?: return null
    val tools = result["tools"] as? JsonArray ?: return null
    val exact = tools.map { item ->
        val value = item.jsonObject
        val definition = definitions.getValue(value.getValue("name").jsonPrimitive.content)
        JsonObject(value + buildMap {
            put("inputSchema", Json.parseToJsonElement(definition.inputSchema))
            definition.outputSchema?.let { put("outputSchema", Json.parseToJsonElement(it)) }
        })
    }
    return JsonObject(envelope + ("result" to JsonObject(result + ("tools" to JsonArray(exact)))))
}

internal const val CONTROL_TOOL_SOURCE = "gromozeka:control"

internal fun AiToolDefinition.toMcpTool(): Tool = Tool(
    name = name,
    description = description,
    inputSchema = inputSchema.sdkSchemaView(),
    outputSchema = outputSchema?.sdkSchemaView(),
    annotations = annotations?.let { ToolAnnotations(
        readOnlyHint = it.readOnlyHint,
        destructiveHint = it.destructiveHint,
        idempotentHint = it.idempotentHint,
        openWorldHint = it.openWorldHint,
    ) },
)

internal fun AiToolCallResult.toMcpResult(): CallToolResult = CallToolResult(
    content = contentForModel().map { item ->
        when (item) {
            is AiToolResult.Text -> TextContent(item.content)
            is AiToolResult.Binary -> EmbeddedResource(BlobResourceContents(
                uri = "urn:gromozeka:tool-result:" + URLEncoder.encode(item.fileName, StandardCharsets.UTF_8),
                mimeType = item.mediaType,
                blob = Base64.getEncoder().encodeToString(item.content),
            ))
        }
    },
    structuredContent = structuredContent,
    isError = isError,
)
