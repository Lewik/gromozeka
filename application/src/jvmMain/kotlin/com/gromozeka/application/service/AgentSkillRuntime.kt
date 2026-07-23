package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredProjectId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

const val ACTIVATE_AGENT_SKILL_TOOL_NAME = "activate_agent_skill"
const val READ_AGENT_SKILL_RESOURCE_TOOL_NAME = "read_agent_skill_resource"

private val agentSkillToolNames = setOf(
    ACTIVATE_AGENT_SKILL_TOOL_NAME,
    READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
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
        runtimeWorkerId: ConversationRuntimeWorkerId,
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

        agentSkillToolNames.forEach { toolName ->
            val entry = toolCatalog.entries[toolName]
                ?: error("Current runtime does not provide required Agent Skill tool '$toolName'")
            require(entry.workers.any { it.workerId == runtimeWorkerId }) {
                "Current runtime worker '${runtimeWorkerId.value}' does not provide Agent Skill tool '$toolName'"
            }
        }

        val names = skills.map(AgentSkill::name)
        val rewrittenTools = toolCatalog.tools.map { callback ->
            when (callback.definition.name) {
                ACTIVATE_AGENT_SKILL_TOOL_NAME ->
                    callback.withDefinition(activateAgentSkillDefinition(names))
                READ_AGENT_SKILL_RESOURCE_TOOL_NAME ->
                    callback.withDefinition(readAgentSkillResourceDefinition(names))
                else -> callback
            }
        }
        return AgentSkillRuntimeCatalog(
            toolCatalog = toolCatalog.copy(tools = rewrittenTools),
            systemPrompt = buildAgentSkillCatalogPrompt(skills),
        )
    }

    private fun DistributedAiToolCatalogSnapshot.withoutAgentSkillTools(): DistributedAiToolCatalogSnapshot =
        copy(
            tools = tools.filterNot { it.definition.name in agentSkillToolNames },
            entries = entries.filterKeys { it !in agentSkillToolNames },
        )

    private fun AiToolCallback.withDefinition(rewritten: AiToolDefinition): AiToolCallback {
        val delegate = this
        return object : AiToolCallback {
            override val definition: AiToolDefinition = rewritten
            override val metadata: AiToolMetadata = delegate.metadata

            override fun call(toolInput: String, context: ToolExecutionContext?): String =
                delegate.call(toolInput, context)
        }
    }
}

