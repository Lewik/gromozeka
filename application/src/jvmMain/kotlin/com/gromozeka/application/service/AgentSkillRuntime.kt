package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.PreloadedServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredAgentDefinitionId
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.skills.MATERIALIZE_AGENT_SKILL_TOOL_NAME
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

const val OPEN_AGENT_SKILL_TOOL_NAME = "open_agent_skill"
const val READ_AGENT_SKILL_RESOURCE_TOOL_NAME = "read_agent_skill_resource"

private val agentSkillToolNames = setOf(
    OPEN_AGENT_SKILL_TOOL_NAME,
    READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
    MATERIALIZE_AGENT_SKILL_TOOL_NAME,
)

data class AgentSkillRuntimeCatalog(
    val toolCatalog: DistributedAiToolCatalogSnapshot,
    val systemPrompt: String?,
)

@Service
class AgentSkillRuntimeCatalogService(
    private val skillRepository: AgentSkillRepository,
) {
    suspend fun prepare(
        agent: AgentDefinition,
        projectId: Project.Id,
        toolCatalog: DistributedAiToolCatalogSnapshot,
    ): AgentSkillRuntimeCatalog {
        val skills = skillRepository.findByIds(agent.skills)
        require(skills.size == agent.skills.size) {
            "Agent ${agent.id.value} references missing Agent Skills"
        }
        require(skills.all { it.projectId == projectId }) {
            "Agent ${agent.id.value} references an Agent Skill from another project"
        }

        if (skills.isEmpty()) {
            return AgentSkillRuntimeCatalog(
                toolCatalog = toolCatalog.withoutAgentSkillTools(),
                systemPrompt = null,
            )
        }

        return AgentSkillRuntimeCatalog(
            toolCatalog = toolCatalog,
            systemPrompt = buildAgentSkillCatalogPrompt(
                skills = skills,
                openToolName = toolCatalog.entries.values
                    .firstOrNull { it.logicalName == OPEN_AGENT_SKILL_TOOL_NAME }
                    ?.modelName
                    ?: OPEN_AGENT_SKILL_TOOL_NAME,
                readResourceToolName = toolCatalog.entries.values
                    .firstOrNull { it.logicalName == READ_AGENT_SKILL_RESOURCE_TOOL_NAME }
                    ?.modelName
                    ?: READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
                materializeTool = toolCatalog.entries.values
                    .firstOrNull { it.logicalName == MATERIALIZE_AGENT_SKILL_TOOL_NAME },
            ),
        )
    }

    private fun DistributedAiToolCatalogSnapshot.withoutAgentSkillTools(): DistributedAiToolCatalogSnapshot =
        copy(
            tools = tools.filterNot { callback ->
                entries[callback.definition.name]?.logicalName in agentSkillToolNames
            },
            entries = entries.filterValues { it.logicalName !in agentSkillToolNames },
        )

}

@Component
class OpenAgentSkillToolCallback(
    private val access: AgentSkillRuntimeAccess,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Input(
        val name: String,
    )

    override val definition: AiToolDefinition = openAgentSkillDefinition()

    override val metadata = PreloadedServerToolMetadata

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        val skillPackage = access.open(context, input.name)
        val resources = skillPackage.files.filterNot { it.path == "SKILL.md" }
        buildJsonObject {
            put("name", skillPackage.skill.name)
            put("skill_id", skillPackage.skill.id.value)
            put("content_hash", skillPackage.skill.contentHash)
            put("description", skillPackage.skill.description)
            put("instructions", skillPackage.skill.instructions)
            skillPackage.skill.license?.let { put("license", it) }
            skillPackage.skill.compatibility?.let { put("compatibility", it) }
            skillPackage.skill.allowedTools?.let { put("allowed_tools", it) }
            put(
                "allowed_tools_semantics",
                "Package metadata only. It does not grant, deny, or replace Gromozeka tool permissions.",
            )
            put("resource_count", resources.size)
            putJsonObject("workspace_materialization") {
                put("policy", skillPackage.skill.materializationPlan.policy.name.lowercase())
                put("reason", skillPackage.skill.materializationPlan.reason)
                put("logical_tool_name", MATERIALIZE_AGENT_SKILL_TOOL_NAME)
                put(
                    "availability",
                    "Use the exact materialization tool exposed in the current tool set. " +
                        "If it is absent, the current Worker/workspace route is unavailable.",
                )
            }
            put("resources_truncated", resources.size > MAX_LISTED_RESOURCES)
            putJsonArray("resources") {
                resources
                    .take(MAX_LISTED_RESOURCES)
                    .forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("size_bytes", file.content.size)
                        })
                    }
            }
        }.toString()
    }

    private companion object {
        const val MAX_LISTED_RESOURCES = 200
    }
}

