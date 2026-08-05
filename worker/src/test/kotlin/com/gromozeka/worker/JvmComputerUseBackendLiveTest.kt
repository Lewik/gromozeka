package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ComputerUsePoint
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmComputerUseBackendLiveTest {
    @Test
    fun `real desktop capture and input`() {
        if (System.getProperty(LIVE_TEST_PROPERTY) != "true") return
        check(!GraphicsEnvironment.isHeadless()) { "Live Computer Use test needs an interactive desktop" }

        lateinit var frame: JFrame
        lateinit var textArea: JTextArea
        SwingUtilities.invokeAndWait {
            textArea = JTextArea("Replace me").apply {
                lineWrap = true
                wrapStyleWord = true
            }
            frame = JFrame("Gromozeka Computer Use Live Test").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                layout = BorderLayout()
                add(JScrollPane(textArea), BorderLayout.CENTER)
                minimumSize = Dimension(560, 280)
                pack()
                setLocationRelativeTo(null)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
            }
        }

        try {
            Thread.sleep(300)
            val backend = JvmComputerUseBackend(JvmComputerUsePlatformAccess())
            check(backend.available) { backend.unavailableReason ?: "Computer Use backend is unavailable" }
            val displayId = invokeAndWaitResult {
                ComputerUseDisplayId(frame.graphicsConfiguration.device.iDstring)
            }
            val firstObservation = backend.capture(Identity, displayId, 1568)
            val target = invokeAndWaitResult {
                textArea.locationOnScreen.center(textArea.size)
            }.toObservationPoint(firstObservation.reference)
            val modifier = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                "CMD"
            } else {
                "CTRL"
            }

            backend.execute(
                observation = firstObservation.reference,
                actions = listOf(
                    ComputerUseAction.Move(target, durationMillis = 120),
                    ComputerUseAction.Click(target),
                    ComputerUseAction.KeyChord(listOf(modifier, "A")),
                    ComputerUseAction.TypeText(EXPECTED_TEXT),
                    ComputerUseAction.Wait(100),
                ),
                interruptionCheck = {},
            )

            val actualText = invokeAndWaitResult { textArea.text }
            val secondObservation = backend.capture(Identity, displayId, 1568)
            assertEquals(EXPECTED_TEXT, actualText)
            assertTrue(firstObservation.png.isPng())
            assertTrue(secondObservation.png.isPng())
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun Point.center(size: Dimension): Point = Point(
        x + size.width / 2,
        y + size.height / 2,
    )

    private fun Point.toObservationPoint(observation: ComputerUseObservationReference): ComputerUsePoint =
        ComputerUsePoint(
            x = ((x - observation.logicalOriginX).toDouble() * observation.imageWidth / observation.logicalWidth)
                .roundToInt()
                .coerceIn(0, observation.imageWidth - 1),
            y = ((y - observation.logicalOriginY).toDouble() * observation.imageHeight / observation.logicalHeight)
                .roundToInt()
                .coerceIn(0, observation.imageHeight - 1),
        )

    private fun ByteArray.isPng(): Boolean = size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }

    private companion object {
        const val LIVE_TEST_PROPERTY = "gromozeka.computer-use.live"
        const val EXPECTED_TEXT = "Gromozeka Computer Use live smoke test"
        val Identity = ConversationRuntimeWorkerIdentity(
            ConversationRuntimeWorkerId("live-worker"),
            ConversationRuntimeWorkerSessionId("live-worker-session"),
        )
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

private fun <T> invokeAndWaitResult(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
