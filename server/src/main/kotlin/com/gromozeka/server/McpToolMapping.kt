package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolResult
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val CONTROL_TOOL_SOURCE = "gromozeka:control"

/** SDK 2.x stores complete schema maps; no lossy schema view or HTTP response patch. */
internal fun AiToolDefinition.toMcpTool(mapper: McpJsonMapper): McpSchema.Tool = mapper.readValue(buildJsonObject {
    put("name", name)
    put("description", description)
    put("inputSchema", Json.parseToJsonElement(inputSchema))
    outputSchema?.let { put("outputSchema", Json.parseToJsonElement(it)) }
    annotations?.let { hints -> put("annotations", buildJsonObject {
        hints.readOnlyHint?.let { put("readOnlyHint", it) }
        hints.destructiveHint?.let { put("destructiveHint", it) }
        hints.idempotentHint?.let { put("idempotentHint", it) }
        hints.openWorldHint?.let { put("openWorldHint", it) }
    }) }
}.toString(), McpSchema.Tool::class.java)

internal fun AiToolCallResult.toMcpResult(mapper: McpJsonMapper): McpSchema.CallToolResult = mapper.readValue(buildJsonObject {
    put("content", buildJsonArray {
        contentForModel().forEach { item -> add(when (item) {
            is AiToolResult.Text -> buildJsonObject { put("type", "text"); put("text", item.content) }
            is AiToolResult.Binary -> buildJsonObject {
                put("type", "resource")
                put("resource", buildJsonObject {
                    put("uri", "urn:gromozeka:tool-result:" + URLEncoder.encode(item.fileName, StandardCharsets.UTF_8))
                    put("mimeType", item.mediaType)
                    put("blob", Base64.getEncoder().encodeToString(item.content))
                })
            }
        }) }
    })
    put("isError", isError)
    structuredContent?.let { put("structuredContent", it) }
}.toString(), McpSchema.CallToolResult::class.java)

/** Parser/SDK exceptions must not echo request excerpts or internal causes to HTTP clients. */
internal class SafeMcpJsonMapper(private val delegate: McpJsonMapper = McpJsonDefaults.getMapper()) : McpJsonMapper {
    override fun <T : Any?> readValue(content: String, type: Class<T>): T = parse { delegate.readValue(content, type) }
    override fun <T : Any?> readValue(content: ByteArray, type: Class<T>): T = parse { delegate.readValue(content, type) }
    override fun <T : Any?> readValue(content: String, type: TypeRef<T>): T = parse { delegate.readValue(content, type) }
    override fun <T : Any?> readValue(content: ByteArray, type: TypeRef<T>): T = parse { delegate.readValue(content, type) }
    override fun <T : Any?> convertValue(value: Any?, type: Class<T>): T = convert { delegate.convertValue(value, type) }
    override fun <T : Any?> convertValue(value: Any?, type: TypeRef<T>): T = convert { delegate.convertValue(value, type) }
    override fun writeValueAsString(value: Any?): String = parse {
        // Servlet transport errors pass McpError itself, not JSONRPCResponse. Never let
        // Jackson treat a Throwable as a bean (message, cause, stackTrace, suppressed).
        if (value is Throwable) {
            val code = (value as? io.modelcontextprotocol.spec.McpError)?.jsonRpcError?.code() ?: -32603
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", JsonNull)
                put("error", buildJsonObject { put("code", code); put("message", "MCP request failed") })
            }.toString()
        } else {
            val safe = if (value is McpSchema.JSONRPCResponse && value.error() != null) {
                McpSchema.JSONRPCResponse.error(value.id(), McpSchema.JSONRPCResponse.JSONRPCError(
                    value.error().code(), "MCP request failed", null))
            } else value
            delegate.writeValueAsString(safe)
        }
    }
    override fun writeValueAsBytes(value: Any?): ByteArray = writeValueAsString(value).encodeToByteArray()

    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IOException("Invalid MCP message") }
    private inline fun <T> convert(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid MCP parameters") }
}
