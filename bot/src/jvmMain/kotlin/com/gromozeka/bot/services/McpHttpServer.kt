package com.gromozeka.bot.services

import com.gromozeka.bot.services.mcp.GromozekaMcpTool
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.collections.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.stereotype.Service
import java.io.File
import com.gromozeka.bot.utils.findRandomAvailablePort

@Service
class McpHttpServer(
    private val settingsService: SettingsService,
    private val mcpTools: List<GromozekaMcpTool>,
) {
    private var httpServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeMcpServers = ConcurrentMap<String, Server>()

    fun start(): String {
        val port = findRandomAvailablePort()
        println("[MCP HTTP Server] Found available port: $port")

        println("[MCP HTTP Server] Starting HTTP/SSE server on port $port")

        httpServer = embeddedServer(CIO, port = port) {
            install(SSE)

            routing {
                // Standard MCP SSE endpoint
                sse("/sse") {
                    handleMcpSseConnection(this)
                }

                post("/message") {
                    handleMcpPostMessage(call)
                }

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

    fun stop() {
        println("[MCP HTTP Server] Stopping server...")
        httpServer?.stop(1000, 2000)
        serverScope.cancel()
        println("[MCP HTTP Server] Server stopped")
    }

    private suspend fun handleMcpSseConnection(sseSession: ServerSSESession) {
        println("[MCP HTTP Server] New SSE connection established")

        try {
            val transport = SseServerTransport("/message", sseSession)
            val server = createMcpServer()

            activeMcpServers[transport.sessionId] = server
            println("[MCP HTTP Server] Registered MCP server for session: ${transport.sessionId}")

            server.onClose {
                println("[MCP HTTP Server] MCP server closed, removing session: ${transport.sessionId}")
                activeMcpServers.remove(transport.sessionId)
            }

            server.connect(transport)
            println("[MCP HTTP Server] MCP server connected and ready")

        } catch (e: Exception) {
            println("[MCP HTTP Server] Error in SSE connection: ${e.message}")
            e.printStackTrace()
        }
    }

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

        server.addTools(mcpTools.map { it.toRegisteredTool() })

        return server
    }

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