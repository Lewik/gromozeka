package com.gromozeka.bot.config

import io.modelcontextprotocol.client.McpSyncClient
import klog.KLoggers
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class McpClientLogger(
    private val mcpClients: List<McpSyncClient>
) {

    private val log = KLoggers.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun logMcpTools() {
        log.info { "=== MCP Client Status ===" }
        log.info { "Total MCP clients initialized: ${mcpClients.size}" }

        if (mcpClients.isEmpty()) {
            log.warn { "No MCP clients configured. Check application.yaml and mcp.json configuration." }
            return
        }

        mcpClients.forEach { client ->
            try {
                val serverInfo = client.initialize()
                log.info { "MCP Server: ${serverInfo.serverInfo().name()} v${serverInfo.serverInfo().version()}" }

                val tools = client.listTools()
                log.info { "  Available tools: ${tools.tools().size}" }
                tools.tools().forEach { tool ->
                    log.info { "    - ${tool.name()}: ${tool.description() ?: "no description"}" }
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to connect to MCP client: ${e.message}" }
            }
        }

        log.info { "=== MCP Client Initialization Complete ===" }
    }
}
