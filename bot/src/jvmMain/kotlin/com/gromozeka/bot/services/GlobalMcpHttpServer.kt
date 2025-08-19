package com.gromozeka.bot.services

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

/**
 * Global MCP HTTP/SSE Server for all Claude sessions.
 * 
 * Architecture:
 * - Single HTTP server on configurable port (default: 49152)
 * - Serves all Claude sessions from one endpoint
 * - MCP tools are session-agnostic (no state isolation needed)
 * - Generates one global config file for all sessions
 * 
 * Benefits:
 * - Simplified port management (one known port)
 * - Easier debugging and monitoring  
 * - Reduced resource usage
 * - Standard MCP tool behavior across sessions
 */
class GlobalMcpHttpServer(
    private val settingsService: SettingsService
) {
    companion object {
        private const val SHUTDOWN_TIMEOUT_MS = 3000L
        private const val MCP_SERVER_NAME = "gromozeka-self-control"
    }
    
    // Server state
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var configFilePath: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Debug tracking
    private var connectionCount = 0
    private var requestCount = 0
    
    /**
     * Starts global MCP HTTP/SSE server:
     * 1. Starts Ktor server on configured port
     * 2. Generates global MCP config file
     * 3. Returns config file path for all sessions
     */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        require(server == null) { "Global MCP HTTP server already started" }
        
        try {
            val port = settingsService.settings.mcpHttpPort
            println("[Global MCP Server] Starting HTTP server on port $port")
            
            // 1. Start HTTP server
            startHttpServer(port)
            
            // 2. Generate global MCP config file
            val configPath = generateGlobalMcpConfigFile(port)
            configFilePath = configPath
            
            println("[Global MCP Server] MCP server started at http://localhost:$port/mcp/sse")
            println("[Global MCP Server] Config file: $configPath")
            return@withContext configPath
            
        } catch (e: Exception) {
            println("[Global MCP Server] Failed to start: ${e.message}")
            cleanup()
            throw e
        }
    }
    
    /**
     * Stops global HTTP server and cleans up resources
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            stopHttpServer()
            cleanupConfigFile()
            println("[Global MCP Server] Stopped successfully")
        } catch (e: Exception) {
            println("[Global MCP Server] Error during shutdown: ${e.message}")
        } finally {
            cleanup()
        }
    }
    
    // === HTTP SERVER ===
    
    /**
     * Starts Ktor HTTP server with global MCP SSE endpoint
     */
    private suspend fun startHttpServer(port: Int) = withContext(Dispatchers.IO) {
        try {
            server = embeddedServer(CIO, port = port, host = "localhost") {
                install(SSE)
                
                routing {
                    // Global MCP SSE endpoint (all sessions use this)
                    sse("/mcp/sse") {
                        connectionCount++
                        val currentConnectionId = connectionCount
                        
                        try {
                            println("[Global MCP Server] NEW SSE CONNECTION #$currentConnectionId")
                            println("[Global MCP Server] Total connections served: $connectionCount")
                            
                            handleMcpSseConnection(currentConnectionId)
                            
                        } catch (e: Exception) {
                            println("[Global MCP Server] Error in SSE connection: ${e.message}")
                        } finally {
                            println("[Global MCP Server] SSE CONNECTION #$currentConnectionId CLOSED")
                        }
                    }
                    
                    // Health check endpoint
                    get("/health") {
                        call.respondText("Global MCP HTTP Server is running on port $port")
                    }
                    
                    // Debug info endpoint
                    get("/debug") {
                        val info = buildJsonObject {
                            put("serverType", "GlobalMcpHttpServer")
                            put("port", port)
                            put("connectionCount", connectionCount)
                            put("requestCount", requestCount)
                            put("serverName", MCP_SERVER_NAME)
                        }
                        call.respond(info)
                    }
                }
            }
            
            server!!.start(wait = false)
            
        } catch (e: Exception) {
            println("[Global MCP Server] Failed to start HTTP server on port $port: ${e.message}")
            throw e
        }
    }
    
    /**
     * Handles MCP SSE connection - session agnostic
     */
    private suspend fun ServerSSESession.handleMcpSseConnection(connectionId: Int) {
        try {
            // Send server capabilities
            val serverInfo = buildJsonObject {
                put("type", "server_info")
                put("connectionId", connectionId)
                put("serverName", MCP_SERVER_NAME)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {
                        put("listChanged", false)
                    })
                })
                put("serverInfo", buildJsonObject {
                    put("name", "gromozeka-global-server")
                    put("version", "1.0.0")
                })
            }
            
            send(ServerSentEvent(
                data = McpJson.encodeToString(JsonObject.serializer(), serverInfo),
                event = "mcp_server_info"
            ))
            
            // Keep connection alive - MCP protocol handling will be implemented here
            delay(Long.MAX_VALUE)
            
        } catch (e: Exception) {
            println("[Global MCP Server] Error handling SSE connection: ${e.message}")
        }
    }
    
    /**
     * Stops HTTP server
     */
    private suspend fun stopHttpServer() = withContext(Dispatchers.IO) {
        try {
            server?.stop(SHUTDOWN_TIMEOUT_MS, SHUTDOWN_TIMEOUT_MS)
        } catch (e: Exception) {
            println("[Global MCP Server] Error during HTTP server shutdown: ${e.message}")
        } finally {
            server = null
        }
    }
    
    // === GLOBAL MCP CONFIG MANAGEMENT ===
    
    /**
     * Generates global MCP config file for all Claude sessions
     */
    private fun generateGlobalMcpConfigFile(port: Int): String {
        val serverUrl = "http://localhost:$port/mcp/sse"
        
        // Create global HTTP MCP configuration
        val config = buildJsonObject {
            put("mcpServers", buildJsonObject {
                put(MCP_SERVER_NAME, buildJsonObject {
                    put("type", "sse")
                    put("url", serverUrl)
                })
            })
        }
        
        val configFileName = "mcp-global-config.json"
        val configFile = File(settingsService.gromozekaHome, configFileName)
        
        // Ensure directory exists
        settingsService.gromozekaHome.mkdirs()
        
        configFile.writeText(McpJson.encodeToString(JsonObject.serializer(), config))
        
        println("[Global MCP Server] Generated global config: ${configFile.absolutePath}")
        println("[Global MCP Server] Config content: $config")
        return configFile.absolutePath
    }
    
    /**
     * Cleans up global MCP config file
     */
    private fun cleanupConfigFile() {
        configFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    println("[Global MCP Server] Cleaned up config file: $path")
                }
            } catch (e: Exception) {
                println("[Global MCP Server] Failed to cleanup config file: ${e.message}")
            }
        }
    }
    
    /**
     * Resets internal state
     */
    private fun cleanup() {
        configFilePath = null
    }
    
    // === GLOBAL MCP TOOLS ===
    
    /**
     * Global Hello World tool - works for all sessions
     */
    private fun executeHelloWorld(connectionId: Int): CallToolResult {
        requestCount++
        val port = settingsService.settings.mcpHttpPort
        println("[Global MCP Server] EXECUTING HELLO_WORLD tool")
        
        val content = listOf(
            TextContent("Hello from Gromozeka! 🤖 Global MCP HTTP server on port $port is working correctly. Request #$requestCount from connection #$connectionId.")
        )
        
        val result = CallToolResult(
            content = content,
            isError = false
        )
        
        println("[Global MCP Server] HELLO_WORLD tool executed successfully")
        return result
    }
}