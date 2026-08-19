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
import kotlinx.serialization.json.buildJsonArray
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
            description = "List paginated Skill summaries for a project. Instructions and files are omitted.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "projectId" to ControlMcpSchemas.string("Project id."),
                    "limit" to ControlMcpSchemas.integer(
                        description = "Maximum skill summaries in this page.",
                        minimum = 1,
                        maximum = SKILL_LIST_MAX_LIMIT,
                    ),
                    "cursor" to ControlMcpSchemas.string("Opaque nextCursor from the previous page."),
                ),
                required = listOf("projectId"),
            ),
            readOnly = true,
        ) { input ->
            val projectId = Project.Id(input.requiredString("projectId"))
            projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
            val limit = input.optionalInt("limit", SKILL_LIST_DEFAULT_LIMIT, 1..SKILL_LIST_MAX_LIMIT)
            val cursor = input.optionalString("cursor")?.decodeSkillListCursor()
            val candidates = skillService.findByProject(projectId)
                .sortedWith(compareBy<AgentSkill>({ it.name.lowercase() }, { it.id.value }))
                .filter { skill -> cursor == null || skill.listKey() > cursor }
                .take(limit + 1)
            val page = candidates.take(limit)
            buildJsonObject {
                put("skills", buildJsonArray {
                    page.forEach { add(it.toControlSummaryJson()) }
                })
                if (candidates.size > limit) {
                    put("nextCursor", page.last().listKey().encodeSkillListCursor())
                }
                put("hasMore", candidates.size > limit)
            }
        },
        controlMcpTool(
            name = "grz_skill_get",
            description = "Read Skill metadata, instructions, materialization analysis, and file manifest without file contents.",
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
            val packageValue = skillService.exportPackage(skill.id)
                ?: notFound("Agent Skill", id)
            buildJsonObject {
                put("skill", controlMcpJson.encodeToJsonElement(AgentSkill.serializer(), skill))
                put("files", packageValue.toManifestJson())
            }
        },
        controlMcpTool(
            name = "grz_skill_import_inline",
            description = "Import a complete Skill package inline, replacing every file under the same name. " +
                "Text and base64 binaries enter model context; prefer directory import for editing. Limit: 4 MiB.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "projectId" to ControlMcpSchemas.string("Owning project id."),
                    "directoryName" to ControlMcpSchemas.string("Skill package directory name."),
                    "files" to ControlMcpSchemas.objectArray(
                        description = "Complete package files. Use utf-8 for exact text or base64 for binary bytes.",
                        properties = mapOf(
                            "path" to ControlMcpSchemas.string("Normalized relative package path."),
                            "encoding" to ControlMcpSchemas.string(
                                description = "How content is encoded.",
                                enum = listOf("utf-8", "base64"),
                            ),
                            "content" to ControlMcpSchemas.string("Complete file content in the selected encoding."),
                        ),
                        required = listOf("path", "encoding", "content"),
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
                    actorUserId = user.id,
                )
            )
        },
        controlMcpTool(
            name = "grz_skill_export_inline",
            description = "Export a complete Skill package inline as text and base64. All files enter model context; " +
                "prefer directory export for editing.",
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
        val encoding = file.requiredString("encoding")
        val encodedContent = (file["content"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw ControlMcpToolException(
                "invalid_argument",
                "Agent Skill file '$path' content must be a string",
            )
        val content = when (encoding) {
            "utf-8" -> encodedContent.toByteArray(StandardCharsets.UTF_8)
            "base64" -> runCatching { Base64.getDecoder().decode(encodedContent) }
                .getOrElse {
                    throw ControlMcpToolException(
                        "invalid_argument",
                        "Agent Skill file '$path' contains invalid base64",
                    )
                }
            else -> throw ControlMcpToolException(
                "invalid_argument",
                "Agent Skill file '$path' encoding must be utf-8 or base64",
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
        put("skill", skill.toControlSummaryJson())
        put(
            "files",
            JsonArray(
                files.map { file ->
                    buildJsonObject {
                        put("path", file.path)
                        val text = file.content.decodeUtf8OrNull()
                        if (text != null) {
                            put("encoding", "utf-8")
                            put("content", text)
                        } else {
                            put("encoding", "base64")
                            put("content", Base64.getEncoder().encodeToString(file.content))
                        }
                    }
                }
            )
        )
    }

private fun AgentSkillPackage.toManifestJson(): JsonArray =
    JsonArray(
        files.map { file ->
            buildJsonObject {
                put("path", file.path)
                put("sizeBytes", file.content.size)
                put("encoding", if (file.content.decodeUtf8OrNull() != null) "utf-8" else "binary")
            }
        }
    )

private fun AgentSkill.toControlSummaryJson(): JsonObject =
    buildJsonObject {
        put("id", id.value)
        put("projectId", projectId.value)
        put("name", name)
        put("description", description)
        put("materializationPolicy", materializationPlan.policy.name)
        put("materializationReason", materializationPlan.reason)
        put("contentHash", contentHash)
        put("updatedAt", updatedAt.toString())
    }

private data class SkillListKey(
    val normalizedName: String,
    val id: String,
) : Comparable<SkillListKey> {
    override fun compareTo(other: SkillListKey): Int =
        compareValuesBy(this, other, SkillListKey::normalizedName, SkillListKey::id)
}

private fun AgentSkill.listKey(): SkillListKey = SkillListKey(name.lowercase(), id.value)

private fun SkillListKey.encodeSkillListCursor(): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$normalizedName\u0000$id".toByteArray(StandardCharsets.UTF_8))

private fun String.decodeSkillListCursor(): SkillListKey {
    val decoded = runCatching {
        Base64.getUrlDecoder().decode(this).toString(StandardCharsets.UTF_8)
    }.getOrElse {
        throw ControlMcpToolException("invalid_argument", "Invalid skill list cursor")
    }
    val separator = decoded.indexOf('\u0000')
    if (separator <= 0 || separator == decoded.lastIndex) {
        throw ControlMcpToolException("invalid_argument", "Invalid skill list cursor")
    }
    return SkillListKey(decoded.substring(0, separator), decoded.substring(separator + 1))
}

private fun ByteArray.decodeUtf8OrNull(): String? =
    runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()?.takeIf { '\u0000' !in it }

private const val SKILL_LIST_DEFAULT_LIMIT = 50
private const val SKILL_LIST_MAX_LIMIT = 200
