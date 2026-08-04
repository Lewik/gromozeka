package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.tool.AiToolResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.UnknownResourceContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.util.Base64
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

    suspend fun callToolResult(
        toolName: String,
        arguments: Map<String, Any?>,
    ): List<AiToolResult> = listOf(AiToolResult.Text(callTool(toolName, arguments)))

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
        val workingDirectory = if (transportConfig.ephemeralWorkingDirectory) {
            Files.createTempDirectory("gromozeka-mcp-${config.id.value}-").toFile()
        } else {
            null
        }
        val process = try {
            ProcessBuilder(listOf(transportConfig.command) + transportConfig.arguments)
                .apply {
                    environment().putAll(transportConfig.environment)
                    workingDirectory?.let(::directory)
                }
                .start()
        } catch (error: Throwable) {
            workingDirectory?.deleteRecursively()
            throw error
        }
        val closeProcess = {
            terminateProcessTree(process)
            workingDirectory?.deleteRecursively()
            Unit
        }
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
                    closeProcess()
                },
                forceCloseTransport = closeProcess,
            )
        } catch (error: Throwable) {
            runCatching { client.close() }
            closeProcess()
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
    ): String = executeTool(toolName, arguments).renderText()

    override suspend fun callToolResult(
        toolName: String,
        arguments: Map<String, Any?>,
    ): List<AiToolResult> = executeTool(toolName, arguments)

    private suspend fun executeTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): List<AiToolResult> {
        val result = withTimeout(timeoutMs) {
            client.callTool(toolName, arguments)
        }
        val mappedResults = result.toAiToolResults(toolName)
        val toolResults = when {
            mappedResults.isEmpty() ->
                listOf(AiToolResult.Text(result.structuredContent?.toString().orEmpty()))
            mappedResults.renderText().isBlank() && result.structuredContent != null ->
                listOf(AiToolResult.Text(result.structuredContent.toString()))
            else -> mappedResults
        }
        check(result.isError != true) {
            toolResults.renderText().ifBlank {
                "MCP tool '$toolName' returned an error without details"
            }
        }
        return toolResults
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

internal fun CallToolResult.toAiToolResults(toolName: String): List<AiToolResult> =
    content.mapIndexed { index, block ->
        when (block) {
            is TextContent -> AiToolResult.Text(block.text)
            is ImageContent -> block.toBinaryResult(toolName, index)
            is AudioContent -> block.toBinaryResult(toolName, index)
            is EmbeddedResource -> when (val resource = block.resource) {
                is TextResourceContents -> AiToolResult.Text(
                    "[Resource: ${resource.uri}]\n${resource.text}"
                )
                is BlobResourceContents -> AiToolResult.Binary(
                    content = Base64.getDecoder().decode(resource.blob),
                    fileName = resource.uri.fileNameOrNull()
                        ?: binaryFileName(toolName, index, resource.mimeType),
                    mediaType = resource.mimeType?.takeIf(String::isNotBlank)
                        ?: "application/octet-stream",
                )
                is UnknownResourceContents -> AiToolResult.Text("[Resource: ${resource.uri}]")
            }
            is ResourceLink -> AiToolResult.Text(
                buildString {
                    append("[Resource Link: ${block.uri}]")
                    append("\nName: ${block.name}")
                    block.title?.takeIf(String::isNotBlank)?.let { append("\nTitle: $it") }
                    block.description?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                }
            )
            else -> AiToolResult.Text(block.toString())
        }
    }

private fun ImageContent.toBinaryResult(toolName: String, index: Int) =
    AiToolResult.Binary(
        content = Base64.getDecoder().decode(data),
        fileName = binaryFileName(toolName, index, mimeType),
        mediaType = mimeType,
    )

private fun AudioContent.toBinaryResult(toolName: String, index: Int) =
    AiToolResult.Binary(
        content = Base64.getDecoder().decode(data),
        fileName = binaryFileName(toolName, index, mimeType),
        mediaType = mimeType,
    )

private fun List<AiToolResult>.renderText(): String =
    joinToString("\n") { result ->
        when (result) {
            is AiToolResult.Text -> result.content
            is AiToolResult.Binary -> "[Binary: ${result.mediaType}, ${result.fileName}]"
        }
    }

private fun binaryFileName(toolName: String, index: Int, mediaType: String?): String {
    val safeToolName = toolName.replace(NON_FILE_NAME_CHARACTER, "_").take(80)
    val extension = when (mediaType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "application/pdf" -> "pdf"
        else -> "bin"
    }
    return "$safeToolName-${index + 1}.$extension"
}

private fun String.fileNameOrNull(): String? =
    substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .takeIf(String::isNotBlank)
        ?.replace(NON_FILE_NAME_CHARACTER, "_")
        ?.take(120)

private val NON_FILE_NAME_CHARACTER = Regex("[^A-Za-z0-9._-]")

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
