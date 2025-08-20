package com.gromozeka.bot.services

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

/**
 * Unified MCP Session Server that combines session management and TCP server functionality.
 *
 * Responsibilities:
 * - Per-session port allocation (20000-60000 range)
 * - TCP server lifecycle management
 * - MCP protocol implementation (initialize, tools/list, tools/call)
 * - Session-specific config file generation/cleanup
 * - Resource cleanup on session termination
 *
 * Architecture:
 * mcp-proxy.jar ↔ TCP Socket ↔ [This Server] ↔ MCP Tools Implementation
 */
class McpSessionServer(
    private val sessionId: ClaudeSessionUuid,
    private val settingsService: SettingsService,
) {
    companion object {
        private const val PORT_RANGE_START = 20000
        private const val PORT_RANGE_END = 60000
        private const val MAX_PORT_ATTEMPTS = 10
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
    }

    // Session state
    private var assignedPort: Int? = null
    private var configFilePath: String? = null

    // TCP server state
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Starts MCP server for this session:
     * 1. Finds random available port
     * 2. Starts TCP server on that port
     * 3. Generates MCP config file
     * 4. Returns config file path for Claude CLI
     */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        require(serverSocket == null) { "MCP server already started for session $sessionId" }

        try {
            // 1. Find random available port
            val port = findRandomAvailablePort()
            println("[MCP Session Server] Session $sessionId: Found available port $port")

            // 2. Start TCP server
            startTcpServer(port)

            // 3. Generate MCP config file
            val configPath = generateMcpConfigFile(port)

            // 4. Save state
            this@McpSessionServer.assignedPort = port
            this@McpSessionServer.configFilePath = configPath

            println("[MCP Session Server] Session $sessionId: MCP server started on port $port")
            return@withContext configPath

        } catch (e: Exception) {
            println("[MCP Session Server] Session $sessionId: Failed to start MCP server: ${e.message}")
            cleanup()
            throw e
        }
    }

    /**
     * Stops MCP server and cleans up resources
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            stopTcpServer()
            cleanupConfigFile()
            println("[MCP Session Server] Session $sessionId: MCP server stopped")
        } catch (e: Exception) {
            println("[MCP Session Server] Session $sessionId: Error stopping MCP server: ${e.message}")
        } finally {
            cleanup()
        }
    }

    // === PORT MANAGEMENT ===

    /**
     * Finds random available port in range 20000-60000
     */
    private fun findRandomAvailablePort(): Int {
        repeat(MAX_PORT_ATTEMPTS) {
            val port = Random.nextInt(PORT_RANGE_START, PORT_RANGE_END + 1)

            if (isPortAvailable(port)) {
                return port
            }
        }

        throw IllegalStateException("Could not find available port after $MAX_PORT_ATTEMPTS attempts in range $PORT_RANGE_START-$PORT_RANGE_END")
    }

    /**
     * Checks if port is available by trying to bind to it
     */
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: IOException) {
            false
        }
    }

    // === TCP SERVER ===

    /**
     * Starts TCP server on assigned port
     */
    private suspend fun startTcpServer(port: Int) = withContext(Dispatchers.IO) {
        require(serverSocket == null) { "TCP server already started on port $port" }

        try {
            println("[MCP Session Server] Starting TCP server on port $port")

            serverJob = scope.launch {
                serverSocket = ServerSocket(port).also { server ->
                    println("[MCP Session Server] Listening on localhost:$port")

                    while (!server.isClosed && isActive) {
                        try {
                            val client = server.accept()
                            println("[MCP Session Server] New client connected: ${client.remoteSocketAddress}")

                            // Handle each client in separate coroutine
                            launch {
                                handleMcpClient(client)
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                println("[MCP Session Server] Error accepting client: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[MCP Session Server] Failed to start TCP server on port $port: ${e.message}")
            throw e
        }
    }

    /**
     * Stops TCP server and cleans up resources
     */
    private suspend fun stopTcpServer() = withContext(Dispatchers.IO) {
        assignedPort?.let { port ->
            println("[MCP Session Server] Stopping TCP server on port $port...")
        }

        try {
            serverJob?.cancel()
            serverSocket?.close()

            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                scope.coroutineContext[Job]?.join()
            }
        } catch (e: Exception) {
            println("[MCP Session Server] Error during TCP server shutdown: ${e.message}")
        } finally {
            serverSocket = null
            serverJob = null
            assignedPort?.let { port ->
                println("[MCP Session Server] TCP server stopped on port $port")
            }
        }
    }

    /**
     * Handles individual MCP client connection
     */
    private suspend fun handleMcpClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                println("[MCP Session Server] Handling client: ${socket.remoteSocketAddress}")

                reader.useLines { lines ->
                    lines.forEach { jsonLine ->
                        if (jsonLine.isNotBlank()) {
                            val response = handleMcpMessage(jsonLine)
                            writer.write(response)
                            writer.newLine()
                            writer.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[MCP Session Server] Error handling client: ${e.message}")
        } finally {
            println("[MCP Session Server] Client disconnected")
        }
    }

    // === MCP PROTOCOL ===

    /**
     * Processes JSON-RPC message and returns response using MCP SDK types
     */
    private suspend fun handleMcpMessage(rawJsonLine: String): String {
        println("[MCP Session Server] Received: ${rawJsonLine.take(200)}...")

        return try {
            val request = McpJson.decodeFromString<JSONRPCRequest>(rawJsonLine)

            val response = when (request.method) {
                Method.Defined.Initialize.value -> handleInitialize(request.id, request.params.jsonObject)
                Method.Defined.ToolsList.value -> handleToolsList(request.id)
                Method.Defined.ToolsCall.value -> handleToolCall(request.id, request.params.jsonObject)
                else -> createErrorResponse(
                    request.id,
                    ErrorCode.Defined.MethodNotFound,
                    "Unknown method: ${request.method}"
                )
            }

            McpJson.encodeToString(JSONRPCResponse.serializer(), response)
        } catch (e: Exception) {
            println("[MCP Session Server] Error processing message: ${e.message}")

            val errorResponse = createErrorResponse(
                id = RequestId.NumberId(0),
                code = ErrorCode.Defined.ParseError,
                message = "Failed to parse JSON-RPC request: ${e.message}"
            )
            McpJson.encodeToString(JSONRPCResponse.serializer(), errorResponse)
        }
    }

    /**
     * Handles MCP initialize request using typed SDK objects
     */
    private fun handleInitialize(id: RequestId, params: JsonObject?): JSONRPCResponse {
        val result = InitializeResult(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = null)
            ),
            serverInfo = Implementation(
                name = "gromozeka-session-server",
                version = "1.0.0"
            )
        )

        return JSONRPCResponse(id = id, result = result)
    }

    /**
     * Handles tools/list request using typed Tool objects
     */
    private fun handleToolsList(id: RequestId): JSONRPCResponse {
        val helloWorldTool = Tool(
            name = "hello_world",
            description = "Test tool that returns a greeting from Gromozeka session ${sessionId.value}",
            inputSchema = Tool.Input(
                properties = buildJsonObject {},
                required = emptyList()
            ),
            outputSchema = null,
            annotations = null
        )

        val result = ListToolsResult(
            tools = listOf(helloWorldTool),
            nextCursor = null
        )

        return JSONRPCResponse(id = id, result = result)
    }

    /**
     * Handles tools/call request using typed objects
     */
    private suspend fun handleToolCall(id: RequestId, params: JsonObject?): JSONRPCResponse {
        if (params == null) {
            return createErrorResponse(id, ErrorCode.Defined.InvalidParams, "Missing params for tool call")
        }

        val toolName = params["name"]?.jsonPrimitive?.content
        val arguments = params["arguments"]?.jsonObject ?: buildJsonObject {}

        return when (toolName) {
            "hello_world" -> executeHelloWorld(id, arguments)
            else -> createErrorResponse(id, ErrorCode.Unknown(-32001), "Unknown tool: $toolName")
        }
    }

    /**
     * Executes Hello World tool using typed CallToolResult
     */
    private fun executeHelloWorld(id: RequestId, arguments: JsonObject): JSONRPCResponse {
        val content = listOf(
            TextContent("Hello from Gromozeka session ${sessionId.value}! 🤖 MCP session server is working correctly.")
        )

        val result = CallToolResult(
            content = content,
            isError = false
        )

        return JSONRPCResponse(id = id, result = result)
    }

    /**
     * Creates JSON-RPC error response using typed error objects
     */
    private fun createErrorResponse(id: RequestId, code: ErrorCode, message: String): JSONRPCResponse {
        val error = JSONRPCError(code = code, message = message)
        return JSONRPCResponse(id = id, error = error)
    }

    // === CONFIG FILE MANAGEMENT ===

    /**
     * Generates MCP config file for this session
     */
    private fun generateMcpConfigFile(port: Int): String {
        val template = loadMcpTemplate()
        val jarPath = JarResourceManager.getMcpProxyJarPath(settingsService)

        val config = template
            .replace("{{JAR_PATH}}", jarPath)
            .replace("{{PORT_NUMBER}}", port.toString())

        val configFileName = "mcp-config-${sessionId.value}.json"
        val configFile = File(settingsService.gromozekaHome, configFileName)

        configFile.writeText(config)

        println("[MCP Session Server] Session $sessionId: Generated config file: ${configFile.absolutePath}")
        return configFile.absolutePath
    }

    /**
     * Loads MCP config template from resources
     */
    private fun loadMcpTemplate(): String {
        val templateStream = this::class.java.getResourceAsStream("/mcp-config-template.json")
            ?: throw IllegalStateException("MCP config template not found in resources")

        return templateStream.bufferedReader().use { it.readText() }
    }

    /**
     * Cleans up MCP config file
     */
    private fun cleanupConfigFile() {
        configFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    println("[MCP Session Server] Session $sessionId: Cleaned up config file: $path")
                }
            } catch (e: Exception) {
                println("[MCP Session Server] Session $sessionId: Failed to cleanup config file: ${e.message}")
            }
        }
    }

    /**
     * Resets internal state
     */
    private fun cleanup() {
        assignedPort = null
        configFilePath = null
    }
}