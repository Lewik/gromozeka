package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Instant

class WorkerLocationToolTest {
    @Test
    fun `freshness uses monotonic age and requires a new fix unless cache was explicitly allowed`() {
        assertFalse(isWorkerLocationFresh(2_000_000_000, 1_000_000_000, 0))
        assertTrue(isWorkerLocationFresh(500_000_000, 1_000_000_000, 0))
        assertTrue(isWorkerLocationFresh(2_000_000_000, 1_000_000_000, 2))
        assertFalse(isWorkerLocationFresh(2_000_000_001, 1_000_000_000, 2))
        assertFalse(isWorkerLocationFresh(-1, 1_000_000_000, 2))
    }

    @Test
    fun `invalid configuration and arguments never activate a sensor`() = runTest {
        var calls = 0
        val sample = WorkerLocationSample(Instant.parse("2026-09-05T00:00:00Z"), DeviceStateEvent.Location(32.0, 34.0, 12.0, cause = com.gromozeka.domain.model.LocationCause.CURRENT))
        val tool = WorkerLocationTool { calls++; assertEquals(0, it); sample }
        assertFalse(tool.descriptor.metadata.visibleToMemoryPipeline)
        for (value in listOf("null", "[]", "{\"extra\":1}", "{\"max_age_seconds\":-1}", "{\"max_age_seconds\":3601}",
            "{\"timeout_seconds\":0}", "{\"timeout_seconds\":121}", "{\"timeout_seconds\":1.5}",
            "{\"timeout_seconds\":null}", "{\"max_age_seconds\":\"2\"}")) {
            assertFailsWith<IllegalArgumentException> { tool.execute(Json.parseToJsonElement(value)) }
        }
        assertEquals(0, calls)
        assertEquals(sample, Json.decodeFromString<WorkerLocationSample>(tool.execute(JsonObject(emptyMap())).toString()))
        assertEquals(1, calls)
        assertFailsWith<IllegalArgumentException> { WorkerLocationConfiguration(intervalSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { WorkerLocationConfiguration(minimumDistanceMeters = -1) }
    }

    @Test
    fun `acquisition timeout and request cancellation release the sensor and return no stale sample`() = runTest {
        var released = 0
        val tool = WorkerLocationTool { try { awaitCancellation() } finally { released++ } }
        assertFailsWith<IllegalArgumentException> { tool.execute(Json.parseToJsonElement("{\"timeout_seconds\":1}")) }
        assertEquals(1, released)
        assertFailsWith<CancellationException> { withTimeout(100) { tool.execute(JsonObject(emptyMap())) } }
        assertEquals(2, released)
    }
}
