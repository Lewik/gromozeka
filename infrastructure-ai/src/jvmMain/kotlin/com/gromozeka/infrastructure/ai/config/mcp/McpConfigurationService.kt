package com.gromozeka.infrastructure.ai.config.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.gromozeka.domain.tool.AiToolCallback
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
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class McpConfigurationService(
    @Value("\${GROMOZEKA_HOME:\${user.home}/.gromozeka}")
    private val gromozemkaHome: String,
    @Qualifier("mcpCoroutineScope") private val coroutineScope: CoroutineScope
) {
    companion object {
        private val DEFAULT_INHERITED_ENV_VARS = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf(
                "APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PATH",
                "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP",
                "USERNAME", "USERPROFILE"
            )
        } else {
            listOf("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER")
        }
    }

    private val log = KLoggers.logger {}
    private val objectMapper = ObjectMapper()

    private var cachedConfig: McpConfig? = null

    @Volatile
    private var mcpClients: List<McpWrapperInterface> = emptyList()

    @PostConstruct
    fun initialize() {
        loadConfiguration()
        // MCP clients are NOT started automatically
        // They must be started explicitly via startMcpClientsWithProgress()
        // to ensure proper loading screen UX
    }

    private fun loadConfiguration() {
        val mcpFile = File("$gromozemkaHome/mcp.json")

        if (!mcpFile.exists()) {
            log.warn { "MCP config not found: ${mcpFile.absolutePath}" }
            log.info { "Create $gromozemkaHome/mcp.json to configure MCP servers" }
            cachedConfig = McpConfig(emptyMap())
            return
        }

        try {
            cachedConfig = objectMapper.readValue(mcpFile, McpConfig::class.java)
            val totalServers = cachedConfig!!.mcpServers.size
            val activeServers = cachedConfig!!.mcpServers.count { !it.value.disabled }

            log.info { "Loaded MCP config with $activeServers/$totalServers active servers" }

            cachedConfig!!.mcpServers.forEach { (name, config) ->
                if (config.disabled) {
                    log.debug { "  - $name: disabled" }
                } else {
                    when (config.transportType) {
                        TransportType.STDIO -> log.info { "  - $name: stdio (${config.command})" }
                        TransportType.STREAMABLE_HTTP -> log.info { "  - $name: streamable HTTP (${config.url})" }
                        TransportType.UNKNOWN -> log.warn { "  - $name: unknown transport type (missing command or url)" }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load mcp.json from ${mcpFile.absolutePath}" }
            cachedConfig = McpConfig(emptyMap())
        }
    }

    private fun getDefaultEnvironment(): Map<String, String> {
        return System.getenv()
            .filterKeys { it in DEFAULT_INHERITED_ENV_VARS }
            .filterValues { value -> !value.startsWith("()") }
    }

    suspend fun startMcpClientsWithProgress(
        onProgress: (serverName: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ) {
        val stdioServers = getStdioServers()
        val streamableHttpServers = getStreamableHttpServers()

        val totalServers = stdioServers.size + streamableHttpServers.size

        if (totalServers == 0) {
            log.info { "No MCP servers configured" }
            return
        }

        val clients = mutableListOf<McpWrapperInterface>()
        var current = 0

        // Start stdio servers
        for ((name, config) in stdioServers) {
            try {
                current++
                onProgress(name, current, totalServers)

                log.info { "Starting MCP client: $name ($current/$totalServers)" }
                log.debug { "  Command: ${config.command}" }
                log.debug { "  Args: ${config.args?.joinToString(" ")}" }

                val processBuilder = ProcessBuilder(
                    listOf(config.command!!) + (config.args ?: emptyList())
                )

                // Initialize with default environment (PATH, HOME, SHELL, etc.)
                val defaultEnv = getDefaultEnvironment()
                log.debug { "  Default environment keys: ${defaultEnv.keys.joinToString()}" }
                log.debug { "  PATH: ${defaultEnv["PATH"]}" }
                processBuilder.environment().putAll(defaultEnv)

                // Apply user-provided environment variables (overrides defaults)
                config.env?.let { envMap ->
                    log.debug { "  User environment keys: ${envMap.keys.joinToString()}" }
                    processBuilder.environment().putAll(envMap)
                }

                val process = processBuilder.start()

                val input = process.inputStream.asSource().buffered()
                val output = process.outputStream.asSink().buffered()

                val transport = StdioClientTransport(
                    input = input,
                    output = output,
                    error = process.errorStream.asSource().buffered()
                )

                val client = Client(
                    clientInfo = Implementation(
                        name = "Gromozeka",
                        version = "1.0.0"
                    )
                )

                val wrapper = McpStdioClientWrapper(name, client, transport, process)

                wrapper.initialize()

                clients.add(wrapper)
                log.info { "Successfully started MCP client: $name" }
            } catch (e: Exception) {
                log.error(e) { "Failed to start MCP client: $name" }
            }
        }

        for ((name, config) in streamableHttpServers) {
            try {
                current++
                onProgress(name, current, totalServers)

                log.info { "Starting MCP streamable HTTP client: $name ($current/$totalServers)" }
                log.debug { "  URL: ${config.url}" }

                val httpClient = HttpClient(CIO) {
                    install(SSE)
                }
                val transport = StreamableHttpClientTransport(
                    client = httpClient,
                    url = config.url!!,
                ) {
                    config.headers?.forEach { (key, value) ->
                        headers.append(key, value)
                    }
                }
                val client = Client(
                    clientInfo = Implementation(
                        name = "Gromozeka",
                        version = "1.0.0"
                    )
                )

                val wrapper = McpRemoteClientWrapper(name, client, transport, httpClient)

                wrapper.initialize()

                clients.add(wrapper)
                log.info { "Successfully started MCP streamable HTTP client: $name" }
            } catch (e: Exception) {
                log.error(e) { "Failed to start MCP streamable HTTP client: $name" }
            }
        }

        mcpClients = clients
        log.info {
            "Initialized ${mcpClients.size} MCP client(s) " +
                "(${stdioServers.size} stdio, ${streamableHttpServers.size} streamable HTTP)"
        }
    }

    fun getStdioServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers
            ?.filter { it.value.transportType == TransportType.STDIO && !it.value.disabled }
            ?: emptyMap()
    }

    fun getStreamableHttpServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers
            ?.filter { it.value.transportType == TransportType.STREAMABLE_HTTP && !it.value.disabled }
            ?: emptyMap()
    }

    fun getAllServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers ?: emptyMap()
    }

    /**
     * Gets MCP client wrapper by server name.
     * 
     * @param serverName name of MCP server (e.g., "selene", "brave-search")
     * @return MCP client wrapper or null if not found
     */
    fun getMcpClient(serverName: String): McpWrapperInterface? {
        return mcpClients.find { it.name.equals(serverName, ignoreCase = true) }
    }

    fun getTools(): List<AiToolCallback> {
        return runBlocking {
            val callbacks = mutableListOf<AiToolCallback>()

            for (wrapper in mcpClients) {
                try {
                    val allTools = wrapper.listTools()
                    
                    val serverConfig = cachedConfig?.mcpServers?.get(wrapper.name)
                    val tools = filterToolsForServer(wrapper.name, allTools, serverConfig)
                    
                    log.info { "=".repeat(80) }
                    log.info { "MCP Server: ${wrapper.name} - ${tools.size} tools (${allTools.size} total, ${allTools.size - tools.size} filtered out)" }
                    log.info { "=".repeat(80) }

                    tools.forEach { tool ->
                        log.info { "" }
                        log.info { "## ${tool.name}" }
                        log.info { "" }
                        log.info { tool.description ?: "No description" }
                        log.info { "" }

                        log.info { "**Parameters (JSON Schema):**" }
                        log.info { "```json" }
                        log.info { McpJson.encodeToString(ToolSchema.serializer(), tool.inputSchema) }
                        log.info { "```" }
                        log.info { "" }

                        callbacks.add(McpToolCallbackAdapter(wrapper, tool, coroutineScope))
                    }
                    log.debug { "Registered ${tools.size} tools from ${wrapper.name}" }
                } catch (e: Exception) {
                    log.error(e) { "Failed to get tools from ${wrapper.name}" }
                }
            }

            if (callbacks.isNotEmpty()) {
                log.info { "Total MCP tools registered: ${callbacks.size}" }
            }

            callbacks
        }
    }

    internal fun filterToolsForServer(
        serverName: String,
        allTools: List<Tool>,
        serverConfig: ServerConfig?
    ): List<Tool> {
        val allowedTools = serverConfig?.allowedTools
        val excludedTools = serverConfig?.excludedTools.orEmpty()

        val allowedFiltered = if (allowedTools == null) {
            allTools
        } else {
            val allowedToolNames = allowedTools.toSet()
            val filtered = allTools.filter { it.name in allowedToolNames }
            val missingAllowedTools = allowedToolNames - allTools.map { it.name }.toSet()

            log.info {
                "MCP Server '$serverName': allowed ${filtered.size}/${allTools.size} tools (${allowedTools.joinToString(", ")})"
            }

            if (missingAllowedTools.isNotEmpty()) {
                log.warn {
                    "MCP Server '$serverName': allowedTools contains missing tools (${missingAllowedTools.joinToString(", ")})"
                }
            }

            filtered
        }

        if (excludedTools.isEmpty()) {
            return allowedFiltered
        }

        val excludedToolNames = excludedTools.toSet()
        val filtered = allowedFiltered.filterNot { it.name in excludedToolNames }
        log.info {
            "MCP Server '$serverName': excluded ${allowedFiltered.size - filtered.size} tools (${excludedTools.joinToString(", ")})"
        }

        return filtered
    }

    suspend fun reloadClients() {
        log.info { "Reloading MCP clients..." }
        shutdown()
        loadConfiguration()
        startMcpClientsWithProgress()
    }

    @PreDestroy
    fun shutdown() {
        log.info { "Shutting down MCP configuration service..." }

        // Cancel coroutine scope first to stop all running operations
        coroutineScope.cancel("MCP service shutdown")

        if (mcpClients.isEmpty()) {
            log.info { "No MCP clients to stop" }
            return
        }

        log.info { "Force-stopping ${mcpClients.size} MCP client(s)..." }

        // Forcefully stop all clients without blocking
        mcpClients.forEach { wrapper ->
            try {
                wrapper.forceClose()
            } catch (e: Exception) {
                log.error(e) { "Error force-closing MCP client: ${wrapper.name}" }
            }
        }

        mcpClients = emptyList()
        log.info { "All MCP clients stopped" }
    }
}

