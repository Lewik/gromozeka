package com.gromozeka.bot.services

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlin.random.Random

/**
 * HTTP/SSE MCP Server that replaces TCP-based McpSessionServer.
 * 
 * Responsibilities:
 * - Per-session HTTP endpoint with SSE streaming
 * - Port allocation (8080-8099 range)
 * - MCP protocol implementation using Kotlin MCP SDK
 * - Session-specific tool handling
 * - Resource cleanup on session termination
 * 
 * Architecture:
 * Claude CLI → HTTP/SSE → [This Server] → MCP Tools Implementation
 */
class McpHttpServer(
    private val sessionId: ClaudeSessionUuid,
    private val settingsService: SettingsService
) {
    companion object {
        private const val PORT_RANGE_START = 8080
        private const val PORT_RANGE_END = 8099
        private const val MAX_PORT_ATTEMPTS = 10
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
    }
    
    // Session state
    private var assignedPort: Int? = null
    private var configFilePath: String? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Debug tracking
    private var connectionCount = 0
    private var requestCount = 0
    
    /**
     * Starts MCP HTTP/SSE server for this session:
     * 1. Finds random available port in 8080-8099 range
     * 2. Starts Ktor CIO server with SSE support  
     * 3. Generates MCP config file for Claude CLI
     * 4. Returns config file path for Claude CLI
     */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        require(server == null) { "MCP HTTP server already started for session $sessionId" }
        
        try {
            // 1. Find random available port
            val port = findRandomAvailablePort()
            println("[MCP HTTP Server] Session $sessionId: Found available port $port")
            
            // 2. Start HTTP server
            startHttpServer(port)
            
            // 3. Generate MCP config file for Claude CLI
            val configPath = generateHttpMcpConfigFile(port)
            
            // 4. Save state
            assignedPort = port
            configFilePath = configPath
            
            println("[MCP HTTP Server] Session $sessionId: MCP server started on port $port with config: $configPath")
            return@withContext configPath
            
        } catch (e: Exception) {
            println("[MCP HTTP Server] Session $sessionId: Failed to start MCP server: ${e.message}")
            cleanup()
            throw e
        }
    }
    
    /**
     * Stops HTTP server and cleans up resources
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            stopHttpServer()
            cleanupConfigFile()
            println("[MCP HTTP Server] Session $sessionId: MCP server stopped")
        } catch (e: Exception) {
            println("[MCP HTTP Server] Session $sessionId: Error stopping MCP server: ${e.message}")
        } finally {
            cleanup()
        }
    }
    
    // === PORT MANAGEMENT ===
    
    /**
     * Finds random available port in range 8080-8099
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
    
    // === HTTP SERVER ===
    
    /**
     * Starts Ktor CIO HTTP server with MCP SSE endpoint
     */
    private suspend fun startHttpServer(port: Int) = withContext(Dispatchers.IO) {
        require(server == null) { "HTTP server already started on port $port" }
        
        try {
            println("[MCP HTTP Server] Starting HTTP server on port $port")
            
            server = embeddedServer(CIO, port = port, host = "localhost") {
                install(SSE)
                
                routing {
                    // Main MCP SSE endpoint
                    sse("/mcp/sse") {
                        connectionCount++
                        val currentConnectionId = connectionCount
                        
                        try {
                            println("[MCP HTTP Server] [$sessionId] NEW SSE CONNECTION #$currentConnectionId (port $port)")
                            println("[MCP HTTP Server] [$sessionId] Total connections served: $connectionCount")
                            
                            handleMcpSseConnection(currentConnectionId)
                            
                        } catch (e: Exception) {
                            println("[MCP HTTP Server] Error in SSE connection: ${e.message}")
                        } finally {
                            println("[MCP HTTP Server] [$sessionId] SSE CONNECTION #$currentConnectionId CLOSED")
                        }
                    }
                    
                    // Health check endpoint
                    get("/health") {
                        call.respondText("MCP HTTP Server for session ${sessionId.value} is running")
                    }
                    
                    // Debug info endpoint
                    get("/debug") {
                        val info = buildJsonObject {
                            put("sessionId", sessionId.value)
                            put("port", port)
                            put("connectionCount", connectionCount)
                            put("requestCount", requestCount)
                        }
                        call.respond(info)
                    }
                }
            }
            
            server!!.start(wait = false)
            
        } catch (e: Exception) {
            println("[MCP HTTP Server] Failed to start HTTP server on port $port: ${e.message}")
            throw e
        }
    }
    
    /**
     * Handles individual MCP SSE connection using MCP SDK patterns  
     * This function runs in SSE context where 'send' is available
     */
    private suspend fun ServerSSESession.handleMcpSseConnection(connectionId: Int) {
        // MCP over SSE protocol handling will be implemented here
        // For now, send initial server info and handle basic requests
        
        try {
            // Send initial server capabilities as SSE event
            val serverInfo = buildJsonObject {
                put("type", "server_info")
                put("sessionId", sessionId.value)
                put("connectionId", connectionId)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {
                        put("listChanged", false)
                    })
                })
                put("serverInfo", buildJsonObject {
                    put("name", "gromozeka-http-server")
                    put("version", "1.0.0")
                })
            }
            
            send(ServerSentEvent(
                data = McpJson.encodeToString(JsonObject.serializer(), serverInfo),
                event = "mcp_server_info"
            ))
            
            // Keep connection alive and handle incoming MCP requests
            // This is a placeholder - full MCP SSE protocol implementation needed
            delay(Long.MAX_VALUE)
            
        } catch (e: Exception) {
            println("[MCP HTTP Server] Error handling SSE connection: ${e.message}")
        }
    }
    
    /**
     * Stops HTTP server and cleans up resources
     */
    private suspend fun stopHttpServer() = withContext(Dispatchers.IO) {
        assignedPort?.let { port ->
            println("[MCP HTTP Server] Stopping HTTP server on port $port...")
        }
        
        try {
            server?.stop(SHUTDOWN_TIMEOUT_MS, SHUTDOWN_TIMEOUT_MS)
        } catch (e: Exception) {
            println("[MCP HTTP Server] Error during HTTP server shutdown: ${e.message}")
        } finally {
            server = null
            assignedPort?.let { port ->
                println("[MCP HTTP Server] HTTP server stopped on port $port")
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
    
    // === HTTP MCP CONFIG MANAGEMENT ===
    
    /**
     * Generates HTTP MCP config file for Claude CLI
     */
    private fun generateHttpMcpConfigFile(port: Int): String {
        val serverUrl = "http://localhost:$port/mcp/sse"
        
        // Create HTTP MCP configuration
        val config = buildJsonObject {
            put("gromozeka-self-control", buildJsonObject {
                put("url", serverUrl)
                put("transport", "sse")  // Server-Sent Events transport
            })
        }
        
        val configFileName = "mcp-http-config-${sessionId.value}.json"
        val configFile = File(settingsService.gromozekaHome, configFileName)
        
        // Ensure directory exists
        settingsService.gromozekaHome.mkdirs()
        
        configFile.writeText(McpJson.encodeToString(JsonObject.serializer(), config))
        
        println("[MCP HTTP Server] Session $sessionId: Generated HTTP config file: ${configFile.absolutePath}")
        println("[MCP HTTP Server] Config content: ${config}")
        return configFile.absolutePath
    }
    
    /**
     * Cleans up HTTP MCP config file
     */
    private fun cleanupConfigFile() {
        configFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    println("[MCP HTTP Server] Session $sessionId: Cleaned up HTTP config file: $path")
                }
            } catch (e: Exception) {
                println("[MCP HTTP Server] Session $sessionId: Failed to cleanup HTTP config file: ${e.message}")
            }
        }
    }
    
    // === MCP TOOLS (from original McpSessionServer) ===
    
    /**
     * Executes Hello World tool - placeholder for MCP tools implementation
     */
    private fun executeHelloWorld(connectionId: Int): CallToolResult {
        requestCount++
        println("[MCP HTTP Server] [$sessionId] EXECUTING HELLO_WORLD tool (port $assignedPort)")
        
        val content = listOf(
            TextContent("Hello from Gromozeka session ${sessionId.value}! 🤖 MCP HTTP server on port $assignedPort is working correctly. Request #$requestCount from connection #$connectionId.")
        )
        
        val result = CallToolResult(
            content = content,
            isError = false
        )
        
        println("[MCP HTTP Server] [$sessionId] HELLO_WORLD tool executed successfully")
        return result
    }
}