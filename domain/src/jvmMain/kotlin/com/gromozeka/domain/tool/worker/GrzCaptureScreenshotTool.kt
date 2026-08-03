package com.gromozeka.domain.tool.worker

import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter
import com.gromozeka.domain.tool.WorkerInspectionToolMetadata

data class CaptureScreenshotRequest(
    @property:ToolParameter(
        description = "Maximum width or height of the returned screenshot in pixels.",
        minimum = 1024,
        maximum = 4096,
    )
    val max_long_edge: Int = 2560,
) {
    init {
        require(max_long_edge in 1024..4096) { "max_long_edge must be between 1024 and 4096" }
    }
}

interface GrzCaptureScreenshotTool : Tool<CaptureScreenshotRequest, AiToolResult.Binary> {
    override val name: String
        get() = NAME

    override val description: String
        get() = "Capture the complete visible desktop of the explicitly selected Worker and return it as an image. " +
            "Use this to inspect that Worker's current graphical screen. This does not capture the user's web client " +
            "or another Worker. The tool is advertised only by Workers with an available graphical desktop."

    override val metadata
        get() = WorkerInspectionToolMetadata

    override val requestType: Class<CaptureScreenshotRequest>
        get() = CaptureScreenshotRequest::class.java

    override fun execute(
        request: CaptureScreenshotRequest,
        context: ToolExecutionContext?,
    ): AiToolResult.Binary

    companion object {
        const val NAME = "grz_capture_screenshot"
    }
}
