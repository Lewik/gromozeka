package com.gromozeka.bot.services

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.collections.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.stereotype.Service
import java.io.File

/**
 * Global HTTP/SSE-based MCP server for Claude CLI integration using official MCP Kotlin SDK.
 *
 * This server starts with the application and serves ALL Claude sessions:
 * - Single HTTP server on fixed port (49152)
 * - SSE connection for server -> client messages
 * - HTTP POST endpoint for client -> server messages
 * - Session-based routing via SseServerTransport
 * - Independent of individual sessions - runs globally for entire application lifecycle
 */
@Service
class McpHttpServer(
    private val settingsService: SettingsService,
) {
    private var httpServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store active MCP servers by session ID (standard MCP pattern)
    private val activeMcpServers = ConcurrentMap<String, Server>()

    /**
     * Starts the HTTP/SSE MCP server and returns Claude CLI config file path.
     *
     * @return Path to generated MCP config file for Claude CLI
     */
    fun start(): String {
        val port = settingsService.settings.mcpHttpPort

        println("[MCP HTTP Server] Starting HTTP/SSE server on port $port")

        httpServer = embeddedServer(CIO, port = port) {
            install(SSE)

            routing {
                // Standard MCP SSE endpoint
                sse("/sse") {
                    handleMcpSseConnection(this)
                }

                // Standard MCP POST endpoint for client messages
                post("/message") {
                    handleMcpPostMessage(call)
                }

                // Health check endpoint
                get("/health") {
                    call.respondText("MCP HTTP Server is running", ContentType.Text.Plain)
                }
            }
        }.start(wait = false)

        println("[MCP HTTP Server] Server started successfully")

        val configPath = generateMcpConfigFile(port)
        println("[MCP HTTP Server] Generated config file: $configPath")

        println("[MCP HTTP Server] Returning config path, start() method completing...")
        return configPath
    }

    /**
     * Stops the HTTP server.
     */
    fun stop() {
        println("[MCP HTTP Server] Stopping server...")
        httpServer?.stop(1000, 2000)
        serverScope.cancel()
        println("[MCP HTTP Server] Server stopped")
    }

    /**
     * Handles SSE connection using official MCP SDK pattern.
     * Creates SseServerTransport and MCP Server instance for this session.
     */
    private suspend fun handleMcpSseConnection(sseSession: ServerSSESession) {
        println("[MCP HTTP Server] New SSE connection established")

        try {
            // Create MCP transport using official SDK
            val transport = SseServerTransport("/message", sseSession)
            val server = createMcpServer()

            // Register server by session ID (for POST endpoint routing)
            activeMcpServers[transport.sessionId] = server
            println("[MCP HTTP Server] Registered MCP server for session: ${transport.sessionId}")

            // Setup cleanup on server close
            server.onClose {
                println("[MCP HTTP Server] MCP server closed, removing session: ${transport.sessionId}")
                activeMcpServers.remove(transport.sessionId)
            }

            // Connect server to transport (this sends endpoint info to client)
            server.connect(transport)
            println("[MCP HTTP Server] MCP server connected and ready")

        } catch (e: Exception) {
            println("[MCP HTTP Server] Error in SSE connection: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Handles POST requests to /message endpoint (standard MCP pattern).
     * Routes messages to appropriate MCP server based on sessionId parameter.
     */
    private suspend fun handleMcpPostMessage(call: ApplicationCall) {
        println("[MCP HTTP Server] Received POST message")

        try {
            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing sessionId parameter")
                return
            }

            val server = activeMcpServers[sessionId]
            if (server == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found: $sessionId")
                return
            }

            val transport = server.transport as? SseServerTransport
            if (transport == null) {
                call.respond(HttpStatusCode.InternalServerError, "Invalid transport type")
                return
            }

            // Let the transport handle the message
            transport.handlePostMessage(call)

        } catch (e: Exception) {
            println("[MCP HTTP Server] Error handling POST message: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, "Error processing message")
        }
    }

    /**
     * Creates MCP Server instance with Gromozeka tools.
     * Uses official MCP SDK Server class.
     */
    private fun createMcpServer(): Server {
        val server = Server(
            Implementation(
                name = "gromozeka-self-control",
                version = "1.0.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = null)
                )
            )
        )

        // Add Hello World tool
        server.addTool(
            name = "hello_world",
            description = "Test tool that returns a greeting from Gromozeka MCP server",
            inputSchema = Tool.Input()  // No input parameters
        ) { request ->
            println("[MCP Server] Executing hello_world tool")
            CallToolResult(
                content = listOf(
                    TextContent("Hello from Gromozeka MCP server! 🤖 MCP integration is working correctly via official SDK.")
                )
            )
        }

        return server
    }

    /**
     * Generates MCP config file for Claude CLI.
     */
    private fun generateMcpConfigFile(port: Int): String {
        val configContent = """
        {
          "mcpServers": {
            "gromozeka-self-control": {
              "type": "sse",
              "url": "http://localhost:$port/sse"
            }
          }
        }
        """.trimIndent()

        val configFile = File(settingsService.gromozekaHome, "mcp-sse-config.json")

        // Ensure directory exists
        settingsService.gromozekaHome.mkdirs()

        configFile.writeText(configContent)

        println("[MCP HTTP Server] Generated config: ${configFile.absolutePath}")
        return configFile.absolutePath
    }
}