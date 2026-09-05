package com.gromozeka.worker.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerSoundToolTest {
    @Test
    fun `duration is bounded and invalid input never reaches the output`() = runTest {
        var calls = 0
        var stopped = 0
        val tool = WorkerSoundTool(WorkerSoundController(output = { _, onStarted ->
            calls++
            onStarted()
            try { awaitCancellation() } finally { stopped++ }
        }))
        assertFalse(tool.descriptor.metadata.visibleToMemoryPipeline)
        for (value in listOf("null", "[]", "{\"extra\":1}", "{\"duration_seconds\":null}", "{\"duration_seconds\":\"3\"}",
            "{\"duration_seconds\":true}", "{\"duration_seconds\":1.5}", "{\"duration_seconds\":0}", "{\"duration_seconds\":61}")) {
            assertFailsWith<IllegalArgumentException> { tool.execute(Json.parseToJsonElement(value)) }
        }
        assertEquals(0, calls)
        val result = tool.execute(JsonObject(emptyMap())).jsonObject
        assertEquals("COMPLETED", result.getValue("outcome").jsonPrimitive.content)
        assertEquals("10", result.getValue("requestedDurationSeconds").jsonPrimitive.content)
        assertEquals(1, calls)
        assertEquals(1, stopped)
    }

    @Test
    fun `local stop releases playback before completing and a busy sound is never queued`() = runTest {
        val started = CompletableDeferred<Unit>()
        var released = false
        val states = mutableListOf<Boolean>()
        val controller = WorkerSoundController(output = { _, onStarted ->
            onStarted()
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(10); released = true } }
        }, onPlayingChanged = { states += it })
        val result = async { controller.play(60) }
        started.await()
        assertFailsWith<IllegalArgumentException> { controller.play(1) }
        assertTrue(controller.stop())
        assertFalse(controller.stop())
        assertEquals(WorkerSoundOutcome.STOPPED_LOCALLY, result.await())
        assertTrue(released)
        assertEquals(listOf(true, false), states)
        assertFalse(controller.stop())
    }

    @Test
    fun `request cancellation and timeout stop sound instead of claiming completion`() = runTest {
        var releases = 0
        val controller = WorkerSoundController(output = { _, onStarted ->
            onStarted()
            try { awaitCancellation() } finally { releases++ }
        })
        assertFailsWith<CancellationException> { withTimeout(100) { controller.play(60) } }
        assertEquals(1, releases)
        val playing = async { controller.play(60) }
        delay(100)
        playing.cancelAndJoin()
        assertEquals(2, releases)
        assertFalse(controller.stop())
        assertEquals(WorkerSoundOutcome.COMPLETED, controller.play(1))
        assertEquals(3, releases)
    }

    @Test
    fun `output and foreground failures propagate and release the controller`() = runTest {
        val states = mutableListOf<Boolean>()
        val controller = WorkerSoundController(output = { _, _ -> throw IllegalArgumentException("DND blocks alarms") },
            onPlayingChanged = { states += it })
        repeat(2) { assertFailsWith<IllegalArgumentException> { controller.play(1) } }
        assertEquals(listOf(true, false, true, false), states)
        var reachedOutput = false
        val foregroundFailure = WorkerSoundController(output = { _, _ -> reachedOutput = true; awaitCancellation() },
            onPlayingChanged = { if (it) error("Foreground denied") })
        repeat(2) { assertFailsWith<IllegalStateException> { foregroundFailure.play(1) } }
        assertFalse(reachedOutput)
    }

    @Test
    fun `local stop during preparation prevents starting output`() = runTest {
        var reachedOutput = false
        lateinit var controller: WorkerSoundController
        controller = WorkerSoundController(output = { _, _ -> reachedOutput = true; awaitCancellation() },
            onPlayingChanged = { if (it) controller.stop() })
        assertEquals(WorkerSoundOutcome.STOPPED_LOCALLY, controller.play(10))
        assertFalse(reachedOutput)
    }

    @Test
    fun `preparation timeout is an error and playback duration starts after output readiness`() = runTest {
        var released = false
        val stuck = WorkerSoundController(output = { _, _ ->
            try { awaitCancellation() } finally { released = true }
        })
        assertFailsWith<IllegalArgumentException> { stuck.play(1) }
        assertTrue(released)
        val beganAt = testScheduler.currentTime
        val slow = WorkerSoundController(output = { _, onStarted ->
            delay(2_000)
            onStarted()
            awaitCancellation()
        })
        assertEquals(WorkerSoundOutcome.COMPLETED, slow.play(1))
        assertEquals(3_000L, testScheduler.currentTime - beganAt)
    }
}
