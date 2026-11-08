package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.WorkerRequestDelivery
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
class WorkerGatewayPayloadEncodingTest {
    private val byteStringDecoder = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
        alwaysUseByteString = true
    }

    @Test
    fun `request carries opaque bytes without numeric array expansion`() {
        val payload = ByteArray(1024 * 1024) { it.toByte() }
        val request = WorkerGatewayMessage.Request(
            "binary-request", WorkerGatewayOperation.AI_REQUEST_RESPONSE, payload,
            WorkerRequestDelivery(Instant.parse("2026-11-08T00:00:00Z"), 60_000),
        )
        assertCompactRoundTrip(request, payload.size)
    }

    @Test
    fun `successful response carries opaque bytes without numeric array expansion`() {
        val payload = ByteArray(1024 * 1024) { (255 - it).toByte() }
        val response = WorkerGatewayMessage.Response(
            "binary-response", WorkerGatewayMessage.Response.Status.SUCCEEDED, payload,
        )
        assertCompactRoundTrip(response, payload.size)
    }

    @Test
    fun `empty payload and absent failed-response payload remain distinct`() {
        val messages = listOf(
            WorkerGatewayMessage.Request("empty-request", WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf()),
            WorkerGatewayMessage.Response("empty-response", WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf()),
            WorkerGatewayMessage.Response("failed-response", WorkerGatewayMessage.Response.Status.FAILED,
                errorCode = "SYNTHETIC_FAILURE", errorMessage = "Synthetic failure"),
        )
        messages.forEach { assertCompactRoundTrip(it, 0) }
    }

    private fun assertCompactRoundTrip(message: WorkerGatewayMessage, payloadSize: Int) {
        val bytes = WorkerGatewayCodec.encode(message)
        assertTrue(bytes.size in payloadSize..(payloadSize + 512),
            "Encoded message must contain raw payload bytes plus a bounded envelope: ${bytes.size} bytes")
        assertEquals(message, byteStringDecoder.decodeFromByteArray(WorkerGatewayMessage.serializer(), bytes))
        assertEquals(message, WorkerGatewayCodec.decode(bytes))
    }
}
