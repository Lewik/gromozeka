package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes

fun interface WorkerGatewayTransport {
    suspend fun connect(): WorkerGatewayConnection
}

interface WorkerGatewayConnection {
    suspend fun send(message: WorkerGatewayMessage)
    suspend fun receive(): WorkerGatewayMessage?
    suspend fun close()
}

class KtorWorkerGatewayTransport(
    private val client: HttpClient,
    private val gatewayUrl: String,
    private val credential: String,
    private val maxIncomingMessageBytes: Int = Int.MAX_VALUE,
) : WorkerGatewayTransport {
    init { require(maxIncomingMessageBytes > 0) }

    override suspend fun connect(): WorkerGatewayConnection {
        val socket = client.webSocketSession {
            url(gatewayUrl)
            header(HttpHeaders.Authorization, "Bearer $credential")
        }
        return object : WorkerGatewayConnection {
            override suspend fun send(message: WorkerGatewayMessage) {
                socket.send(Frame.Binary(true, WorkerGatewayCodec.encode(message)))
            }

            override suspend fun receive(): WorkerGatewayMessage? {
                val received = socket.incoming.receiveCatching()
                if (received.isClosed) {
                    received.exceptionOrNull()?.let { throw it }
                    return null
                }
                val frame = received.getOrThrow()
                require(frame is Frame.Binary) { "Worker Gateway Server sent a non-binary frame" }
                require(frame.data.size <= maxIncomingMessageBytes) { "Worker Gateway Server message is too large" }
                return WorkerGatewayCodec.decode(frame.readBytes())
            }

            override suspend fun close() {
                socket.close()
            }
        }
    }
}
