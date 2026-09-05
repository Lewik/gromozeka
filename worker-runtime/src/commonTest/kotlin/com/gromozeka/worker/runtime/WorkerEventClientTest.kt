package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerEventInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class WorkerEventClientTest {
    private val time = Instant.parse("2026-09-05T00:00:00Z")
    private val batch = WorkerEventBatchRequest(listOf(WorkerEventInput("event", time, DeviceStateEvent.Battery(50, false))))

    @Test
    fun `any Worker uses authenticated HTTPS batch endpoint`() = runTest {
        withContext(Dispatchers.Default) {
            val http = HttpClient(MockEngine { request ->
                assertEquals("https://server.test/api/worker/events", request.url.toString())
                assertEquals("Bearer secret", request.headers["Authorization"])
                assertEquals(batch, Json.decodeFromString<WorkerEventBatchRequest>((request.body as TextContent).text))
                respond(Json.encodeToString(WorkerEventBatchResponse(setOf("event"), emptySet(), time)))
            })
            val client = WorkerEventClient(http, "https://server.test", "secret")
            try { assertEquals(setOf("event"), client.send(batch).acceptedEventIds) }
            finally { client.close(); http.close() }
        }
    }

    @Test
    fun `oversized acknowledgement is rejected before JSON decoding`() = runTest {
        withContext(Dispatchers.Default) {
            val http = HttpClient(MockEngine { respond("x".repeat(64 * 1024 + 1)) })
            val client = WorkerEventClient(http, "https://server.test", "secret")
            try {
                val error = assertFailsWith<IllegalArgumentException> { client.send(batch) }
                assertEquals("Worker event acknowledgement is too large", error.message)
            } finally { client.close(); http.close() }
        }
    }

    @Test
    fun `redirect is not followed even when original HTTP client allows it`() = runTest {
        withContext(Dispatchers.Default) {
            var calls = 0
            val http = HttpClient(MockEngine {
                calls++
                respond("", HttpStatusCode.TemporaryRedirect, headersOf("Location", "https://other.test/collect"))
            }) { followRedirects = true }
            val client = WorkerEventClient(http, "https://server.test", "secret")
            try {
                assertEquals(307, assertFailsWith<WorkerEventHttpException> { client.send(batch) }.statusCode)
                assertEquals(1, calls)
                assertFailsWith<IllegalArgumentException> { WorkerEventClient(http, "http://server.test", "secret") }
            } finally { client.close(); http.close() }
        }
    }
}
