package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.DesktopScreenshotCapture
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkerId
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import com.gromozeka.domain.tool.worker.GrzCaptureScreenshotTool
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCaptureScreenshotToolImpl(
    @Value("\${gromozeka.runtime.worker.id}") configuredWorkerId: String,
    private val screenshots: DesktopScreenshotCapture,
) : GrzCaptureScreenshotTool {
    private val localWorkerId = ConversationRuntimeWorkerId(configuredWorkerId.trim())

    override val available: Boolean
        get() = screenshots.available

    override fun execute(
        request: CaptureScreenshotRequest,
        context: ToolExecutionContext?,
    ): AiToolResult.Binary {
        val workerId = context.requiredWorkerId()
        check(workerId == localWorkerId) {
            "Screenshot request for ${workerId.value} reached ${localWorkerId.value}"
        }
        check(available) {
            screenshots.unavailableReason ?: "Worker ${localWorkerId.value} has no graphical desktop to capture"
        }
        return AiToolResult.Binary(
            content = screenshots.capture(request.max_long_edge),
            fileName = "worker-${localWorkerId.value}-screenshot.png",
            mediaType = "image/png",
        )
    }
}
