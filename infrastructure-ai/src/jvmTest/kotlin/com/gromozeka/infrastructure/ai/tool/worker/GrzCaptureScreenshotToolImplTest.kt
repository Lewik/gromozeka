package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrzCaptureScreenshotToolImplTest {
    @Test
    fun `rejects a request routed to another Worker before capture`() {
        val tool = GrzCaptureScreenshotToolImpl("worker-local")

        val error = assertFailsWith<IllegalStateException> {
            tool.execute(
                CaptureScreenshotRequest(),
                ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to "worker-other")),
            )
        }

        assertTrue(error.message.orEmpty().contains("reached worker-local"))
    }

    @Test
    fun `validates requested screenshot dimensions`() {
        assertFailsWith<IllegalArgumentException> { CaptureScreenshotRequest(max_long_edge = 512) }
        assertFailsWith<IllegalArgumentException> { CaptureScreenshotRequest(max_long_edge = 8192) }
    }
}
