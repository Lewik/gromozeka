package com.gromozeka.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GromozekaStatelessMcpHttpTest {
    @Test
    fun `initialize returns a serialized JSON-RPC response`() = testApplication {
        application {
            statelessMcpStreamableHttp(
                path = "/mcp",
                allowedHosts = null,
                allowedOrigins = null,
            ) { testServer() }
            statelessMcpStreamableHttp(
                path = "/mcp/control",
                allowedHosts = null,
                allowedOrigins = null,
            ) { testServer() }
        }

        val response = client.post("https://localhost/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(
                HttpHeaders.Accept,
                "${ContentType.Application.Json}, ${ContentType.Text.EventStream}",
            )
            setBody(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test-client", "version": "1"}
                  }
                }
                """.trimIndent()
            )
        }

        val responseBody = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, responseBody)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertTrue(responseBody.contains("\"serverInfo\""))
    }
}

private fun testServer() = Server(
    serverInfo = Implementation("test-server", "1"),
    options = ServerOptions(ServerCapabilities()),
)
