package com.gromozeka.domain.tool.worker

import com.gromozeka.domain.service.ComputerUseMouseButton
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolParameter
import com.gromozeka.domain.tool.WorkerComputerUseToolMetadata

class ComputerTargetsRequest

data class ComputerObserveRequest(
    @property:ToolParameter(description = "Exact display id returned by grz_computer_targets.")
    val display_id: String,
    @property:ToolParameter(
        description = "Maximum width or height of the returned screenshot in pixels.",
        minimum = 1024,
        maximum = 4096,
    )
    val max_long_edge: Int = 1568,
)

enum class ComputerActionKind {
    MOVE,
    CLICK,
    DRAG,
    SCROLL,
    TYPE_TEXT,
    KEY_CHORD,
    WAIT,
}

data class ComputerActionRequest(
    @property:ToolParameter(description = "Action kind.")
    val kind: ComputerActionKind,
    @property:ToolParameter(description = "X coordinate in pixels of the referenced screenshot.")
    val x: Int? = null,
    @property:ToolParameter(description = "Y coordinate in pixels of the referenced screenshot.")
    val y: Int? = null,
    @property:ToolParameter(description = "Destination X coordinate for DRAG.")
    val to_x: Int? = null,
    @property:ToolParameter(description = "Destination Y coordinate for DRAG.")
    val to_y: Int? = null,
    @property:ToolParameter(description = "Mouse button for CLICK or DRAG.")
    val button: ComputerUseMouseButton = ComputerUseMouseButton.LEFT,
    @property:ToolParameter(description = "Click count for CLICK.", minimum = 1, maximum = 3)
    val click_count: Int = 1,
    @property:ToolParameter(description = "Horizontal scroll distance. Positive values scroll right.")
    val delta_x: Int = 0,
    @property:ToolParameter(description = "Vertical scroll distance. Positive values scroll down.")
    val delta_y: Int = 0,
    @property:ToolParameter(description = "Text for TYPE_TEXT.")
    val text: String? = null,
    @property:ToolParameter(description = "Ordered keys for KEY_CHORD, for example [\"CTRL\", \"L\"].")
    val keys: List<String> = emptyList(),
    @property:ToolParameter(description = "Duration for MOVE, DRAG, WAIT, or delay between typed characters.")
    val duration_ms: Long = 0,
)

data class ComputerActRequest(
    @property:ToolParameter(
        description = "Opaque observation_ref returned by the latest grz_computer_observe or grz_computer_act call."
    )
    val observation_ref: String,
    @property:ToolParameter(
        description = "Ordered bounded input actions. Execution stops at the first failure or interruption."
    )
    val actions: List<ComputerActionRequest>,
    @property:ToolParameter(
        description = "Maximum width or height of the post-action screenshot in pixels.",
        minimum = 1024,
        maximum = 4096,
    )
    val max_long_edge: Int = 1568,
)

interface GrzComputerTargetsTool : Tool<ComputerTargetsRequest, Map<String, Any>> {
    override val name: String get() = NAME
    override val description: String
        get() = "List graphical displays available for Computer Use on the explicitly selected Worker. " +
            "Computer Use controls the real interactive desktop."
    override val metadata get() = WorkerComputerUseToolMetadata
    override val requestType: Class<ComputerTargetsRequest> get() = ComputerTargetsRequest::class.java

    companion object {
        const val NAME = "grz_computer_targets"
    }
}

interface GrzComputerObserveTool : Tool<ComputerObserveRequest, List<AiToolResult>> {
    override val name: String get() = NAME
    override val description: String
        get() = "Capture one display on the explicitly selected Worker. Returns an image and an opaque " +
            "observation_ref that defines its coordinate frame. The desktop may change immediately after capture."
    override val metadata get() = WorkerComputerUseToolMetadata
    override val requestType: Class<ComputerObserveRequest> get() = ComputerObserveRequest::class.java

    companion object {
        const val NAME = "grz_computer_observe"
    }
}

interface GrzComputerActTool : Tool<ComputerActRequest, List<AiToolResult>> {
    override val name: String get() = NAME
    override val description: String
        get() = "Synchronously execute ordered mouse, keyboard, scroll, or wait actions using one Computer Use " +
            "observation coordinate frame, then return a fresh screenshot. Never retry this tool automatically. " +
            "After a timeout, disconnect, or uncertain failure, capture a fresh observation before deciding what to do."
    override val metadata get() = WorkerComputerUseToolMetadata
    override val requestType: Class<ComputerActRequest> get() = ComputerActRequest::class.java

    companion object {
        const val NAME = "grz_computer_act"
    }
}
