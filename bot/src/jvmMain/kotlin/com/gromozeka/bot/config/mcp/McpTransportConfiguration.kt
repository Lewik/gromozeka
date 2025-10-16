package com.gromozeka.bot.config.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import klog.KLoggers
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Duration

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

    @Bean
    fun sseTransports(
        objectMapperProvider: ObjectProvider<ObjectMapper>
    ): List<NamedClientMcpTransport> {
        val sseServers = mcpConfigService.getSseServers()

        if (sseServers.isEmpty()) {
            log.debug { "No SSE MCP servers configured" }
            return emptyList()
        }

        val objectMapper = objectMapperProvider.getIfAvailable(::ObjectMapper)

        return sseServers.mapNotNull { (name, config) ->
            try {
                val builder = HttpClientSseClientTransport.builder(config.url!!)
                    .objectMapper(objectMapper)

                if (config.sseEndpoint != null) {
                    builder.sseEndpoint(config.sseEndpoint)
                }

                config.headers?.forEach { (key, value) ->
                    builder.customizeRequest { it.header(key, value) }
                }

                if (config.timeout != null && config.timeout > 0) {
                    builder.customizeClient {
                        it.connectTimeout(Duration.ofSeconds(config.timeout.toLong()))
                    }
                }

                val transport = builder.build()
                log.info { "Created SSE transport for: $name" }
                NamedClientMcpTransport(name, transport)
            } catch (e: Exception) {
                log.error(e) { "Failed to create SSE transport for: $name" }
                null
            }
        }
    }
}
