package com.gromozeka.domain.tool.skills

import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MATERIALIZE_AGENT_SKILL_TOOL_NAME = "grz_skill_materialize"

data class MaterializeAgentSkillRequest(
    @property:ToolParameter(
        description = "Skill id returned by grz_skill_activate.",
    )
    val skill_id: String,
    @property:ToolParameter(
        description = "Content hash returned by the same activation.",
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
        get() = "Materialize an activated immutable Skill in the selected workspace for runtime use. " +
            "It returns a versioned path and does not execute files. To edit the Skill itself, use " +
            "grz_skill_export_to_directory and grz_skill_import_from_directory instead."

    override val requestType: Class<MaterializeAgentSkillRequest>
        get() = MaterializeAgentSkillRequest::class.java

    override fun execute(
        request: MaterializeAgentSkillRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
