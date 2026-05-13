package com.gromozeka.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import klog.KLoggers
import java.util.concurrent.ConcurrentHashMap

private val mcpLog = KLoggers.logger("GromozekaMcpSse")

fun Routing.gromozekaMcp(path: String, mcpServerFactory: GromozekaMcpServerFactory) {
    val transports = ConcurrentHashMap<String, SseServerTransport>()

    sse(path) {
        val transport = SseServerTransport(path, this)
        transports[transport.sessionId] = transport
        val server = mcpServerFactory.create()

        server.onClose {
            transports.remove(transport.sessionId)
            mcpLog.info { "Gromozeka MCP SSE closed: sessionId=${transport.sessionId}" }
        }

        mcpLog.info { "Gromozeka MCP SSE connected: sessionId=${transport.sessionId}" }
        server.connect(transport)
    }

    post(path) {
        val sessionId = call.request.queryParameters[MCP_SESSION_ID_PARAM]
            ?: run {
                call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is not provided")
                return@post
            }

        val transport = transports[sessionId]
            ?: run {
                call.respond(HttpStatusCode.NotFound, "Session not found")
                return@post
            }

        transport.handlePostMessage(call)
    }
}

private const val MCP_SESSION_ID_PARAM = "sessionId"
