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
internal class ControlMcpToolCatalog(
    providers: List<ControlMcpToolProvider>,
) {
    val tools = providers
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
}

@Service
internal class GromozekaControlMcpServerFactory(
    private val catalog: ControlMcpToolCatalog,
) {
    private val log = KLoggers.logger(this)

    init {
        log.info {
            "Registered ${catalog.tools.size} Gromozeka Control MCP tools: " +
                catalog.tools.joinToString { it.definition.name }
        }
    }

    fun create(caller: AuthenticatedMcpCaller): Server {
        val context = ControlMcpCallContext(caller.user)
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
        catalog.tools.forEach { tool ->
            server.addTool(tool.definition) { request ->
                tool.invoke(
                    context,
                    request.arguments ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            }
        }
        return server
    }
}