@Component
class ReadAgentSkillResourceToolCallback(
    private val access: AgentSkillRuntimeAccess,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Input(
        val skill_id: String,
        val content_hash: String,
        val path: String,
        val offset: Int = 0,
        val max_bytes: Int = DEFAULT_MAX_BYTES,
    )

    override val definition: AiToolDefinition = readAgentSkillResourceDefinition()

    override val metadata = PreloadedServerToolMetadata

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        require(input.offset >= 0) { "Agent Skill resource offset must not be negative" }
        require(input.max_bytes in 1..MAX_BYTES) {
            "Agent Skill resource max_bytes must be between 1 and $MAX_BYTES"
        }
        val path = normalizeAgentSkillPath(input.path)
        val skillPackage = access.resolve(
            context = context,
            skillId = AgentSkill.Id(input.skill_id),
            contentHash = input.content_hash,
        )
        val file = skillPackage.files.singleOrNull { it.path == path }
            ?: error("Agent Skill '${skillPackage.skill.name}' has no resource '$path'")
        require(input.offset <= file.content.size) {
            "Agent Skill resource offset ${input.offset} exceeds ${file.content.size} bytes"
        }

        val textFile = file.content.decodeUtf8OrNull() != null
        if (!textFile) {
            return@runBlocking buildJsonObject {
                put("name", skillPackage.skill.name)
                put("skill_id", skillPackage.skill.id.value)
                put("content_hash", skillPackage.skill.contentHash)
                put("path", file.path)
                put("size_bytes", file.content.size)
                put("readable", false)
                put("encoding", "binary")
                put(
                    "reason",
                    "Binary Agent Skill resources are not returned through model context. " +
                        "Use the workspace materialization tool when it is available.",
                )
            }.toString()
        }
        val requestedEnd = minOf(file.content.size, input.offset + input.max_bytes)
        val end = findUtf8ChunkEnd(file.content, input.offset, requestedEnd)
        val chunk = file.content.copyOfRange(input.offset, end)
        buildJsonObject {
            put("name", skillPackage.skill.name)
            put("skill_id", skillPackage.skill.id.value)
            put("content_hash", skillPackage.skill.contentHash)
            put("path", file.path)
            put("offset", input.offset)
            put("next_offset", end)
            put("size_bytes", file.content.size)
            put("complete", end == file.content.size)
            put("readable", true)
            put("encoding", "utf-8")
            put("content", checkNotNull(chunk.decodeUtf8OrNull()))
        }.toString()
    }

    private fun findUtf8ChunkEnd(
        content: ByteArray,
        offset: Int,
        requestedEnd: Int,
    ): Int {
        if (offset == requestedEnd) {
            return offset
        }
        for (end in requestedEnd downTo maxOf(offset + 1, requestedEnd - MAX_UTF8_CODE_POINT_BYTES)) {
            if (content.copyOfRange(offset, end).decodeUtf8OrNull() != null) {
                return end
            }
        }
        error(
            "Agent Skill resource offset $offset is not on a UTF-8 boundary, " +
                "or max_bytes is too small for the next UTF-8 code point"
        )
    }

    private fun ByteArray.decodeUtf8OrNull(): String? =
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
        }.getOrNull()?.takeIf { '\u0000' !in it }

    private companion object {
        const val DEFAULT_MAX_BYTES = 65_536
        const val MAX_BYTES = 1_000_000
        const val MAX_UTF8_CODE_POINT_BYTES = 4
    }
}

@Service
class AgentSkillRuntimeAccess(
    private val agentRepository: AgentRepository,
    private val skillRepository: AgentSkillRepository,
) {
    suspend fun resolve(
        context: ToolExecutionContext?,
        skillId: AgentSkill.Id,
        contentHash: String,
    ): AgentSkillPackage {
        val projectId = context.requiredProjectId()
        val agentId = context.requiredAgentDefinitionId()
        val agent = agentRepository.findById(agentId)
            ?: error("Agent not found: ${agentId.value}")
        require(agent.projectId == projectId) {
            "Agent ${agent.id.value} does not belong to project ${projectId.value}"
        }
        require(skillId in agent.skills) {
            "Agent Skill '${skillId.value}' is not assigned to agent ${agent.id.value}"
        }
        val skillPackage = skillRepository.findPackage(skillId)
            ?: error("Agent Skill package not found: ${skillId.value}")
        require(skillPackage.skill.projectId == projectId) {
            "Agent Skill '${skillId.value}' belongs to another project"
        }
        require(skillPackage.skill.contentHash == contentHash) {
            "Agent Skill handle is stale: expected $contentHash but current package is " +
                skillPackage.skill.contentHash + ". Call $OPEN_AGENT_SKILL_TOOL_NAME again."
        }
        return skillPackage
    }

    suspend fun open(
        context: ToolExecutionContext?,
        skillName: String,
    ): AgentSkillPackage {
        val projectId = context.requiredProjectId()
        val skill = skillRepository.findByName(projectId, skillName)
            ?: error("Agent Skill not found: $skillName")
        return resolve(context, skill.id, skill.contentHash)
    }
}

