package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeHookPayload
import com.gromozeka.bot.model.ClaudeHookResponse
import com.gromozeka.bot.model.HookDecision
import com.gromozeka.bot.services.mcp.GromozekaMcpTool
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class McpHttpServer(
    private val settingsService: SettingsService,
    private val mcpTools: List<GromozekaMcpTool>,
    private val hookPermissionService: HookPermissionService,
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
                        name = "gromozeka",
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

                post("/hook-permission") {
                    try {
                        val requestBody = call.receiveText()
                        log.debug("Received Claude hook payload: $requestBody")
                        val hookPayload = Json.decodeFromString<ClaudeHookPayload>(requestBody)

                        log.info("Processing ${hookPayload.hook_event_name} hook for tool: ${hookPayload.tool_name} (session: ${hookPayload.session_id})")

//                        if (!hookPayload.isPreToolUse()) {
//                            log.debug("Ignoring non-PreToolUse hook: ${hookPayload.hook_event_name}")
//                            val allowResponse = ClaudeHookResponse(decision = "approve")
//                            val autoApproveJson = Json.encodeToString(ClaudeHookResponse.serializer(), allowResponse)
//                            log.debug("Auto-approving non-PreToolUse hook, sending: $autoApproveJson")
//                            call.respond(HttpStatusCode.OK, autoApproveJson)
//                            return@post
//                        }

                        val responseDeferred = CompletableDeferred<HookDecision>()
                        hookPermissionService.sendCommand(
                            HookPermissionService.Command.ProcessRequest(hookPayload, responseDeferred)
                        )
                        
                        val hookDecision = responseDeferred.await()
                        val response = hookDecision.toClaudeResponse()

                        log.info("Hook decision for ${hookPayload.tool_name}: ${response.decision} - ${response.reason}")

                        val jsonResponse = Json.encodeToString(response)
                        log.info("Sending HTTP response to Claude Code CLI: $jsonResponse")
                        call.respond(HttpStatusCode.OK, jsonResponse)

                    } catch (e: Exception) {
                        log.error(e, "Error processing Claude hook")

                        val errorResponse =
                            ClaudeHookResponse(decision = "block", reason = "Internal error: ${e.message}")
                        val errorJsonResponse = Json.encodeToString(ClaudeHookResponse.serializer(), errorResponse)
                        log.error("Sending error HTTP response to Claude Code CLI: $errorJsonResponse")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            errorJsonResponse
                        )
                    }
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