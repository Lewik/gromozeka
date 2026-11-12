package com.gromozeka.server

import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal class WebSocketMessageRejected(val code: Int, override val message: String) : RuntimeException(message)

internal class WebSocketMessageAssembler(private val maxBinaryMessageBytes: Int) {
    private var binary: Boolean? = null
    private var byteCount = 0
    private var binaryBuffer: ByteArrayOutputStream? = null
    private var textBuffer: StringBuilder? = null
    private var lastTextChar: Char? = null

    init {
        require(maxBinaryMessageBytes > 0)
    }

    fun accept(message: WebSocketMessage<*>): ServerSocketMessage? {
        val isBinary = message is BinaryMessage
        require(isBinary || message is TextMessage)
        if (binary != null && binary != isBinary) {
            clear()
            throw WebSocketMessageRejected(1002, "WebSocket message type changed before completion")
        }
        binary = isBinary
        val continuesSurrogatePair = message is TextMessage && lastTextChar?.isHighSurrogate() == true &&
            message.payload.firstOrNull()?.isLowSurrogate() == true
        val payloadBytes = when (message) {
            is BinaryMessage -> message.payload.remaining()
            is TextMessage -> message.payload.encodeToByteArray().size + if (continuesSurrogatePair) 2 else 0
            else -> error("Unsupported WebSocket message")
        }
        val limit = if (isBinary) maxBinaryMessageBytes else DEFAULT_WEBSOCKET_MESSAGE_BYTES
        if (payloadBytes > limit - byteCount) {
            clear()
            throw WebSocketMessageRejected(1009, "WebSocket message is too large")
        }
        byteCount += payloadBytes
        val complete = when (message) {
            is BinaryMessage -> {
                val buffer = binaryBuffer
                if (buffer == null && message.isLast) {
                    ServerSocketMessage.Binary(message.payload.copyRemaining())
                } else {
                    val output = buffer ?: ByteArrayOutputStream(minOf(payloadBytes, WEBSOCKET_RECEIVE_BUFFER_SIZE)).also {
                        binaryBuffer = it
                    }
                    val payload = message.payload
                    if (payload.hasArray()) {
                        output.write(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining())
                    } else {
                        output.write(payload.copyRemaining())
                    }
                    if (message.isLast) ServerSocketMessage.Binary(output.toByteArray()) else null
                }
            }
            is TextMessage -> {
                if (message.payload.isNotEmpty()) lastTextChar = message.payload.last()
                val buffer = textBuffer
                if (buffer == null && message.isLast) {
                    ServerSocketMessage.Text(message.payload)
                } else {
                    val output = buffer ?: StringBuilder().also { textBuffer = it }
                    output.append(message.payload)
                    if (message.isLast) ServerSocketMessage.Text(output.toString()) else null
                }
            }
            else -> error("Unsupported WebSocket message")
        }
        if (message.isLast) clear()
        return complete
    }

    fun clear() {
        binary = null
        byteCount = 0
        binaryBuffer = null
        textBuffer = null
        lastTextChar = null
    }

    private fun ByteBuffer.copyRemaining(): ByteArray =
        asReadOnlyBuffer().let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
}
