package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkerId
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import com.gromozeka.domain.tool.worker.GrzCaptureScreenshotTool
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCaptureScreenshotToolImpl(
    @Value("\${gromozeka.runtime.worker.id}") configuredWorkerId: String,
) : GrzCaptureScreenshotTool {
    private val localWorkerId = ConversationRuntimeWorkerId(configuredWorkerId.trim())

    override val available: Boolean
        get() = !GraphicsEnvironment.isHeadless()

    override fun execute(
        request: CaptureScreenshotRequest,
        context: ToolExecutionContext?,
    ): AiToolResult.Binary {
        val workerId = context.requiredWorkerId()
        check(workerId == localWorkerId) {
            "Screenshot request for ${workerId.value} reached ${localWorkerId.value}"
        }
        check(available) { "Worker ${localWorkerId.value} has no graphical desktop to capture" }

        val desktopBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduceOrNull(Rectangle::union)
            ?: error("Worker ${localWorkerId.value} has no displays")
        val captured = Robot().createScreenCapture(desktopBounds)
        val bounded = captured.fitLongEdge(request.max_long_edge)
        val encoded = bounded.encodeBoundedPng()
        return AiToolResult.Binary(
            content = encoded,
            fileName = "worker-${localWorkerId.value}-screenshot.png",
            mediaType = "image/png",
        )
    }
}

private fun BufferedImage.fitLongEdge(maxLongEdge: Int): BufferedImage {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return this
    return resize(maxLongEdge.toDouble() / longEdge)
}

private fun BufferedImage.encodeBoundedPng(): ByteArray {
    var current = this
    while (true) {
        val encoded = current.encodePng()
        if (encoded.size <= MAX_SCREENSHOT_BYTES || maxOf(current.width, current.height) <= MIN_LONG_EDGE) {
            return encoded
        }
        current = current.resize(0.8)
    }
}

private fun BufferedImage.resize(scale: Double): BufferedImage {
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun BufferedImage.encodePng(): ByteArray =
    ByteArrayOutputStream().use { output ->
        check(ImageIO.write(this, "png", output)) { "PNG encoder is unavailable" }
        output.toByteArray()
    }

private const val MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
private const val MIN_LONG_EDGE = 1024
