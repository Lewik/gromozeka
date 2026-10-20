package com.gromozeka.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

internal fun Application.statelessMcpStreamableHttp(
    path: String,
    allowedHosts: List<String>?,
    allowedOrigins: List<String>?,
    createServer: suspend (ApplicationCall) -> Server,
) {
    installMcpContentNegotiation()
    routing {
        route(path) {
            install(io.ktor.server.application.createRouteScopedPlugin("ExactNativeMcpSchemas") {
                onCallRespond { call, _ ->
                    transformBody { body ->
                        val definitions = call.attributes.getOrNull(exactMcpToolDefinitions)
                        val json = when (body) {
                            is io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse ->
                                McpJson.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse.serializer(), body)
                            is io.ktor.http.content.OutgoingContent.ByteArrayContent ->
                                if (body.contentType?.withoutParameters() == io.ktor.http.ContentType.Application.Json)
                                    kotlinx.serialization.json.Json.parseToJsonElement(body.bytes().decodeToString()) else null
                            else -> null
                        }
                        val exact = if (definitions != null && json != null) json.withExactToolSchemas(definitions) else null
                        if (exact == null) body else
                            io.ktor.http.content.TextContent(exact.toString(), io.ktor.http.ContentType.Application.Json)
                    }
                }
            })
            install(DnsRebindingProtection) {
                this.allowedHosts = allowedHosts ?: LOCALHOST_ALLOWED_HOSTS
                this.allowedOrigins = allowedOrigins
                    ?: LOCALHOST_ALLOWED_ORIGINS.takeIf { allowedHosts == null }
            }
            post {
                val transport = StreamableHttpServerTransport(
                    StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
                ).apply {
                    setSessionIdGenerator(null)
                }
                val session = createServer(call).createSession(transport)
                try {
                    transport.handleRequest(null, call)
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { session.close() }
                }
            }
            get {
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
            delete {
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
        }
    }
}

private fun Application.installMcpContentNegotiation() {
    if (attributes.getOrNull(mcpContentNegotiationInstalled) != null) return
    check(pluginOrNull(ContentNegotiation) == null) {
        "MCP routes require ContentNegotiation configured with McpJson"
    }
    install(ContentNegotiation) {
        json(McpJson)
    }
    attributes.put(mcpContentNegotiationInstalled, Unit)
}

private val mcpContentNegotiationInstalled = AttributeKey<Unit>("GromozekaMcpContentNegotiationInstalled")

private val LOCALHOST_ALLOWED_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")
private val LOCALHOST_ALLOWED_ORIGINS = listOf(
    "http://localhost",
    "http://127.0.0.1",
    "http://[::1]",
)
