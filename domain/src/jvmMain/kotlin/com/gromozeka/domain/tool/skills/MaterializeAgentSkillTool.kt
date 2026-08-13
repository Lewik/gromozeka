package com.gromozeka.domain.tool.skills

import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MATERIALIZE_AGENT_SKILL_TOOL_NAME = "materialize_agent_skill"

data class MaterializeAgentSkillRequest(
    @property:ToolParameter(
        description = "Exact Agent Skill name from the compact catalog.",
    )
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "Agent Skill name must not be blank" }
    }
}

interface MaterializeAgentSkillTool : Tool<MaterializeAgentSkillRequest, Map<String, Any>> {
    override val name: String
        get() = MATERIALIZE_AGENT_SKILL_TOOL_NAME

    override val metadata
        get() = PreloadedWorkspaceToolMetadata

    override val description: String
        get() = "Materialize the complete package of one Agent Skill assigned to this agent in the selected workspace. " +
            "Use this when activated instructions need scripts, templates, binaries, or ordinary filesystem paths. " +
            "The result returns a stable versioned directory; it does not execute files or install dependencies."

    override val requestType: Class<MaterializeAgentSkillRequest>
        get() = MaterializeAgentSkillRequest::class.java

    override fun execute(
        request: MaterializeAgentSkillRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
