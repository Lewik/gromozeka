package com.gromozeka.bot.config.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.springframework.ai.tool.ToolCallback
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
    private val log = KLoggers.logger {}
    private val objectMapper = ObjectMapper()

    private var cachedConfig: McpConfig? = null

    @Volatile
    private var mcpClients: List<McpWrapperInterface> = emptyList()

    @PostConstruct
    fun initialize() {
        loadConfiguration()

        coroutineScope.launch {
            try {
                startMcpClients()
            } catch (e: Exception) {
                log.error(e) { "Failed to initialize MCP clients" }
            }
        }
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
                        TransportType.SSE -> log.info { "  - $name: SSE (${config.url})" }
                        TransportType.UNKNOWN -> log.warn { "  - $name: unknown transport type (missing command or url)" }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load mcp.json from ${mcpFile.absolutePath}" }
            cachedConfig = McpConfig(emptyMap())
        }
    }

    private suspend fun startMcpClients() {
        val stdioServers = getStdioServers()
        val sseServers = getSseServers()

        if (stdioServers.isEmpty() && sseServers.isEmpty()) {
            log.info { "No MCP servers configured" }
            return
        }

        val clients = mutableListOf<McpWrapperInterface>()

        // Start stdio servers
        for ((name, config) in stdioServers) {
            try {
                log.info { "Starting MCP client: $name" }
                log.debug { "  Command: ${config.command}" }
                log.debug { "  Args: ${config.args?.joinToString(" ")}" }

                val processBuilder = ProcessBuilder(
                    listOf(config.command!!) + (config.args ?: emptyList())
                )

                config.env?.let { envMap ->
                    processBuilder.environment().putAll(envMap)
                }

                val process = processBuilder.start()

                val input = process.inputStream.asSource().buffered()
                val output = process.outputStream.asSink().buffered()

                val transport = StdioClientTransport(
                    input = input,
                    output = output
                )

                val client = Client(
                    clientInfo = Implementation(
                        name = "Gromozeka",
                        version = "1.0.0"
                    )
                )

                val wrapper = McpClientWrapper(name, client, transport, process, coroutineScope)

                wrapper.initialize()

                clients.add(wrapper)
                log.info { "Successfully started MCP client: $name" }
            } catch (e: Exception) {
                log.error(e) { "Failed to start MCP client: $name" }
            }
        }

        // Start SSE servers
        for ((name, config) in sseServers) {
            try {
                log.info { "Starting MCP SSE client: $name" }
                log.debug { "  URL: ${config.url}" }
                log.debug { "  SSE Endpoint: ${config.sseEndpoint ?: "/sse"}" }

                val transport = io.modelcontextprotocol.client.transport.HttpClientSseClientTransport.builder(config.url!!)
                    .apply {
                        config.sseEndpoint?.let { sseEndpoint(it) }
                        config.headers?.let { headers ->
                            customizeRequest { builder ->
                                headers.forEach { (key, value) ->
                                    builder.header(key, value)
                                }
                            }
                        }
                    }
                    .build()

                val client = io.modelcontextprotocol.client.McpClient.async(transport)
                    .clientInfo(io.modelcontextprotocol.spec.McpSchema.Implementation("Gromozeka", "1.2.0"))
                    .requestTimeout(java.time.Duration.ofSeconds(config.timeout?.toLong() ?: 30))
                    .build()

                val wrapper = McpSseClientWrapper(name, client, transport, coroutineScope)

                wrapper.initialize()

                clients.add(wrapper)
                log.info { "Successfully started MCP SSE client: $name" }
            } catch (e: Exception) {
                log.error(e) { "Failed to start MCP SSE client: $name" }
            }
        }

        mcpClients = clients
        log.info { "Initialized ${mcpClients.size} MCP client(s) (${stdioServers.size} stdio, ${sseServers.size} SSE)" }
    }

    fun getStdioServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers
            ?.filter { it.value.transportType == TransportType.STDIO && !it.value.disabled }
            ?: emptyMap()
    }

    fun getSseServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers
            ?.filter { it.value.transportType == TransportType.SSE && !it.value.disabled }
            ?: emptyMap()
    }

    fun getAllServers(): Map<String, ServerConfig> {
        return cachedConfig?.mcpServers ?: emptyMap()
    }

    fun getToolCallbacks(): List<ToolCallback> {
        return runBlocking {
            val callbacks = mutableListOf<ToolCallback>()

            for (wrapper in mcpClients) {
                try {
                    val tools = wrapper.listTools()
                    tools.forEach { tool ->
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

    suspend fun reloadClients() {
        log.info { "Reloading MCP clients..." }
        shutdown()
        loadConfiguration()
        startMcpClients()
    }

    @PreDestroy
    fun shutdown() {
        runBlocking {
            if (mcpClients.isEmpty()) {
                return@runBlocking
            }

            log.info { "Stopping ${mcpClients.size} MCP client(s)..." }

            mcpClients.forEach { wrapper ->
                try {
                    wrapper.close()
                } catch (e: Exception) {
                    log.error(e) { "Error closing MCP client: ${wrapper.name}" }
                }
            }

            mcpClients = emptyList()
            log.info { "All MCP clients stopped" }
        }
    }
}

data class McpClientWrapper(
    override val name: String,
    private val client: Client,
    private val transport: StdioClientTransport,
    private val process: Process,
    private val coroutineScope: CoroutineScope
) : McpWrapperInterface {
    private val log = KLoggers.logger {}

    override suspend fun initialize() {
        client.connect(transport)

        val serverInfo = client.serverVersion
        log.info { "Connected to MCP server: $name" }
        log.info { "  Server: ${serverInfo?.name} v${serverInfo?.version}" }
    }

    override suspend fun listTools(): List<Tool> {
        val result = client.listTools() ?: throw IllegalStateException("listTools returned null")
        return result.tools
    }

    override suspend fun callTool(toolName: String, arguments: Map<String, Any>): String {
        val result = client.callTool(toolName, arguments)

        if (result == null) {
            return "Tool returned no result"
        }

        return result.content.joinToString("\n") { content ->
            when (content) {
                is io.modelcontextprotocol.kotlin.sdk.TextContent -> content.text ?: ""
                is io.modelcontextprotocol.kotlin.sdk.ImageContent -> "[Image: ${content.mimeType ?: "image"}]"
                else -> content.toString()
            }
        }
    }

    override fun close() {
        runBlocking {
            try {
                client.close()
                transport.close()
                process.destroy()
                process.waitFor()
            } catch (e: Exception) {
                log.error(e) { "Error closing client: $name" }
            }
        }
    }
}
