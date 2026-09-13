package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

data class SaveToolOutputRequest(
    val artifact_id: String,
    @property:ToolParameter(description = "Destination path inside the selected workspace.")
    val path: String,
    @property:ToolParameter(description = "original copies exact bytes; text strictly decodes source_encoding and writes UTF-8. original is also correct for text files.")
    val mode: String = "original",
    val source_encoding: String = "UTF-8",
    val overwrite: Boolean = false,
)

interface GrzSaveToolOutputTool : Tool<SaveToolOutputRequest, Map<String, Any>> {
    override val name: String get() = "grz_save_tool_output"
    override val metadata get() = PreloadedWorkspaceToolMetadata
    override val requestType: Class<SaveToolOutputRequest> get() = SaveToolOutputRequest::class.java
    override val description: String get() = """
        Save a result artifact from this conversation into the selected workspace without copying its content through the model.
        Use artifact_id from a tool result or attachment. This works for binary results and for text whose preview is truncated, garbled, or otherwise insufficient.
        mode=original preserves every byte. mode=text converts the explicitly selected source_encoding to UTF-8 and fails on invalid input instead of replacing characters.
        Existing destinations require overwrite=true. Returns the saved path, byte count, and SHA-256.
        A command-output artifact contains only the returned chunk or retained tail; it is not necessarily the complete command log. Consult the result byte cursors and output_file or error_file for the complete stream.
    """.trimIndent()

    override fun execute(request: SaveToolOutputRequest, context: ToolExecutionContext?): Map<String, Any>
}