@Component
class ActivateAgentSkillToolCallback(
    private val access: AgentSkillRuntimeAccess,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Input(
        val name: String,
    )

    override val definition: AiToolDefinition = activateAgentSkillDefinition(emptyList())

    override val metadata: AiToolMetadata = AiToolMetadata(
        executionScope = AiToolExecutionScope.CONVERSATION_RUNTIME,
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        val skillPackage = access.resolve(context, input.name)
        val resources = skillPackage.files.filterNot { it.path == "SKILL.md" }
        buildJsonObject {
            put("name", skillPackage.skill.name)
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
        val name: String,
        val path: String,
        val offset: Int = 0,
        val max_bytes: Int = DEFAULT_MAX_BYTES,
    )

    override val definition: AiToolDefinition = readAgentSkillResourceDefinition(emptyList())

    override val metadata: AiToolMetadata = AiToolMetadata(
        executionScope = AiToolExecutionScope.CONVERSATION_RUNTIME,
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        require(input.offset >= 0) { "Agent Skill resource offset must not be negative" }
        require(input.max_bytes in 1..MAX_BYTES) {
            "Agent Skill resource max_bytes must be between 1 and $MAX_BYTES"
        }
        val path = normalizeAgentSkillPath(input.path)
        val skillPackage = access.resolve(context, input.name)
        val file = skillPackage.files.singleOrNull { it.path == path }
            ?: error("Agent Skill '${input.name}' has no resource '$path'")
        require(input.offset <= file.content.size) {
            "Agent Skill resource offset ${input.offset} exceeds ${file.content.size} bytes"
        }

        val textFile = file.content.decodeUtf8OrNull() != null
        val requestedEnd = minOf(file.content.size, input.offset + input.max_bytes)
        val end = if (textFile) {
            findUtf8ChunkEnd(file.content, input.offset, requestedEnd)
        } else {
            requestedEnd
        }
        val chunk = file.content.copyOfRange(input.offset, end)
        buildJsonObject {
            put("name", skillPackage.skill.name)
            put("path", file.path)
            put("offset", input.offset)
            put("next_offset", end)
            put("size_bytes", file.content.size)
            put("complete", end == file.content.size)
            if (textFile) {
                put("encoding", "utf-8")
                put("content", checkNotNull(chunk.decodeUtf8OrNull()))
            } else {
                put("encoding", "base64")
                put("content", Base64.getEncoder().encodeToString(chunk))
            }
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
        }.getOrNull()

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
        skillName: String,
    ): AgentSkillPackage {
        val projectId = context.requiredProjectId()
        val agentId = context
            ?.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(AgentDefinition::Id)
            ?: error("Agent definition id is required in tool execution context")
        val agent = agentRepository.findById(agentId)
            ?: error("Agent not found: ${agentId.value}")
        require(agent.projectId == projectId) {
            "Agent ${agent.id.value} does not belong to project ${projectId.value}"
        }
        val skill = skillRepository.findByName(projectId, skillName)
            ?: error("Agent Skill not found: $skillName")
        require(skill.id in agent.skills) {
            "Agent Skill '$skillName' is not assigned to agent ${agent.id.value}"
        }
        return skillRepository.findPackage(skill.id)
            ?: error("Agent Skill package not found: ${skill.id.value}")
    }
}

private fun activateAgentSkillDefinition(skillNames: List<String>): AiToolDefinition =
    AiToolDefinition(
        name = ACTIVATE_AGENT_SKILL_TOOL_NAME,
        description = "Load the complete instructions and resource index for one Agent Skill assigned to this agent. " +
            "Activate a relevant skill before following it. Do not call this tool when the compact skill catalog is empty.",
        inputSchema = buildSkillNameSchema(
            skillNames = skillNames,
            extraProperties = emptyMap(),
            required = listOf("name"),
        ),
    )

private fun readAgentSkillResourceDefinition(skillNames: List<String>): AiToolDefinition =
    AiToolDefinition(
        name = READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
        description = "Read one file from an activated Agent Skill package. " +
            "Use an exact relative path referenced by the skill instructions or returned by activate_agent_skill, " +
            "and continue with next_offset when complete=false.",
        inputSchema = buildSkillNameSchema(
            skillNames = skillNames,
            extraProperties = mapOf(
                "path" to buildJsonObject {
                    put("type", "string")
                    put("description", "Exact relative resource path returned by activate_agent_skill.")
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
            required = listOf("name", "path"),
        ),
    )

private fun buildSkillNameSchema(
    skillNames: List<String>,
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
                if (skillNames.isNotEmpty()) {
                    put("enum", buildJsonArray {
                        skillNames.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
            extraProperties.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        })
    }.toString()

private fun buildAgentSkillCatalogPrompt(skills: List<AgentSkill>): String =
    buildString {
        append("<agent_skills>\n")
        append("Agent Skills provide specialized instructions through progressive disclosure. ")
        append("When a listed skill is relevant, call `")
        append(ACTIVATE_AGENT_SKILL_TOOL_NAME)
        append("` with its exact name before applying it. ")
        append("Use `")
        append(READ_AGENT_SKILL_RESOURCE_TOOL_NAME)
        append("` only for resources listed by the activated skill. ")
        append("Do not invent skill names or treat `allowed-tools` metadata as a permission grant.\n")
        append(buildJsonObject {
            putJsonArray("available_skills") {
                skills.forEach { skill ->
                    add(buildJsonObject {
                        put("name", skill.name)
                        put("description", skill.description)
                    })
                }
            }
        })
        append("\n</agent_skills>")
    }
