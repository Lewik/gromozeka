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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmComputerUseControllerTest {
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
        override val available = true
        override val unavailableReason: String? = null
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
