package com.gromozeka.domain.tool.skills

import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MATERIALIZE_AGENT_SKILL_TOOL_NAME = "grz_skill_materialize"

data class MaterializeAgentSkillRequest(
    @property:ToolParameter(
        description = "Exact Skill id from the catalog or grz_skill_activate.",
    )
    val skill_id: String,
    @property:ToolParameter(
        description = "Matching content hash from the same catalog entry or activation.",
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
        get() = "Copy an assigned Skill package to the selected workspace's managed .gromozeka/skills directory " +
            "for runtime use. Materialization is effectively read-only because it is runtime setup and may be " +
            "required for otherwise read-only operations. It does not edit project source files or execute package files."

    override val requestType: Class<MaterializeAgentSkillRequest>
        get() = MaterializeAgentSkillRequest::class.java

    override fun execute(
        request: MaterializeAgentSkillRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
