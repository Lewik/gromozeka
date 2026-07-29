package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.RuntimeCatalogTemplates
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service
internal class ControlMcpAgentCatalogTools(
    private val agentService: AgentDomainService,
    private val promptService: PromptDomainService,
    private val skillService: AgentSkillDomainService,
    private val templateService: RuntimeCatalogTemplateService,
    private val projectAccessService: ProjectAccessService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_runtime_template_get",
            description = "Read bundled Agent, Prompt, and AI catalog templates. Templates are blueprints, not live configuration.",
            readOnly = true,
        ) {
            entityResult(
                "templates",
                RuntimeCatalogTemplates.serializer(),
                templateService.getTemplates(),
            )
        },
        controlMcpTool(
            name = "grz_agent_list",
            description = "List Agents. Omit projectId for the complete catalog; provide it for global Agents plus that project's Agents.",
            inputSchema = optionalProjectSchema(),
            readOnly = true,
        ) { input ->
            val projectId = input.optionalString("projectId")?.let(Project::Id)
            val agents = if (projectId != null) {
                projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
                agentService.findByProject(projectId)
            } else {
                val readableProjectIds = projectAccessService.findAll(user.id).mapTo(
                    mutableSetOf(),
                    Project::id,
                )
                agentService.findAll().filter {
                    it.projectId == null || it.projectId in readableProjectIds
                }
            }
            listResult("agents", AgentDefinition.serializer(), agents)
        },
        controlMcpTool(
            name = "grz_agent_get",
            description = "Read one server-managed Agent definition by id.",
            inputSchema = idSchema("agentId", "Agent id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("agentId")
            val agent = agentService.findById(AgentDefinition.Id(id)) ?: notFound("Agent", id)
            requireAgentAccess(agent, ProjectPermission.READ)
            entityResult(
                "agent",
                AgentDefinition.serializer(),
                agent,
            )
        },
        controlMcpTool(
            name = "grz_agent_create",
            description = "Create a global or project Agent from existing Prompt, Skill, tool, and model configuration ids.",
            inputSchema = agentWriteSchema(includeId = false, includeProjectId = true),
            readOnly = false,
        ) { input ->
            val projectId = input.optionalString("projectId")?.let(Project::Id)
            requireScopeAccess(projectId, ProjectPermission.WRITE)
            entityResult(
                "agent",
                AgentDefinition.serializer(),
                agentService.createAgent(
                    projectId = projectId,
                    name = input.requiredString("name"),
                    prompts = input.requiredStringList("promptIds").map(Prompt::Id),
                    runtimeSelection = input.decodeObject("runtimeSelection", AiRuntimeSelection.serializer()),
                    runtimeOverrides = input.decodeOptionalObject(
                        "runtimeOverrides",
                        AiRuntimeOverrides.serializer(),
                    ) ?: AiRuntimeOverrides(),
                    tools = input.optionalStringList("tools"),
                    description = input.optionalString("description"),
                    skills = input.optionalStringList("skillIds").map(AgentSkill::Id),
                )
            )
        },
        controlMcpTool(
            name = "grz_agent_update",
            description = "Replace every mutable field of an existing Agent definition.",
            inputSchema = agentWriteSchema(includeId = true, includeProjectId = false),
            readOnly = false,
        ) { input ->
            val id = input.requiredString("agentId")
            val agentId = AgentDefinition.Id(id)
            val existing = agentService.findById(agentId) ?: notFound("Agent", id)
            requireAgentAccess(existing, ProjectPermission.WRITE)
            entityResult(
                "agent",
                AgentDefinition.serializer(),
                agentService.update(
                    id = agentId,
                    name = input.requiredString("name"),
                    prompts = input.requiredStringList("promptIds").map(Prompt::Id),
                    description = input.optionalString("description"),
                    skills = input.optionalStringList("skillIds").map(AgentSkill::Id),
                    runtimeSelection = input.decodeObject("runtimeSelection", AiRuntimeSelection.serializer()),
                    runtimeOverrides = input.decodeOptionalObject(
                        "runtimeOverrides",
                        AiRuntimeOverrides.serializer(),
                    ) ?: AiRuntimeOverrides(),
                    tools = input.optionalStringList("tools"),
                ) ?: notFound("Agent", id),
            )
        },
        controlMcpTool(
            name = "grz_agent_duplicate",
            description = "Create a new Agent by copying an existing Agent into global or project scope.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "sourceAgentId" to ControlMcpSchemas.string("Source Agent id."),
                    "projectId" to ControlMcpSchemas.string("Optional destination project id. Omit for global scope."),
                    "name" to ControlMcpSchemas.string("Name for the new Agent."),
                ),
                required = listOf("sourceAgentId", "name"),
            ),
            readOnly = false,
        ) { input ->
            val sourceId = AgentDefinition.Id(input.requiredString("sourceAgentId"))
            val source = agentService.findById(sourceId)
                ?: notFound("Agent", sourceId.value)
            requireAgentAccess(source, ProjectPermission.READ)
            val projectId = input.optionalString("projectId")?.let(Project::Id)
            requireScopeAccess(projectId, ProjectPermission.WRITE)
            entityResult(
                "agent",
                AgentDefinition.serializer(),
                agentService.duplicateAgent(
                    projectId = projectId,
                    sourceAgentId = sourceId,
                    name = input.requiredString("name"),
                )
            )
        },
        controlMcpTool(
            name = "grz_agent_delete",
            description = "Delete an Agent. The current default Agent cannot be deleted.",
            inputSchema = idSchema("agentId", "Agent id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("agentId")
            val agentId = AgentDefinition.Id(id)
            val existing = agentService.findById(agentId) ?: notFound("Agent", id)
            requireAgentAccess(existing, ProjectPermission.WRITE)
            agentService.delete(agentId)
            deletedResult("agent", id)
        },
        controlMcpTool(
            name = "grz_prompt_list",
            description = "List Prompts. Omit projectId for the complete catalog; provide it for global Prompts plus that project's Prompts.",
            inputSchema = optionalProjectSchema(),
            readOnly = true,
        ) { input ->
            val projectId = input.optionalString("projectId")?.let(Project::Id)
            val prompts = if (projectId != null) {
                projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
                promptService.findByProject(projectId)
            } else {
                val readableProjectIds = projectAccessService.findAll(user.id).mapTo(
                    mutableSetOf(),
                    Project::id,
                )
                promptService.findAll().filter {
                    it.projectId == null || it.projectId in readableProjectIds
                }
            }
            listResult("prompts", Prompt.serializer(), prompts)
        },
        controlMcpTool(
            name = "grz_prompt_get",
            description = "Read one server-managed Prompt by id.",
            inputSchema = idSchema("promptId", "Prompt id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("promptId")
            val prompt = promptService.findById(Prompt.Id(id)) ?: notFound("Prompt", id)
            requirePromptAccess(prompt, ProjectPermission.READ)
            entityResult(
                "prompt",
                Prompt.serializer(),
                prompt,
            )
        },
        controlMcpTool(
            name = "grz_prompt_create",
            description = "Create a global or project Prompt from explicit text content.",
            inputSchema = promptWriteSchema(includeId = false, includeProjectId = true),
            readOnly = false,
        ) { input ->
            val projectId = input.optionalString("projectId")?.let(Project::Id)
            requireScopeAccess(projectId, ProjectPermission.WRITE)
            entityResult(
                "prompt",
                Prompt.serializer(),
                promptService.createPrompt(
                    projectId = projectId,
                    name = input.requiredString("name"),
                    content = input.requiredRawString("content"),
                )
            )
        },
        controlMcpTool(
            name = "grz_prompt_update",
            description = "Replace the name and content of an existing Prompt.",
            inputSchema = promptWriteSchema(includeId = true, includeProjectId = false),
            readOnly = false,
        ) { input ->
            val id = input.requiredString("promptId")
            val promptId = Prompt.Id(id)
            val existing = promptService.findById(promptId) ?: notFound("Prompt", id)
            requirePromptAccess(existing, ProjectPermission.WRITE)
            entityResult(
                "prompt",
                Prompt.serializer(),
                promptService.updatePrompt(
                    id = promptId,
                    name = input.requiredString("name"),
                    content = input.requiredRawString("content"),
                ) ?: notFound("Prompt", id),
            )
        },
        controlMcpTool(
            name = "grz_prompt_delete",
            description = "Delete an unreferenced Prompt. Prompts still used by Agents are rejected.",
            inputSchema = idSchema("promptId", "Prompt id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("promptId")
            val promptId = Prompt.Id(id)
            val existing = promptService.findById(promptId) ?: notFound("Prompt", id)
            requirePromptAccess(existing, ProjectPermission.WRITE)
            promptService.deletePrompt(promptId)
            deletedResult("prompt", id)
        },
        controlMcpTool(
            name = "grz_skill_list",
            description = "List imported Agent Skills owned by one project.",
            inputSchema = idSchema("projectId", "Project id."),
            readOnly = true,
        ) { input ->
            val projectId = Project.Id(input.requiredString("projectId"))
            projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
            listResult(
                "skills",
                AgentSkill.serializer(),
                skillService.findByProject(projectId),
            )
        },
        controlMcpTool(
            name = "grz_skill_get",
            description = "Read one imported Agent Skill's metadata and instructions.",
            inputSchema = idSchema("skillId", "Agent Skill id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("skillId")
            val skill = skillService.findById(AgentSkill.Id(id)) ?: notFound("Agent Skill", id)
            projectAccessService.requirePermission(
                user.id,
                skill.projectId,
                ProjectPermission.READ,
            )
            entityResult(
                "skill",
                AgentSkill.serializer(),
                skill,
            )
        },
        controlMcpTool(
            name = "grz_skill_import_inline",
            description = "Import or update one Agent Skill package from explicit inline files. Each file must provide exactly one of text or base64. The complete MCP request must fit the 4 MiB transport limit.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "projectId" to ControlMcpSchemas.string("Owning project id."),
                    "directoryName" to ControlMcpSchemas.string("Skill package directory name."),
                    "files" to ControlMcpSchemas.objectArray(
                        "Skill files: [{path, text}] for UTF-8 text or [{path, base64}] for binary content."
                    ),
                ),
                required = listOf("projectId", "directoryName", "files"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            val projectId = Project.Id(input.requiredString("projectId"))
            projectAccessService.requirePermission(user.id, projectId, ProjectPermission.WRITE)
            val source = input.toInlineSkillPackage()
            entityResult(
                "skill",
                AgentSkill.serializer(),
                skillService.importPackage(
                    projectId = projectId,
                    source = source,
                )
            )
        },
        controlMcpTool(
            name = "grz_skill_export",
            description = "Export one Agent Skill package. UTF-8 files are returned as text and other files as base64.",
            inputSchema = idSchema("skillId", "Agent Skill id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("skillId")
            val skillId = AgentSkill.Id(id)
            val skill = skillService.findById(skillId) ?: notFound("Agent Skill", id)
            projectAccessService.requirePermission(user.id, skill.projectId, ProjectPermission.READ)
            val packageValue = skillService.exportPackage(skillId)
                ?: notFound("Agent Skill", id)
            packageValue.toControlJson()
        },
        controlMcpTool(
            name = "grz_skill_delete",
            description = "Delete an Agent Skill that is not assigned to an Agent.",
            inputSchema = idSchema("skillId", "Agent Skill id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("skillId")
            val skillId = AgentSkill.Id(id)
            val skill = skillService.findById(skillId) ?: notFound("Agent Skill", id)
            projectAccessService.requirePermission(user.id, skill.projectId, ProjectPermission.WRITE)
            skillService.delete(skillId)
            deletedResult("agent_skill", id)
        },
    )

    private suspend fun ControlMcpCallContext.requireAgentAccess(
        agent: AgentDefinition,
        permission: ProjectPermission,
    ) {
        requireScopeAccess(agent.projectId, permission)
    }

    private suspend fun ControlMcpCallContext.requirePromptAccess(
        prompt: Prompt,
        permission: ProjectPermission,
    ) {
        requireScopeAccess(prompt.projectId, permission)
    }

    private suspend fun ControlMcpCallContext.requireScopeAccess(
        projectId: Project.Id?,
        permission: ProjectPermission,
    ) {
        if (projectId == null) {
            if (permission != ProjectPermission.READ) {
                requireServerOwner()
            }
        } else {
            projectAccessService.requirePermission(user.id, projectId, permission)
        }
    }
}

private fun optionalProjectSchema() =
    ControlMcpSchemas.objectSchema(
        properties = mapOf(
            "projectId" to ControlMcpSchemas.string("Optional project id."),
        )
    )

private fun agentWriteSchema(
    includeId: Boolean,
    includeProjectId: Boolean,
) =
    ControlMcpSchemas.objectSchema(
        properties = buildMap {
            if (includeId) put("agentId", ControlMcpSchemas.string("Agent id."))
            if (includeProjectId) {
                put("projectId", ControlMcpSchemas.string("Optional project id. Omit for global scope."))
            }
            put("name", ControlMcpSchemas.string("Agent name."))
            put("promptIds", ControlMcpSchemas.stringArray("Ordered Prompt ids."))
            put(
                "runtimeSelection",
                ControlMcpSchemas.objectValue("AiRuntimeSelection object containing modelConfigurationId."),
            )
            put(
                "runtimeOverrides",
                ControlMcpSchemas.objectValue("Optional AiRuntimeOverrides object."),
            )
            put("tools", ControlMcpSchemas.stringArray("Always-loaded tool names."))
            put("description", ControlMcpSchemas.string("Optional Agent description."))
            put("skillIds", ControlMcpSchemas.stringArray("Project Agent Skill ids."))
        },
        required = buildList {
            if (includeId) add("agentId")
            addAll(listOf("name", "promptIds", "runtimeSelection"))
        },
    )

private fun promptWriteSchema(
    includeId: Boolean,
    includeProjectId: Boolean,
) =
    ControlMcpSchemas.objectSchema(
        properties = buildMap {
            if (includeId) put("promptId", ControlMcpSchemas.string("Prompt id."))
            if (includeProjectId) {
                put("projectId", ControlMcpSchemas.string("Optional project id. Omit for global scope."))
            }
            put("name", ControlMcpSchemas.string("Prompt name."))
            put("content", ControlMcpSchemas.string("Exact Prompt text."))
        },
        required = buildList {
            if (includeId) add("promptId")
            addAll(listOf("name", "content"))
        },
    )

private fun JsonObject.requiredRawString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be a non-blank string")

private fun <T> JsonObject.decodeObject(
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
): T = controlMcpJson.decodeFromJsonElement(serializer, requiredObject(name))

private fun <T> JsonObject.decodeOptionalObject(
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
): T? {
    val value = this[name] ?: return null
    val objectValue = value as? JsonObject
        ?: throw ControlMcpToolException("invalid_argument", "'$name' must be an object")
    return controlMcpJson.decodeFromJsonElement(serializer, objectValue)
}

private fun <T> listResult(
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    values: List<T>,
): JsonObject = buildJsonObject {
    put(name, controlMcpJson.encodeToJsonElement(ListSerializer(serializer), values))
}

private fun JsonObject.toInlineSkillPackage(): AgentSkillPackageSource {
    val fileValues = this["files"] as? JsonArray
        ?: throw ControlMcpToolException("invalid_argument", "'files' must be an array")
    require(fileValues.isNotEmpty()) { "Agent Skill package must contain files" }
    val files = fileValues.mapIndexed { index, value ->
        val file = value as? JsonObject
            ?: throw ControlMcpToolException("invalid_argument", "'files[$index]' must be an object")
        val path = file.requiredString("path")
        val text = (file["text"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        val base64 = (file["base64"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        require((text == null) != (base64 == null)) {
            "Agent Skill file '$path' must provide exactly one of text or base64"
        }
        val content = text?.toByteArray(StandardCharsets.UTF_8)
            ?: runCatching { Base64.getDecoder().decode(base64) }
                .getOrElse {
                    throw ControlMcpToolException(
                        "invalid_argument",
                        "Agent Skill file '$path' contains invalid base64",
                    )
                }
        AgentSkillFile(path = path, content = content)
    }
    return AgentSkillPackageSource(
        directoryName = requiredString("directoryName"),
        files = files,
    )
}

private fun AgentSkillPackage.toControlJson(): JsonObject =
    buildJsonObject {
        put("skill", controlMcpJson.encodeToJsonElement(AgentSkill.serializer(), skill))
        put(
            "files",
            JsonArray(
                files.map { file ->
                    buildJsonObject {
                        put("path", file.path)
                        file.content.decodeUtf8OrNull()?.let { text ->
                            put("text", text)
                        } ?: put("base64", Base64.getEncoder().encodeToString(file.content))
                    }
                }
            )
        )
    }

private fun ByteArray.decodeUtf8OrNull(): String? =
    runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()
