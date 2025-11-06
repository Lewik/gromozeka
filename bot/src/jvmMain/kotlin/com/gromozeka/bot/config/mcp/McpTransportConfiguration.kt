package com.gromozeka.bot.config.mcp

import io.modelcontextprotocol.client.transport.ServerParameters
import klog.KLoggers
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class McpTransportConfiguration(
    private val mcpConfigService: McpConfigurationService
) {
    private val log = KLoggers.logger {}

    @Bean
    @Primary
    fun mcpStdioClientProperties(): McpStdioClientProperties {
        return object : McpStdioClientProperties() {
            override fun toServerParameters(): Map<String, ServerParameters> {
                val stdioServers = mcpConfigService.getStdioServers()

                return stdioServers.mapValues { (name, config) ->
                    try {
                        ServerParameters.builder(config.command!!)
                            .args(config.args ?: emptyList())
                            .env(config.env ?: emptyMap())
                            .build()
                    } catch (e: Exception) {
                        log.error(e) { "Failed to create ServerParameters for stdio server: $name" }
                        throw e
                    }
                }
            }
        }
    }
}