private fun openAgentSkillDefinition(): AiToolDefinition =
    AiToolDefinition(
        name = OPEN_AGENT_SKILL_TOOL_NAME,
        description = "Open the current immutable package for one Agent Skill assigned to this agent. " +
            "The result contains complete instructions, a resource index, and a skill_id plus content_hash handle. " +
            "Pass that exact handle to resource reading and workspace materialization. " +
            "Do not call this tool when the compact skill catalog is empty.",
        inputSchema = buildSkillNameSchema(
            extraProperties = emptyMap(),
            required = listOf("name"),
        ),
    )

private fun readAgentSkillResourceDefinition(): AiToolDefinition =
    AiToolDefinition(
        name = READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
        description = "Read one text file from the exact immutable Agent Skill package opened earlier. " +
            "Use the skill_id and content_hash returned by open_agent_skill plus an exact listed relative path, " +
            "and continue with next_offset when complete=false. Binary resources are never copied into model context; " +
            "use the workspace materialization tool instead.",
        inputSchema = buildSkillHandleSchema(
            extraProperties = mapOf(
                "path" to buildJsonObject {
                    put("type", "string")
                    put("description", "Exact relative resource path returned by open_agent_skill.")
                },
                "offset" to buildJsonObject {
                    put("type", "integer")
                    put("minimum", 0)
                    put("description", "Byte offset. Omit for the first chunk.")
                },
                "max_bytes" to buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 1_000_000)
                    put("description", "Maximum bytes to return. Defaults to 65536.")
                },
            ),
            required = listOf("skill_id", "content_hash", "path"),
        ),
    )

private fun buildSkillNameSchema(
    extraProperties: Map<String, kotlinx.serialization.json.JsonObject>,
    required: List<String>,
): String =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Exact Agent Skill name from the compact catalog.")
            })
            extraProperties.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        })
    }.toString()

private fun buildSkillHandleSchema(
    extraProperties: Map<String, kotlinx.serialization.json.JsonObject>,
    required: List<String>,
): String =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {
            put("skill_id", buildJsonObject {
                put("type", "string")
                put("description", "Immutable Agent Skill id returned by open_agent_skill.")
            })
            put("content_hash", buildJsonObject {
                put("type", "string")
                put("pattern", "^[0-9a-f]{64}$")
                put("description", "Exact package content hash returned by the same open_agent_skill call.")
            })
            extraProperties.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        })
    }.toString()

private fun buildAgentSkillCatalogPrompt(
    skills: List<AgentSkill>,
    openToolName: String,
    readResourceToolName: String,
    materializeTool: DistributedAiTool?,
): String =
    buildString {
        append("<agent_skills>\n")
        append("Agent Skills provide specialized instructions through progressive disclosure. ")
        append("When a listed skill is relevant, call `")
        append(openToolName)
        append("` with its exact name before applying it. Preserve the returned skill_id and content_hash as one immutable package handle. ")
        append("Use `")
        append(readResourceToolName)
        append("` only for model-readable resources listed by the opened package, using that exact handle. ")
        if (materializeTool != null) {
            append("When opened instructions require bundled files in a workspace, call `")
            append(materializeTool.modelName)
            append("` with the exact skill handle and intended workspace execution target. ")
        } else {
            append("Workspace materialization is currently unavailable; report that limitation instead of inventing a tool. ")
        }
        append("Do not invent skill names or treat `allowed-tools` metadata as a permission grant.\n")
        append(buildJsonObject {
            putJsonObject("workspace_materialization") {
                put("available", materializeTool != null)
                materializeTool?.let { tool ->
                    put("tool_name", tool.modelName)
                    putJsonArray("targets") {
                        tool.workers.forEach { worker ->
                            add(buildJsonObject {
                                put("worker_id", worker.workerId.value)
                                putJsonArray("workspace_mount_ids") {
                                    worker.workspaceMounts.forEach { mount ->
                                        add(JsonPrimitive(mount.id.value))
                                    }
                                }
                            })
                        }
                    }
                }
            }
            putJsonArray("available_skills") {
                skills.forEach { skill ->
                    add(buildJsonObject {
                        put("name", skill.name)
                        put("description", skill.description)
                        put("skill_id", skill.id.value)
                        put("content_hash", skill.contentHash)
                    })
                }
            }
        })
        append("\n</agent_skills>")
    }
