package com.gromozeka.bot.config.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import klog.KLoggers
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class McpConfigurationService(
    @Value("\${GROMOZEKA_HOME:\${user.home}/.gromozeka}")
    private val gromozemkaHome: String
) {
    private val log = KLoggers.logger {}
    private val objectMapper = ObjectMapper()

    private var cachedConfig: McpConfig? = null

    @PostConstruct
    fun loadConfiguration() {
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
}
