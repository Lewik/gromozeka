package com.gromozeka.server

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.lang.reflect.Proxy

private val testClientConnection: ClientConnection =
    Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSessionId" -> "test-session"
            else -> error("MCP test client connection method is not supported: ${method.name}")
        }
    } as ClientConnection

suspend fun RegisteredTool.callForTest(request: CallToolRequest): CallToolResult =
    handler.invoke(testClientConnection, request)
