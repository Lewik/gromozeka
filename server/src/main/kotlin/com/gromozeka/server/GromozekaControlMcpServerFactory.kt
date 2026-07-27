package com.gromozeka.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import klog.KLoggers
import org.springframework.stereotype.Service

internal interface ControlMcpToolProvider {
    val tools: List<ControlMcpTool>
}

@Service
internal class GromozekaControlMcpServerFactory(
    providers: List<ControlMcpToolProvider>,
) {
    private val log = KLoggers.logger(this)
    private val tools = providers
        .flatMap(ControlMcpToolProvider::tools)
        .sortedBy { it.definition.name }
        .also { registered ->
            val duplicateNames = registered
                .groupingBy { it.definition.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            require(duplicateNames.isEmpty()) {
                "Duplicate Gromozeka Control MCP tools: ${duplicateNames.sorted()}"
            }
        }

    init {
        log.info {
            "Registered ${tools.size} Gromozeka Control MCP tools: " +
                tools.joinToString { it.definition.name }
        }
    }

    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "gromozeka-control",
                version = "dev",
            ),
            options = ServerOptions(
                ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                )
            ),
        )
        tools.forEach { tool ->
            server.addTool(tool.definition) { request ->
                tool.invoke(request.arguments ?: kotlinx.serialization.json.JsonObject(emptyMap()))
            }
        }
        return server
    }
}
