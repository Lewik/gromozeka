package com.gromozeka.domain.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class WorkerRequestExecutionContextTest {
    private val deadline = Instant.parse("2026-01-01T00:00:30Z")
    private val request = WorkerRequestExecutionContext("trusted-request", deadline)

    @Test
    fun publicMapCannotSupplyOrOverrideTransportMetadata() {
        val public = mapOf("workerRequestId" to "spoof", "startDeadline" to "2099-01-01T00:00:00Z")
        assertNull(ToolExecutionContext(public).workerRequest)
        val context = ToolExecutionContext(public, workerRequest = request)
            .withValue("toolCallId", "call")
            .withCancellationSignal(ToolCancellationSignal.None)
        assertEquals(request, context.workerRequest)
        assertFalse(context.asMap().values.contains(request))
        assertEquals("trusted-request", context.backgroundActivityLaunchReference()?.workerRequestId)
        assertEquals("call", context.backgroundActivityLaunchReference()?.toolCallId)
    }

    @Test
    fun deadlineIsExclusiveAndCheckedAtTheActualStartBoundary() {
        val context = ToolExecutionContext(workerRequest = request)
        context.checkStartAllowed(Instant.parse("2026-01-01T00:00:29Z"))
        assertFailsWith<IllegalStateException> { context.checkStartAllowed(deadline) }
        assertFailsWith<IllegalStateException> { context.checkStartAllowed(Instant.parse("2026-01-01T00:00:31Z")) }
    }

    @Test
    fun cancellationIsCheckedEvenWithoutTransportDeadline() {
        val failure = IllegalStateException("cancelled")
        val context = ToolExecutionContext().withCancellationSignal(ToolCancellationSignal { throw failure })
        assertEquals(failure, assertFailsWith<IllegalStateException> { context.checkStartAllowed() })
    }

    @Test
    fun toolLaunchRequiresCallIdButVisualNeverInventsOne() {
        val context = ToolExecutionContext(workerRequest = request)
        assertFailsWith<IllegalArgumentException> { context.backgroundActivityLaunchReference() }
        val visual = context.withValue(TOOL_CONTEXT_VISUAL_ID, "visual").withValue("toolCallId", "not-a-visual-call")
        assertEquals("trusted-request", visual.backgroundActivityLaunchReference()?.workerRequestId)
        assertNull(visual.backgroundActivityLaunchReference()?.toolCallId)
        assertNull(ToolExecutionContext(mapOf("workerRequestId" to "spoof")).backgroundActivityLaunchReference())
    }
}
