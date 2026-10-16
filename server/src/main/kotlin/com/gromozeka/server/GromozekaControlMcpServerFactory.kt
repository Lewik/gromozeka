package com.gromozeka.server

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.CancellationException
import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val CONTROL_TOOL_SOURCE = "gromozeka:control"

/** HTTP presentation of the same native callbacks used by conversation execution. */
@Service
internal class GromozekaControlMcpServerFactory(
    private val tools: AiToolProvider,
) {
    fun create(caller: AuthenticatedMcpCaller): Server {
        val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to caller.user.id.value))
        val server = Server(
            serverInfo = Implementation(name = "gromozeka-control", version = "dev"),
            options = ServerOptions(ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            )),
        )
        tools.getTools()
            .filter { it.available && it.definition.source == CONTROL_TOOL_SOURCE &&
                it.metadata.executionScope == AiToolExecutionScope.SERVER }
            .sortedBy { it.definition.name }
            .forEach { callback ->
                server.addTool(AiToolDescriptor(callback.definition, callback.metadata)
                    .withInvocationContext(AiToolContextBindings.from(context)).definition.toMcpTool()) { request ->
                    withContext(Dispatchers.IO) {
                        try {
                            val input = callback.metadata.parseInvocationInput(request.arguments ?: JsonObject(emptyMap()), context)
                            input.requireResolvedSelectors(context)
                            callback.metadata.requireInvocationContext(context)
                            callback.callResult(input.arguments.toString(), context).toMcpResult()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AiToolCallResult(content = listOf(AiToolResult.Text("Invalid tool invocation context or operation failed")), isError = true).toMcpResult()
                        }
                    }
                }
            }
        return server
    }
}

internal fun AiToolDefinition.toMcpTool(): Tool = Tool(
    name = name,
    description = description,
    inputSchema = serverToolJson.decodeFromString(ToolSchema.serializer(), inputSchema),
    outputSchema = outputSchema?.let { serverToolJson.decodeFromString(ToolSchema.serializer(), it) },
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
