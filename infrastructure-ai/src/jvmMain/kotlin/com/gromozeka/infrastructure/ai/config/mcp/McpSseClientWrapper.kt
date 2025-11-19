package com.gromozeka.infrastructure.ai.config.mcp

import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.spec.McpSchema
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * SSE wrapper for Java MCP SDK client.
 *
 * This wrapper integrates the Java MCP SDK's SSE transport with our Kotlin codebase,
 * converting reactive Mono/Flux operations to Kotlin coroutines.
 */
data class McpSseClientWrapper(
    override val name: String,
    private val client: McpAsyncClient,
    private val transport: HttpClientSseClientTransport,
    private val coroutineScope: CoroutineScope
) : McpWrapperInterface {
    private val log = KLoggers.logger {}

    override suspend fun initialize() {
        client.initialize().awaitSingle()

        log.info { "Connected to MCP SSE server: $name" }
    }

    override suspend fun listTools(): List<Tool> {
        val result = client.listTools().awaitSingleOrNull()
            ?: throw IllegalStateException("listTools returned null")

        return result.tools().map { javaTool ->
            Tool(
                javaTool.name(),
                javaTool.description(),
                Tool.Input(),
                null,
                null
            )
        }
    }

    override suspend fun callTool(toolName: String, arguments: Map<String, Any>): String {
        val request = McpSchema.CallToolRequest(toolName, arguments)
        val result = client.callTool(request).awaitSingleOrNull()

        if (result == null) {
            return "Tool returned no result"
        }

        return result.content().joinToString("\n") { content ->
            when {
                content.type() == "text" -> {
                    (content as? McpSchema.TextContent)?.text() ?: ""
                }
                content.type() == "image" -> {
                    (content as? McpSchema.ImageContent)?.let { "[Image: ${it.mimeType()}]" } ?: ""
                }
                content.type() == "audio" -> {
                    (content as? McpSchema.AudioContent)?.let { "[Audio: ${it.mimeType()}]" } ?: ""
                }
                content.type() == "resource" -> {
                    (content as? McpSchema.EmbeddedResource)?.let {
                        val res = it.resource()
                        "[Resource: ${res.uri()} (${res.mimeType() ?: "unknown type"})]"
                    } ?: ""
                }
                content.type() == "resource_link" -> {
                    (content as? McpSchema.ResourceLink)?.let { "[Resource Link: ${it.uri()}]" } ?: ""
                }
                else -> content.toString()
            }
        }
    }

    override fun close() {
        try {
            log.info { "Closing SSE MCP client: $name" }
            transport.closeGracefully()
                .timeout(Duration.ofSeconds(3))
                .block()
            log.info { "SSE MCP client $name closed successfully" }
        } catch (e: Exception) {
            log.error(e) { "Error closing SSE client: $name" }
        }
    }

    override fun forceClose() {
        try {
            log.info { "Force-closing SSE MCP client: $name" }
            // Just interrupt connections without waiting
            transport.close()
            log.info { "Force-closed SSE MCP client: $name" }
        } catch (e: Exception) {
            log.error(e) { "Error force-closing SSE client: $name" }
        }
    }
}
