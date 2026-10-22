package com.gromozeka.server

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.asMono
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service

/** A fresh authorized catalog per HTTP request, never a shared cross-user MCP registry. */
@Service
internal class GromozekaMcpServerFactory(private val tools: GromozekaMcpToolService) {
    suspend fun create(caller: AuthenticatedMcpCaller): NativeMcpHttpSession {
        // Own tool jobs in the HTTP request's structured scope, not a detached Reactor
        // coroutine. Cancellation and request completion must include callback cleanup.
        val requestScope = CoroutineScope(currentCoroutineContext())
        val mapper = SafeMcpJsonMapper()
        val transport = HttpServletStatelessServerTransport.builder()
            .jsonMapper(mapper)
            .messageEndpoint("/mcp")
            .maxRequestSize(4 * 1024 * 1024)
            .build()
        val server = McpServer.async(transport)
            .jsonMapper(mapper)
            .serverInfo("gromozeka", "dev")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            // The native resolver/executor performs the authoritative validation, including
            // trusted context and full contract fingerprints, after current authorization.
            .validateToolInputs(false)
        for (definition in tools.list(caller.user.id)) {
            server.toolCall(definition.toMcpTool(mapper)) { _, request ->
                val result = requestScope.async {
                    val arguments = request.arguments()?.let {
                        serverToolJson.parseToJsonElement(mapper.writeValueAsString(it)).jsonObject
                    } ?: JsonObject(emptyMap())
                    tools.call(caller.user.id, definition.name, arguments).toMcpResult(mapper)
                }
                result.asMono(Dispatchers.Unconfined).doOnCancel { result.cancel() }
            }
        }
        return NativeMcpHttpSession(transport, server.build())
    }
}

internal class NativeMcpHttpSession(
    val transport: HttpServletStatelessServerTransport,
    private val server: McpStatelessAsyncServer,
) : AutoCloseable {
    override fun close() { server.close() }
}
