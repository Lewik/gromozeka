package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseDisplay
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservation
import com.gromozeka.domain.service.ComputerUseObservationId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ComputerUsePoint
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.DesktopScreenshotCapture
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import com.gromozeka.infrastructure.ai.tool.worker.GrzCaptureScreenshotToolImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.EOFException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JvmComputerUseControllerTest {
    @Test
    fun `desktop helper preserves one worker identity and rejects an old helper generation`() {
        val local = FakeComputerUseBackend()
        var desktopUnlocked = true
        val toHelper = LinkedBlockingQueue<String>()
        val toService = LinkedBlockingQueue<String>()
        val closed = AtomicBoolean()
        fun endpoint(incoming: LinkedBlockingQueue<String>, outgoing: LinkedBlockingQueue<String>) =
            object : DesktopHelperChannel {
                override fun send(message: String) { check(!closed.get()); outgoing.put(message) }
                override fun receive(interruptionCheck: () -> Unit): String {
                    while (!closed.get()) {
                        interruptionCheck()
                        incoming.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
                    }
                    throw EOFException()
                }
                override fun close() { closed.set(true) }
            }
        val service = endpoint(toService, toHelper)
        val helper = endpoint(toHelper, toService)
        val thread = Thread {
            runCatching {
                serveDesktopHelper(helper, local) { check(desktopUnlocked) { "Desktop is locked" } }
            }
        }.apply { isDaemon = true; start() }
        var connection = DesktopHelperConnection(service, generation = "first-helper")
        val backend = WindowsDesktopComputerUseBackend(object : DesktopSessionProvider {
            override fun current() = connection
            override fun close() = connection.close()
        })
        try {
            val screenshots = ConversationRuntimeWorkerConfiguration().desktopScreenshotCapture(backend)
            val tool = GrzCaptureScreenshotToolImpl(Identity.workerId.value, screenshots)
            val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to Identity.workerId.value))
            val screenshot = tool.execute(CaptureScreenshotRequest(1920), context)
            assertContentEquals(byteArrayOf(7, 8, 9), screenshot.content)
            assertEquals(1920, local.desktopCaptureLongEdge)
            assertEquals(0, local.captureCount)
            val display = backend.targets().single()
            val observed = backend.capture(Identity, display.id, 2048)
            assertEquals(Identity.workerId, observed.reference.workerId)
            assertEquals(Identity.sessionId, observed.reference.workerSessionId)
            backend.execute(observed.reference, listOf(ComputerUseAction.Wait(1))) {}
            assertEquals(1, local.executeCount)
            connection = DesktopHelperConnection(service, generation = "replacement-helper")
            assertFailsWith<IllegalArgumentException> {
                backend.execute(observed.reference, listOf(ComputerUseAction.Click(ComputerUsePoint(1, 1)))) {}
            }
            assertEquals(1, local.executeCount)
            desktopUnlocked = false
            val locked = assertFailsWith<IllegalStateException> {
                tool.execute(CaptureScreenshotRequest(), context)
            }
            assertEquals("Desktop is locked", locked.message)
        } finally {
            backend.close()
            thread.join(2000)
            assertTrue(!thread.isAlive)
        }
    }

    @Test
    fun `lost desktop helper response never repeats an action`() {
        var sends = 0
        val connection = DesktopHelperConnection(object : DesktopHelperChannel {
            override fun send(message: String) { sends++ }
            override fun receive(interruptionCheck: () -> Unit): String = throw EOFException("Helper stopped after input")
            override fun close() {}
        })
        val error = assertFailsWith<ComputerUseBackendExecutionException> {
            connection.call(DesktopHelperRequest("request-1", DesktopHelperOperation.EXECUTE))
        }
        assertTrue(error.mutationStarted)
        assertEquals(1, sends)
    }

    @Test
    fun `windows launcher quotes spaces quotes and trailing backslashes`() {
        assertEquals("\"C:\\Program Files\\Worker\"", quoteWindowsArgument("C:\\Program Files\\Worker"))
        assertEquals("\"C:\\path\\\\\"", quoteWindowsArgument("C:\\path\\"))
        assertEquals("\"a\\\"b\"", quoteWindowsArgument("a\"b"))
        assertFailsWith<IllegalArgumentException> { quoteWindowsArgument("bad\u0000argument") }
    }

    @Test
    fun `observe returns a self-contained frame for the current worker process`() = runBlocking {
        val backend = FakeComputerUseBackend()

        val observation = controller(backend).observe(Display.id, 2048)

        assertEquals(Identity.workerId, observation.reference.workerId)
        assertEquals(Identity.sessionId, observation.reference.workerSessionId)
        assertEquals(Display.id, observation.reference.displayId)
        assertEquals(1, backend.captureCount)
    }

    @Test
    fun `act executes once and returns a fresh observation`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)

        val after = controller.act(
            observation = observed.reference,
            actions = listOf(ComputerUseAction.Click(ComputerUsePoint(10, 10))),
            maxLongEdge = 2048,
        )

        assertEquals(1, backend.executeCount)
        assertEquals(2, backend.captureCount)
        assertTrue(after.reference.id != observed.reference.id)
    }

    @Test
    fun `observation from a previous worker process is rejected before input`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val stale = backend.capture(
            Identity.copy(sessionId = ConversationRuntimeWorkerSessionId("old-worker-session")),
            Display.id,
            2048,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            controller.act(
                stale.reference,
                listOf(ComputerUseAction.Click(ComputerUsePoint(10, 10))),
                2048,
            )
        }

        assertTrue(error.message.orEmpty().contains("fresh observation"))
        assertEquals(0, backend.executeCount)
    }

    @Test
    fun `failure after input reports an unknown outcome and is never retried`() = runBlocking {
        val backend = FakeComputerUseBackend().apply {
            executeFailure = ComputerUseBackendExecutionException(
                mutationStarted = true,
                cause = IllegalStateException("desktop failed"),
            )
        }
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)

        val error = assertFailsWith<ComputerUseBackendExecutionException> {
            controller.act(
                observed.reference,
                listOf(ComputerUseAction.Click(ComputerUsePoint(10, 10))),
                2048,
            )
        }

        assertTrue(error.message.orEmpty().contains("outcome is unknown"))
        assertEquals(1, backend.executeCount)
        assertEquals(1, backend.captureCount)
    }

    @Test
    fun `oversized action duration is rejected before input`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)

        assertFailsWith<IllegalArgumentException> {
            controller.act(
                observed.reference,
                listOf(
                    ComputerUseAction.Wait(30_000),
                    ComputerUseAction.Wait(30_000),
                    ComputerUseAction.Wait(1),
                ),
                2048,
            )
        }

        assertEquals(0, backend.executeCount)
    }

    @Test
    fun `coordinates outside the referenced screenshot are rejected before input`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)

        assertFailsWith<IllegalArgumentException> {
            controller.act(
                observed.reference,
                listOf(ComputerUseAction.Click(ComputerUsePoint(100, 10))),
                2048,
            )
        }

        assertEquals(0, backend.executeCount)
    }

    @Test
    fun `request cancellation reaches the active action sequence`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)

        assertFailsWith<CancellationException> {
            controller.act(
                observed.reference,
                listOf(ComputerUseAction.Wait(1)),
                2048,
            ) {
                throw CancellationException("turn stopped")
            }
        }

        assertEquals(0, backend.executeCount)
    }

    @Test
    fun `permission revoked after observation is rejected before input`() = runBlocking {
        val backend = FakeComputerUseBackend()
        val controller = controller(backend)
        val observed = controller.observe(Display.id, 2048)
        backend.unavailableReasonValue = "macOS Accessibility permission is missing"

        val error = assertFailsWith<IllegalStateException> {
            controller.act(
                observed.reference,
                listOf(ComputerUseAction.Click(ComputerUsePoint(10, 10))),
                2048,
            )
        }

        assertTrue(error.message.orEmpty().contains("Accessibility"))
        assertEquals(0, backend.executeCount)
    }

    @Test
    fun `macOS platform access requires screen capture and input permissions`() {
        val missingScreenCapture = JvmComputerUsePlatformAccess(
            osName = "Mac OS X",
            screenCaptureAllowed = { false },
            postEventAllowed = { true },
        )
        val missingInput = JvmComputerUsePlatformAccess(
            osName = "Mac OS X",
            screenCaptureAllowed = { true },
            postEventAllowed = { false },
        )
        val available = JvmComputerUsePlatformAccess(
            osName = "Mac OS X",
            screenCaptureAllowed = { true },
            postEventAllowed = { true },
        )

        assertTrue(missingScreenCapture.unavailableReason.orEmpty().contains("Screen Recording"))
        assertTrue(missingInput.unavailableReason.orEmpty().contains("Accessibility"))
        assertNull(missingInput.screenCaptureUnavailableReason)
        assertTrue(missingScreenCapture.screenCaptureUnavailableReason.orEmpty().contains("Screen Recording"))
        assertNull(available.unavailableReason)
    }

    @Test
    fun `non macOS platform does not call macOS permission probes`() {
        val access = JvmComputerUsePlatformAccess(
            osName = "Windows 11",
            screenCaptureAllowed = { error("must not run") },
            postEventAllowed = { error("must not run") },
        )

        assertNull(access.unavailableReason)
    }

    @Test
    fun `windows key uses the platform key code`() {
        assertEquals(KeyEvent.VK_WINDOWS, "WIN".computerUseKeyCode())
        assertEquals(KeyEvent.VK_WINDOWS, "WINDOWS".computerUseKeyCode())
    }

    @Test
    fun `bounded png dimensions describe the encoded image`() {
        val image = BufferedImage(1_200, 1_200, BufferedImage.TYPE_INT_RGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        val random = Random(1)
        pixels.indices.forEach { pixels[it] = random.nextInt() }

        val encoded = image.encodeBoundedPng()
        val decoded = ImageIO.read(ByteArrayInputStream(encoded.bytes))

        assertEquals(encoded.width, decoded.width)
        assertEquals(encoded.height, decoded.height)
        assertTrue(encoded.width < image.width)
        assertTrue(encoded.bytes.size <= 3_500_000)
    }

    private fun controller(backend: ComputerUseBackend): JvmComputerUseController =
        JvmComputerUseController(
            identity = Identity,
            properties = ConversationRuntimeWorkerProperties(
                id = Identity.workerId.value,
                capabilities = setOf(
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.COMPUTER_USE,
                ),
            ),
            backend = backend,
        )

    private class FakeComputerUseBackend : ComputerUseBackend {
        var desktopCaptureLongEdge: Int? = null
        override val screenshots: DesktopScreenshotCapture = object : DesktopScreenshotCapture {
            override val available = true
            override val unavailableReason: String? = null
            override fun capture(maxLongEdge: Int): ByteArray {
                desktopCaptureLongEdge = maxLongEdge
                return byteArrayOf(7, 8, 9)
            }
        }

        var unavailableReasonValue: String? = null
        override val available: Boolean get() = unavailableReasonValue == null
        override val unavailableReason: String? get() = unavailableReasonValue
        var executeCount = 0
        var captureCount = 0
        var executeFailure: RuntimeException? = null

        override fun targets(): List<ComputerUseDisplay> = listOf(Display)

        override fun capture(
            identity: ConversationRuntimeWorkerIdentity,
            displayId: ComputerUseDisplayId,
            maxLongEdge: Int,
        ): ComputerUseObservation {
            assertEquals(Display.id, displayId)
            captureCount += 1
            return ComputerUseObservation(
                reference = ComputerUseObservationReference(
                    id = ComputerUseObservationId("observation-$captureCount"),
                    workerId = identity.workerId,
                    workerSessionId = identity.sessionId,
                    displayId = displayId,
                    imageWidth = 100,
                    imageHeight = 60,
                    logicalOriginX = 0,
                    logicalOriginY = 0,
                    logicalWidth = 100,
                    logicalHeight = 60,
                    capturedAt = Clock.System.now(),
                ),
                png = byteArrayOf(1),
            )
        }

        override fun execute(
            observation: ComputerUseObservationReference,
            actions: List<ComputerUseAction>,
            interruptionCheck: () -> Unit,
        ) {
            executeCount += 1
            interruptionCheck()
            executeFailure?.let { throw it }
        }
    }

    private companion object {
        val Identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("worker-session-1"),
        )
        val Display = ComputerUseDisplay(
            id = ComputerUseDisplayId("display-1"),
            name = "Display 1",
            originX = 0,
            originY = 0,
            logicalWidth = 100,
            logicalHeight = 60,
            scaleX = 1.0,
            scaleY = 1.0,
            primary = true,
        )
    }
}
