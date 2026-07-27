package com.gromozeka.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport

internal fun Application.statelessMcpStreamableHttp(
    path: String,
    allowedHosts: List<String>?,
    allowedOrigins: List<String>?,
    createServer: () -> Server,
) {
    routing {
        route(path) {
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
                createServer().createSession(transport)
                transport.handleRequest(null, call)
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

private val LOCALHOST_ALLOWED_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")
private val LOCALHOST_ALLOWED_ORIGINS = listOf(
    "http://localhost",
    "http://127.0.0.1",
    "http://[::1]",
)
