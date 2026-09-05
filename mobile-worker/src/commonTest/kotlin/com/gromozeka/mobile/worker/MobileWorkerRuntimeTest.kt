package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class MobileWorkerRuntimeTest {
    private class Storage : MobileWorkerStorage {
        private var state: String? = null
        private var credential: String? = null
        override fun readState() = state
        override fun writeState(value: String) { state = value }
        override fun readCredential() = credential
        override fun writeCredential(value: String) { credential = value }
        override fun clearCredential() { credential = null }
    }
    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }
    private fun runtime(storage: Storage, http: HttpClient) = MobileWorkerRuntime(storage, WorkerPlatform.ANDROID, "Android", "test", "1", http)
    private fun acknowledgement(batch: WorkerEventBatchRequest) = Json.encodeToString(
        WorkerEventBatchResponse(batch.events.mapTo(linkedSetOf()) { it.id }, emptySet(), Clock.System.now()),
    )

    @Test
    fun `recording is not blocked by synchronization and newly recorded events are preserved`() = test {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<WorkerEventBatchRequest>()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("consume")) {
                respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"user"}""")
            } else {
                val batch = Json.decodeFromString<WorkerEventBatchRequest>((request.body as TextContent).text)
                batches += batch
                entered.complete(Unit)
                release.await()
                respond(acknowledgement(batch))
            }
        })
        val runtime = runtime(Storage(), http)
        try {
            runtime.enroll("https://server.test", "token", "worker")
            val sending = async { runtime.synchronize() }
            entered.await()
            runtime.recordBattery(50, false)
            assertEquals(2, runtime.status().pendingEventCount)
            release.complete(Unit)
            assertEquals(0, sending.await().pendingEventCount)
            assertEquals(listOf(1, 1), batches.map { it.events.size })
        } finally { runtime.close() }
    }

    @Test
    fun `failed delivery survives runtime recreation without changing event IDs`() = test {
        var fail = true
        val batches = mutableListOf<WorkerEventBatchRequest>()
        fun http() = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("consume")) {
                respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"user"}""")
            } else {
                val batch = Json.decodeFromString<WorkerEventBatchRequest>((request.body as TextContent).text)
                batches += batch
                if (fail) respond("unavailable", HttpStatusCode.ServiceUnavailable) else respond(acknowledgement(batch))
            }
        })
        val storage = Storage()
        val first = runtime(storage, http())
        first.enroll("https://server.test", "token", "worker")
        first.recordBattery(50, false)
        assertTrue(runCatching { first.synchronize() }.isFailure)
        assertEquals(2, first.status().pendingEventCount)
        first.close()
        fail = false
        val second = runtime(storage, http())
        try {
            assertEquals(0, second.synchronize().pendingEventCount)
            assertEquals(batches.first().events, batches.last().events)
        } finally { second.close() }
    }

    @Test
    fun `synchronization is signaled only after durable queue becomes nonempty`() = test {
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("consume")) {
                respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"user"}""")
            } else {
                respond(acknowledgement(Json.decodeFromString((request.body as TextContent).text)))
            }
        })
        val storage = Storage()
        var signals = 0
        val runtime = MobileWorkerRuntime(storage, WorkerPlatform.ANDROID, "Android", "test", "1", http,
            onEventsQueued = {
                assertTrue(requireNotNull(storage.readState()).contains("\"pending\":[{"))
                signals++
            })
        try {
            runtime.enroll("https://server.test", "token", "worker")
            runtime.recordBattery(50, false)
            runtime.recordBattery(51, false)
            assertEquals(1, signals)
            assertEquals(0, runtime.synchronize().pendingEventCount)
            runtime.recordBattery(51, false)
            assertEquals(1, signals)
            runtime.recordBattery(52, false)
            assertEquals(2, signals)
        } finally { runtime.close() }
    }

    @Test
    fun `full backlog can drain after app version changes`() = test {
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("consume")) {
                respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"user"}""")
            } else {
                respond(acknowledgement(Json.decodeFromString((request.body as TextContent).text)))
            }
        })
        val storage = Storage()
        val initial = runtime(storage, http)
        initial.enroll("https://server.test", "token", "worker")
        val updated = MobileWorkerRuntime(storage, WorkerPlatform.ANDROID, "Android", "test", "2", http,
            outboxLimits = com.gromozeka.worker.runtime.WorkerEventOutboxLimits(maxEvents = 1))
        try {
            assertEquals(1, updated.synchronize().pendingEventCount)
            assertEquals(0, updated.synchronize().pendingEventCount)
        } finally { updated.close() }
    }

    @Test
    fun `reset and reenrollment cannot apply an old server acknowledgement`() = test {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("consume")) {
                respond("""{"workerId":"worker","gatewayCredential":"${request.url.host}","capabilities":[],"subjectUserId":"user"}""")
            } else {
                val batch = Json.decodeFromString<WorkerEventBatchRequest>((request.body as TextContent).text)
                assertEquals("first.test", request.url.host)
                assertEquals("Bearer first.test", request.headers["Authorization"])
                entered.complete(Unit)
                release.await()
                respond(acknowledgement(batch))
            }
        })
        val runtime = runtime(Storage(), http)
        try {
            runtime.enroll("https://first.test", "token", "worker")
            val sending = async { runCatching { runtime.synchronize() } }
            entered.await()
            runtime.reset()
            runtime.enroll("https://second.test", "token", "worker")
            release.complete(Unit)
            assertTrue(sending.await().isFailure)
            assertEquals("https://second.test", runtime.status().serverUrl)
            assertEquals(1, runtime.status().pendingEventCount)
        } finally { runtime.close() }
    }
}
