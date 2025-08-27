package com.gromozeka.bot.services

import com.gromozeka.bot.services.mcp.GromozekaMcpTool
import klog.KLoggers

import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.stereotype.Service

@Service
class McpHttpServer(
    private val settingsService: SettingsService,
    private val mcpTools: List<GromozekaMcpTool>,
) {
    private val log = KLoggers.logger(this)
    private var httpServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        val port = settingsService.mcpPort

        httpServer = embeddedServer(CIO, port = port) {
            mcp {
                Server(
                    serverInfo = Implementation(
                        name = "gromozeka-self-control",
                        version = "1.0.0"
                    ),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = null)
                        )
                    )
                ).apply {
                    addTools(mcpTools.map { it.toRegisteredTool() })
                    log.info("Created MCP server with ${mcpTools.size} tools")
                }
            }

            routing {
                get("/health") {
                    call.respondText("MCP HTTP Server is running", ContentType.Text.Plain)
                }
            }
        }.start(wait = false)

        log.info("Server started on port $port")
    }

    fun stop() {
        log.info("Stopping server...")
        httpServer?.stop(1000, 2000)
        serverScope.cancel()
        log.info("Server stopped")
    }


    /**
     * Generates MCP config file
     * @return Path to the generated config file
     */

}