package com.gromozeka.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import org.springframework.stereotype.Service

/** One authenticated HTTP surface for the existing Server and Worker tool registry. */
@Service
internal class GromozekaMcpServerFactory(private val tools: GromozekaMcpToolService) {
    suspend fun create(call: io.ktor.server.application.ApplicationCall): Server =
        create(call.attributes[authenticatedMcpCallerKey]) { definitions ->
            call.attributes.put(exactMcpToolDefinitions, definitions.associateBy { it.name })
        }

    suspend fun create(
        caller: AuthenticatedMcpCaller,
        publishDefinitions: (List<com.gromozeka.domain.tool.AiToolDefinition>) -> Unit = {},
    ): Server {
        val server = Server(
            serverInfo = Implementation(name = "gromozeka", version = "dev"),
            options = ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        val definitions = tools.list(caller.user.id)
        publishDefinitions(definitions)
        definitions.forEach { definition ->
            server.addTool(definition.toMcpTool()) { request ->
                tools.call(caller.user.id, definition.name, request.arguments ?: JsonObject(emptyMap())).toMcpResult()
            }
        }
        return server
    }
}
