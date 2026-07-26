package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

interface McpConnectedClient {
    val serverInfo: Implementation
    val serverInstructions: String?
    val supportsToolsListChanged: Boolean

    suspend fun listAllTools(): List<Tool>

    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): String

    fun setToolsListChangedHandler(handler: () -> Unit)

    fun close()

    fun forceClose()
}

fun interface McpClientFactory {
    suspend fun connect(config: McpServerConfig): McpConnectedClient
}

@Service
class DefaultMcpClientFactory : McpClientFactory {
    override suspend fun connect(config: McpServerConfig): McpConnectedClient =
        when (val transport = config.transport) {
            is McpServerTransport.Stdio -> connectStdio(config, transport)
            is McpServerTransport.StreamableHttp -> connectHttp(config, transport)
        }

    private suspend fun connectStdio(
        config: McpServerConfig,
        transportConfig: McpServerTransport.Stdio,
    ): McpConnectedClient {
        val process = ProcessBuilder(listOf(transportConfig.command) + transportConfig.arguments)
            .apply { environment().putAll(transportConfig.environment) }
            .start()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
        val client = newClient()
        return try {
            withTimeout(config.timeoutMs) {
                client.connect(transport)
            }
            SdkMcpConnectedClient(
                client = client,
                timeoutMs = config.timeoutMs,
                closeTransport = {
                    runCatching { runBlocking { client.close() } }
                    terminateProcessTree(process)
                },
                forceCloseTransport = { terminateProcessTree(process) },
            )
        } catch (error: Throwable) {
            runCatching { client.close() }
            terminateProcessTree(process)
            throw error
        }
    }

    private suspend fun connectHttp(
        config: McpServerConfig,
        transportConfig: McpServerTransport.StreamableHttp,
    ): McpConnectedClient {
        val httpClient = HttpClient(CIO) {
            install(SSE)
        }
        val transport = StreamableHttpClientTransport(
            client = httpClient,
            url = transportConfig.url,
        ) {
            transportConfig.headers.forEach { (key, value) ->
                headers.append(key, value)
            }
        }
        val client = newClient()
        return try {
            withTimeout(config.timeoutMs) {
                client.connect(transport)
            }
            SdkMcpConnectedClient(
                client = client,
                timeoutMs = config.timeoutMs,
                closeTransport = {
                    runCatching { runBlocking { client.close() } }
                    httpClient.close()
                },
                forceCloseTransport = {
                    runCatching { runBlocking { client.close() } }
                    httpClient.close()
                },
            )
        } catch (error: Throwable) {
            runCatching { client.close() }
            httpClient.close()
            throw error
        }
    }

    private fun newClient(): Client =
        Client(
            clientInfo = Implementation(
                name = "Gromozeka",
                version = "1.0.0",
            )
        )
}

private class SdkMcpConnectedClient(
    private val client: Client,
    private val timeoutMs: Long,
    private val closeTransport: () -> Unit,
    private val forceCloseTransport: () -> Unit,
) : McpConnectedClient {
    override val serverInfo: Implementation =
        checkNotNull(client.serverVersion) { "MCP initialization completed without server information" }

    override val serverInstructions: String?
        get() = client.serverInstructions

    override val supportsToolsListChanged: Boolean
        get() = client.serverCapabilities?.tools?.listChanged == true

    override suspend fun listAllTools(): List<Tool> {
        val tools = mutableListOf<Tool>()
        var cursor: String? = null
        do {
            val page = withTimeout(timeoutMs) {
                client.listTools(
                    ListToolsRequest(
                        params = cursor?.let(::PaginatedRequestParams),
                    )
                )
            }
            tools += page.tools
            cursor = page.nextCursor
        } while (cursor != null)
        return tools
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): String {
        val result = withTimeout(timeoutMs) {
            client.callTool(toolName, arguments)
        }
        val rendered = result.content.joinToString("\n") { block ->
            when (block) {
                is TextContent -> block.text
                is ImageContent -> "[Image: ${block.mimeType}]"
                is AudioContent -> "[Audio: ${block.mimeType}]"
                is EmbeddedResource -> "[Resource: ${block.resource.uri}]"
                is ResourceLink -> "[Resource Link: ${block.uri}]"
                else -> block.toString()
            }
        }
        check(result.isError != true) {
            rendered.ifBlank { "MCP tool '$toolName' returned an error without details" }
        }
        return rendered.ifBlank {
            result.structuredContent?.toString().orEmpty()
        }
    }

    override fun setToolsListChangedHandler(handler: () -> Unit) {
        if (!supportsToolsListChanged) {
            return
        }
        client.setNotificationHandler<ToolListChangedNotification>(
            Method.Defined.NotificationsToolsListChanged
        ) {
            handler()
            CompletableDeferred(Unit)
        }
    }

    override fun close() {
        closeTransport()
    }

    override fun forceClose() {
        forceCloseTransport()
    }
}

private fun terminateProcessTree(process: Process) {
    val root = process.toHandle()
    repeat(3) {
        root.descendants()
            .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
            .forEach(ProcessHandle::destroyForcibly)
        root.destroyForcibly()
        if (root.onExit().completeOnTimeout(root, 500, TimeUnit.MILLISECONDS).get() != root || !root.isAlive) {
            return
        }
    }
}
