package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.DesktopScreenshotCapture
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrzCaptureScreenshotToolImplTest {
    @Test
    fun `rejects a request routed to another Worker before capture`() {
        val screenshots = FakeDesktopScreenshotCapture()
        val tool = GrzCaptureScreenshotToolImpl("worker-local", screenshots)

        val error = assertFailsWith<IllegalStateException> {
            tool.execute(
                CaptureScreenshotRequest(),
                ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to "worker-other")),
            )
        }

        assertTrue(error.message.orEmpty().contains("reached worker-local"))
        assertEquals(0, screenshots.captureCount)
    }

    @Test
    fun `uses the selected desktop capture without accessing the tool process screen`() {
        val screenshots = FakeDesktopScreenshotCapture()
        val tool = GrzCaptureScreenshotToolImpl("worker-local", screenshots)

        val result = tool.execute(CaptureScreenshotRequest(1920), localContext())

        assertContentEquals(screenshots.png, result.content)
        assertEquals(1920, screenshots.maxLongEdge)
        assertEquals(1, screenshots.captureCount)
        assertEquals("image/png", result.mediaType)
        assertEquals("worker-worker-local-screenshot.png", result.fileName)
    }

    @Test
    fun `omits unavailable capture and reports its reason`() {
        val screenshots = FakeDesktopScreenshotCapture().apply { unavailableReason = "Screen Recording denied" }
        val tool = GrzCaptureScreenshotToolImpl("worker-local", screenshots)

        assertFalse(tool.available)
        val error = assertFailsWith<IllegalStateException> {
            tool.execute(CaptureScreenshotRequest(), localContext())
        }
        assertEquals("Screen Recording denied", error.message)
        assertEquals(0, screenshots.captureCount)
    }

    @Test
    fun `temporary desktop loss fails instead of falling back to the service screen`() {
        val screenshots = FakeDesktopScreenshotCapture().apply {
            failure = IllegalStateException("Windows desktop is locked or unavailable")
        }
        val tool = GrzCaptureScreenshotToolImpl("worker-local", screenshots)

        assertTrue(tool.available)
        val error = assertFailsWith<IllegalStateException> {
            tool.execute(CaptureScreenshotRequest(), localContext())
        }
        assertEquals("Windows desktop is locked or unavailable", error.message)
        assertEquals(1, screenshots.captureCount)
    }

    @Test
    fun `validates requested screenshot dimensions`() {
        assertFailsWith<IllegalArgumentException> { CaptureScreenshotRequest(max_long_edge = 512) }
        assertFailsWith<IllegalArgumentException> { CaptureScreenshotRequest(max_long_edge = 8192) }
    }

    private fun localContext() = ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to "worker-local"))

    private class FakeDesktopScreenshotCapture : DesktopScreenshotCapture {
        override var unavailableReason: String? = null
        override val available: Boolean get() = unavailableReason == null
        val png = byteArrayOf(1, 2, 3)
        var captureCount = 0
        var maxLongEdge: Int? = null
        var failure: RuntimeException? = null

        override fun capture(maxLongEdge: Int): ByteArray {
            captureCount++
            this.maxLongEdge = maxLongEdge
            failure?.let { throw it }
            return png
        }
    }
}
