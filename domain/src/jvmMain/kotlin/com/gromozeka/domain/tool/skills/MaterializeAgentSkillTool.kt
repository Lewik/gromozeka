package com.gromozeka.domain.tool.skills

import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MATERIALIZE_AGENT_SKILL_TOOL_NAME = "materialize_agent_skill"

data class MaterializeAgentSkillRequest(
    @property:ToolParameter(
        description = "Immutable Agent Skill id returned by open_agent_skill.",
    )
    val skill_id: String,
    @property:ToolParameter(
        description = "Exact package content hash returned by the same open_agent_skill call.",
    )
    val content_hash: String,
) {
    init {
        require(skill_id.isNotBlank()) { "Agent Skill id must not be blank" }
        require(content_hash.matches(CONTENT_HASH_PATTERN)) {
            "Agent Skill content hash must be a lowercase SHA-256 value"
        }
    }

    private companion object {
        val CONTENT_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

interface MaterializeAgentSkillTool : Tool<MaterializeAgentSkillRequest, Map<String, Any>> {
    override val name: String
        get() = MATERIALIZE_AGENT_SKILL_TOOL_NAME

    override val metadata
        get() = PreloadedWorkspaceToolMetadata

    override val description: String
        get() = "Materialize the exact immutable package opened by open_agent_skill in the selected workspace. " +
            "Use this when opened instructions need scripts, templates, binaries, or ordinary filesystem paths. " +
            "The result returns a stable versioned directory; it does not execute files or install dependencies."

    override val requestType: Class<MaterializeAgentSkillRequest>
        get() = MaterializeAgentSkillRequest::class.java

    override fun execute(
        request: MaterializeAgentSkillRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