private data class McpStdioClientWrapper(
    override val name: String,
    private val client: Client,
    private val transport: StdioClientTransport,
    private val process: Process,
) : McpWrapperInterface {
    private val log = KLoggers.logger {}

    override suspend fun initialize() {
        client.connect(transport)

        val serverInfo = client.serverVersion
        log.info { "Connected to MCP server: $name" }
        log.info { "  Server: ${serverInfo?.name} v${serverInfo?.version}" }
    }

    override suspend fun listTools(): List<Tool> {
        return client.listTools().tools
    }

    override suspend fun callTool(toolName: String, arguments: Map<String, Any?>): String {
        val startTime = System.currentTimeMillis()
        log.info { "[MCP] Calling tool '$toolName' on server '$name' (timeout: 40s)" }
        
        val result = try {
            withTimeout(40_000) {
                client.callTool(toolName, arguments)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            log.error(e) { "[MCP] Tool '$toolName' failed after ${duration}ms: ${e.message}" }
            throw IllegalStateException("MCP tool '$toolName' failed after ${duration}ms", e)
        }
        
        val duration = System.currentTimeMillis() - startTime
        log.info { "[MCP] Tool '$toolName' completed in ${duration}ms" }

        return renderToolResultContent(result.content)
    }

    override fun close() {
        try {
            log.info { "Closing MCP client: $name" }

            // Get process handle and descendants BEFORE closing
            val handle = process.toHandle()
            val descendants = handle.descendants().toList()

            // Close client - this may kill parent process
            runBlocking { client.close() }

            // Kill all descendant processes (prevents orphaned subprocesses)
            descendants.forEach { it.destroyForcibly() }

            // Kill parent process if still alive
            if (handle.isAlive()) {
                handle.destroyForcibly()
            }

            // Wait for termination with timeout
            val terminated = handle.onExit()
                .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .handle { _, ex -> ex == null }
                .get()

            if (terminated) {
                log.info { "MCP client $name closed successfully" }
            } else {
                log.warn { "MCP client $name process did not terminate cleanly" }
            }
        } catch (e: Exception) {
            log.error(e) { "Error closing MCP client: $name" }
        }
    }

    override fun forceClose() {
        try {
            log.info { "Force-closing MCP client: $name" }

            // Get process handle and descendants
            val handle = process.toHandle()
            val descendants = handle.descendants().toList()

            // Kill all descendant processes first
            descendants.forEach { it.destroyForcibly() }

            // Kill parent process
            handle.destroyForcibly()

            log.info { "Force-closed MCP client: $name" }
        } catch (e: Exception) {
            log.error(e) { "Error force-closing MCP client: $name" }
        }
    }
}

private data class McpRemoteClientWrapper(
    override val name: String,
    private val client: Client,
    private val transport: StreamableHttpClientTransport,
    private val httpClient: HttpClient,
) : McpWrapperInterface {
    private val log = KLoggers.logger {}

    override suspend fun initialize() {
        client.connect(transport)

        val serverInfo = client.serverVersion
        log.info { "Connected to MCP streamable HTTP server: $name" }
        log.info { "  Server: ${serverInfo?.name} v${serverInfo?.version}" }
    }

    override suspend fun listTools(): List<Tool> {
        return client.listTools().tools
    }

    override suspend fun callTool(toolName: String, arguments: Map<String, Any?>): String {
        val startTime = System.currentTimeMillis()
        log.info { "[MCP] Calling tool '$toolName' on streamable HTTP server '$name' (timeout: 40s)" }

        val result = try {
            withTimeout(40_000) {
                client.callTool(toolName, arguments)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            log.error(e) { "[MCP] Tool '$toolName' failed after ${duration}ms: ${e.message}" }
            throw IllegalStateException("MCP tool '$toolName' failed after ${duration}ms", e)
        }

        val duration = System.currentTimeMillis() - startTime
        log.info { "[MCP] Tool '$toolName' completed in ${duration}ms" }

        return renderToolResultContent(result.content)
    }

    override fun close() {
        try {
            log.info { "Closing MCP streamable HTTP client: $name" }
            runBlocking { client.close() }
            httpClient.close()
            log.info { "MCP streamable HTTP client $name closed successfully" }
        } catch (e: Exception) {
            log.error(e) { "Error closing MCP streamable HTTP client: $name" }
        }
    }

    override fun forceClose() {
        close()
    }
}

private fun renderToolResultContent(content: List<io.modelcontextprotocol.kotlin.sdk.types.ContentBlock>): String =
    content.joinToString("\n") {
        when (it) {
            is TextContent -> it.text
            is ImageContent -> "[Image: ${it.mimeType}]"
            is AudioContent -> "[Audio: ${it.mimeType}]"
            is EmbeddedResource -> "[Resource: ${it.resource.uri}]"
            is ResourceLink -> "[Resource Link: ${it.uri}]"
            else -> it.toString()
        }
    }
