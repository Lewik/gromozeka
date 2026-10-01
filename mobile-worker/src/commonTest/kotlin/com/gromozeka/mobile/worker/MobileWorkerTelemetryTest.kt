package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.AppActivityTransition
import com.gromozeka.domain.model.DeviceCollectionGapReason
import com.gromozeka.domain.model.DeviceCollectionSource
import com.gromozeka.domain.model.DeviceCollectionState
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerEventInput
import com.gromozeka.worker.runtime.WorkerEventOutboxFullException
import com.gromozeka.worker.runtime.WorkerEventOutboxLimits
import com.gromozeka.worker.runtime.WorkerLocationConfiguration
import com.gromozeka.worker.runtime.WorkerLocationSample
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MobileWorkerTelemetryTest {
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private fun test(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

    @Test
    fun `location and telemetry share delivery with original measurement times and no commands`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.runtime.configureLocation(WorkerLocationConfiguration(enabled = true))
            f.runtime.recordSharedLocation(requireNotNull(f.runtime.locationCollection()),
                WorkerLocationSample(start + 3.seconds, DeviceStateEvent.Location(1.0, 2.0, 8.0, cause = LocationCause.LIVE_TRACKING)))
            f.source.events += activity("resume", start + 5.seconds)
            f.collect()
            f.runtime.synchronize()
            assertFalse(f.runtime.status().gatewayEnabled)
            val events = f.delivered()
            assertEquals(start + 5.seconds, events.single { it.payload is DeviceStateEvent.AppActivity }.observedAt)
            assertEquals(start + 3.seconds, events.single { it.payload is DeviceStateEvent.Location }.observedAt)
            assertEquals(f.now, events.single { it.payload is DeviceStateEvent.ScreenState }.observedAt)
            assertTrue(events.any { it.payload is DeviceStateEvent.ApplicationInfo })
            assertEquals(start + 58.seconds, f.runtime.status().usageQueriedThrough)
        } finally { f.close() }
    }

    @Test
    fun `empty successful query records coverage not a missing observation`() = test {
        val f = Fixture()
        try {
            f.enable()
            assertFalse(f.collect())
            f.runtime.synchronize()
            val status = f.delivered().map { it.payload }.filterIsInstance<DeviceStateEvent.CollectionStatus>()
                .single { it.source == DeviceCollectionSource.APP_USAGE && it.state == DeviceCollectionState.ACTIVE }
            assertEquals(start, status.queriedFrom)
            assertEquals(start + 58.seconds, status.queriedThrough)
            assertTrue(f.delivered().none { it.payload is DeviceStateEvent.AppActivity })
        } finally { f.close() }
    }

    @Test
    fun `overlapping history and runtime restart do not duplicate acknowledged events`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.source.events += activity("resume", start + 55.seconds)
            f.collect()
            f.runtime.synchronize()
            val cursor = requireNotNull(f.runtime.telemetryCollection()).checkpoint
            f.restart()
            assertEquals(cursor, requireNotNull(f.runtime.telemetryCollection()).checkpoint)
            f.now += 30.seconds
            f.source.events += activity("pause", start + 70.seconds, AppActivityTransition.PAUSED)
            f.collect()
            f.runtime.synchronize()
            assertEquals(2, f.delivered().count { it.payload is DeviceStateEvent.AppActivity })
            assertEquals(1, f.delivered().count { it.payload is DeviceStateEvent.ApplicationInfo })
        } finally { f.close() }
    }

    @Test
    fun `offline delivery reuses persisted IDs after process restart`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.source.events += activity("resume", start + 5.seconds)
            f.collect()
            f.offline = true
            assertFailsWith<Exception> { f.runtime.synchronize() }
            val first = f.batches.last().events
            f.restart()
            f.offline = false
            f.runtime.synchronize()
            assertEquals(first, f.batches.last().events)
            assertEquals(0, f.runtime.status().pendingEventCount)
        } finally { f.close() }
    }

    @Test
    fun `failed storage write cannot advance cursor independently of events`() = test {
        val f = Fixture()
        try {
            f.enable()
            val old = requireNotNull(f.runtime.telemetryCollection())
            val count = f.runtime.status().pendingEventCount
            f.storage.failWrites = true
            assertFailsWith<IllegalStateException> { f.collect() }
            f.storage.failWrites = false
            assertEquals(old, f.runtime.telemetryCollection())
            assertEquals(count, f.runtime.status().pendingEventCount)
            f.collect()
            assertEquals(start + 58.seconds, f.runtime.status().usageQueriedThrough)
        } finally { f.close() }
    }

    @Test
    fun `full data queue preserves cursor and still permits disabling collection`() = test {
        val f = Fixture(WorkerEventOutboxLimits(maxEvents = 1))
        try {
            f.enable()
            val old = requireNotNull(f.runtime.telemetryCollection())
            assertFailsWith<WorkerEventOutboxFullException> { f.collect() }
            assertEquals(old, f.runtime.telemetryCollection())
            f.runtime.configureTelemetry(WorkerTelemetryConfiguration())
            assertNull(f.runtime.telemetryCollection())
            repeat(5) { f.runtime.synchronize() }
            assertEquals(0, f.runtime.status().pendingEventCount)
            assertTrue(f.delivered().any { (it.payload as? DeviceStateEvent.CollectionStatus)?.state == DeviceCollectionState.DISABLED })
        } finally { f.close() }
    }

    @Test
    fun `disable reenable and enrollment changes fence late collector writes`() = test {
        val f = Fixture()
        try {
            f.enable()
            val old = requireNotNull(f.runtime.telemetryCollection())
            suspend fun oldWrite() = f.runtime.recordTelemetry(old, listOf(activity("late", f.now)), old.checkpoint)
            f.runtime.configureTelemetry(WorkerTelemetryConfiguration())
            assertFailsWith<IllegalStateException> { oldWrite() }
            f.runtime.configureTelemetry(WorkerTelemetryConfiguration(true, true), f.now)
            assertFailsWith<IllegalStateException> { oldWrite() }
            f.runtime.reset()
            f.runtime.enroll("https://second.test", "token", "worker")
            f.runtime.configureTelemetry(WorkerTelemetryConfiguration(true, true), f.now)
            assertFailsWith<IllegalStateException> { oldWrite() }
        } finally { f.close() }
    }

    @Test
    fun `revoked usage access does not stop screen collection and is not backfilled when restored`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.source.access = DeviceCollectionState.PERMISSION_REQUIRED
            f.source.events += activity("private", start + 5.seconds)
            f.collect()
            assertTrue(f.source.queries.isEmpty())
            f.now += 30.seconds
            f.source.access = DeviceCollectionState.ACTIVE
            f.collect()
            f.now += 30.seconds
            f.source.events += activity("allowed", start + 100.seconds)
            f.collect()
            f.runtime.synchronize()
            val events = f.delivered()
            assertEquals(1, events.count { it.payload is DeviceStateEvent.AppActivity })
            assertEquals(start + 100.seconds, events.single { it.payload is DeviceStateEvent.AppActivity }.observedAt)
            assertEquals(3, events.count { it.payload is DeviceStateEvent.ScreenState })
            assertTrue(events.any { (it.payload as? DeviceStateEvent.CollectionGap)?.reason == DeviceCollectionGapReason.ACCESS_UNAVAILABLE })
        } finally { f.close() }
    }

    @Test
    fun `query failure does not claim coverage or enter a tight catchup loop`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.collect()
            val old = requireNotNull(f.runtime.telemetryCollection()).checkpoint.usageThrough
            f.now += 30.seconds
            f.source.failQuery = true
            assertFalse(f.collect())
            assertEquals(old, requireNotNull(f.runtime.telemetryCollection()).checkpoint.usageThrough)
            f.runtime.synchronize()
            assertTrue(f.delivered().any { (it.payload as? DeviceStateEvent.CollectionStatus)?.state == DeviceCollectionState.ERROR })
        } finally { f.close() }
    }

    @Test
    fun `package visibility failure preserves activity with explicitly unavailable metadata`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.source.failMetadata = true
            f.source.events += activity("resume", start + 5.seconds)
            f.collect()
            f.runtime.synchronize()
            assertEquals(1, f.delivered().count { it.payload is DeviceStateEvent.AppActivity })
            assertFalse(f.delivered().map { it.payload }.filterIsInstance<DeviceStateEvent.ApplicationInfo>().single().metadataAvailable)
        } finally { f.close() }
    }

    @Test
    fun `old history is bounded and skipped interval is explicit`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.now = start + 48.hours
            assertTrue(f.collect())
            f.runtime.synchronize()
            val gap = f.delivered().map { it.payload }.filterIsInstance<DeviceStateEvent.CollectionGap>().single()
            assertEquals(start, gap.from)
            assertEquals(start + 24.hours, gap.to)
            assertEquals(DeviceCollectionGapReason.HISTORY_LIMIT, gap.reason)
            assertEquals(start + 24.hours, f.source.queries.single().first)
        } finally { f.close() }
    }

    @Test
    fun `wall clock rollback starts new usage identity epoch and reports uncertainty`() = test {
        val f = Fixture()
        try {
            f.enable()
            f.collect()
            val epoch = requireNotNull(f.runtime.telemetryCollection()).checkpoint.usageEpoch
            f.now = start - 30.seconds
            f.collect()
            assertNotEquals(epoch, requireNotNull(f.runtime.telemetryCollection()).checkpoint.usageEpoch)
            f.runtime.synchronize()
            assertTrue(f.delivered().any { (it.payload as? DeviceStateEvent.CollectionGap)?.reason == DeviceCollectionGapReason.CLOCK_CHANGED })
        } finally { f.close() }
    }

    @Test
    fun `disabled by default and optional app usage does not read Android history`() = test {
        val f = Fixture()
        try {
            f.runtime.enroll("https://server.test", "token", "worker")
            assertFalse(f.collect())
            assertEquals(0, f.source.deviceReads)
            f.runtime.configureTelemetry(WorkerTelemetryConfiguration(enabled = true), start)
            f.collect()
            assertEquals(1, f.source.deviceReads)
            assertTrue(f.source.queries.isEmpty())
            assertEquals(0, f.source.accessReads)
        } finally { f.close() }
    }

    private fun activity(id: String, time: Instant, transition: AppActivityTransition = AppActivityTransition.RESUMED) =
        WorkerEventInput(id, time, DeviceStateEvent.AppActivity("test.game", transition, "test.game.Main"))

    private inner class Fixture(limits: WorkerEventOutboxLimits = WorkerEventOutboxLimits()) {
        val storage = Storage()
        val source = Source()
        val batches = mutableListOf<WorkerEventBatchRequest>()
        var now = start + 60.seconds
        var offline = false
        private val outboxLimits = limits
        var runtime = createRuntime()
        private fun createRuntime() = MobileWorkerRuntime(storage, WorkerPlatform.ANDROID, "Phone", "Android test", "test",
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("consume")) respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"child"}""")
                else {
                    val batch = Json.decodeFromString<WorkerEventBatchRequest>((request.body as TextContent).text)
                    batches += batch
                    if (offline) respond("offline", HttpStatusCode.ServiceUnavailable)
                    else respond(Json.encodeToString(WorkerEventBatchResponse(batch.events.mapTo(linkedSetOf()) { it.id }, emptySet(), now)))
                }
            }), outboxLimits = outboxLimits)
        suspend fun enable() {
            runtime.enroll("https://server.test", "token", "worker")
            runtime.configureTelemetry(WorkerTelemetryConfiguration(true, true), start)
        }
        suspend fun collect() = MobileWorkerTelemetryCollector(runtime, source) { now }.collect()
        fun delivered() = batches.flatMap { it.events }
        fun restart() { runtime.close(); runtime = createRuntime() }
        fun close() { runtime.close() }
    }

    private class Storage : MobileWorkerStorage {
        var failWrites = false
        private var state: String? = null
        private var credential: String? = null
        override fun readState() = state
        override fun writeState(value: String) { check(!failWrites); state = value }
        override fun readCredential() = credential
        override fun writeCredential(value: String) { credential = value }
        override fun clearCredential() { credential = null }
    }

    private class Source : MobileWorkerTelemetrySource {
        val events = mutableListOf<WorkerEventInput>()
        val queries = mutableListOf<Pair<Instant, Instant>>()
        var access = DeviceCollectionState.ACTIVE
        var failQuery = false
        var failMetadata = false
        var deviceReads = 0
        var accessReads = 0
        override fun deviceState(): List<DeviceStateEvent> {
            deviceReads++
            return listOf(DeviceStateEvent.ScreenState(false, true, true))
        }
        override fun usageAccess(): DeviceCollectionState { accessReads++; return access }
        override fun usageEvents(from: Instant, to: Instant, epoch: String): List<WorkerEventInput> {
            queries += from to to
            check(!failQuery)
            return events.filter { it.observedAt >= from && it.observedAt < to }.map { it.copy(id = "$epoch-${it.id}") }
        }
        override fun applicationInfo(packageName: String): DeviceStateEvent.ApplicationInfo {
            check(!failMetadata)
            return DeviceStateEvent.ApplicationInfo(packageName, "Game", metadataAvailable = true)
        }
    }
}
