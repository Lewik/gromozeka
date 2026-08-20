package com.gromozeka.domain.tool.skills

import com.gromozeka.domain.tool.PreloadedWorkerToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val EXPORT_AGENT_SKILL_TO_DIRECTORY_TOOL_NAME = "grz_skill_export_to_directory"
const val IMPORT_AGENT_SKILL_FROM_DIRECTORY_TOOL_NAME = "grz_skill_import_from_directory"

data class ExportAgentSkillToDirectoryRequest(
    @property:ToolParameter(description = "Exact Skill id from the catalog or grz_skill_activate.")
    val skill_id: String,
    @property:ToolParameter(description = "Matching content hash from the same catalog entry or activation.")
    val content_hash: String,
    @property:ToolParameter(description = "New destination directory on the selected Worker.")
    val directory_path: String,
) {
    init {
        require(skill_id.isNotBlank()) { "Agent Skill id must not be blank" }
        require(content_hash.matches(CONTENT_HASH_PATTERN)) {
            "Agent Skill content hash must be a lowercase SHA-256 value"
        }
        require(directory_path.isNotBlank()) { "Agent Skill destination directory must not be blank" }
    }
}

data class ImportAgentSkillFromDirectoryRequest(
    @property:ToolParameter(description = "Skill package directory on the selected Worker.")
    val directory_path: String,
    @property:ToolParameter(
        description = "Current content hash when updating. Omit only when creating a new Skill.",
    )
    val expected_content_hash: String? = null,
) {
    init {
        require(directory_path.isNotBlank()) { "Agent Skill source directory must not be blank" }
        require(expected_content_hash == null || expected_content_hash.matches(CONTENT_HASH_PATTERN)) {
            "Expected Agent Skill content hash must be a lowercase SHA-256 value"
        }
    }
}

interface ExportAgentSkillToDirectoryTool : Tool<ExportAgentSkillToDirectoryRequest, Map<String, Any>> {
    override val name: String
        get() = EXPORT_AGENT_SKILL_TO_DIRECTORY_TOOL_NAME

    override val metadata
        get() = PreloadedWorkerToolMetadata

    override val description: String
        get() = "Export an assigned Skill package to a new directory on the selected Worker."

    override val requestType: Class<ExportAgentSkillToDirectoryRequest>
        get() = ExportAgentSkillToDirectoryRequest::class.java

    override fun execute(
        request: ExportAgentSkillToDirectoryRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}

interface ImportAgentSkillFromDirectoryTool : Tool<ImportAgentSkillFromDirectoryRequest, Map<String, Any>> {
    override val name: String
        get() = IMPORT_AGENT_SKILL_FROM_DIRECTORY_TOOL_NAME

    override val metadata
        get() = PreloadedWorkerToolMetadata

    override val description: String
        get() = "Create or replace a complete Skill package from a Worker directory. " +
            "Updates replace every package file; symbolic links are rejected."

    override val requestType: Class<ImportAgentSkillFromDirectoryRequest>
        get() = ImportAgentSkillFromDirectoryRequest::class.java

    override fun execute(
        request: ImportAgentSkillFromDirectoryRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}

private val CONTENT_HASH_PATTERN = Regex("[0-9a-f]{64}")
