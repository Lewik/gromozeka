package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.infrastructure.db.persistence.tables.Agents
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedAgentRepository(
    private val builtinAgentLoader: BuiltinAgentLoader,
    private val fileSystemAgentScanner: FileSystemAgentScanner,
    private val json: Json,
) : AgentRepository {

    private var cachedBuiltinAgents: List<AgentDefinition> = emptyList()

    private fun getProjectPath(): String? {
        return fileSystemAgentScanner.findProjectRoot()?.absolutePath
    }

    override suspend fun save(agent: AgentDefinition): AgentDefinition {
        return when (agent.type) {
            is AgentDefinition.Type.Inline -> saveInlineAgent(agent)
            else -> {
                throw UnsupportedOperationException(
                    "File-based agents cannot be saved via repository. " +
                    "Use file system to create/edit agents."
                )
            }
        }
    }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? {
        val idValue = id.value

        findDynamicAgent(id)?.let { return it }

        // Handle builtin with project override
        if (idValue.startsWith("builtin:")) {
            val fileName = idValue.removePrefix("builtin:")
            val projectPath = getProjectPath()

            // Check for project override first
            if (projectPath != null) {
                val projectOverride = AgentDefinition.Id("project:$fileName")
                fileSystemAgentScanner.loadAgentById(projectOverride, projectPath, logMissing = false)?.let {
                    return it
                }
            }

            // Fall back to builtin
            return cachedBuiltinAgents.find { it.id == id }
        }

        // For global and project agents, load from disk
        if (idValue.startsWith("global:") || idValue.startsWith("project:")) {
            val projectPath = getProjectPath()
            return fileSystemAgentScanner.loadAgentById(id, projectPath)
        }

        return null
    }

    override suspend fun findAll(projectPath: String?): List<AgentDefinition> {
        val effectiveProjectPath = projectPath ?: getProjectPath()
        val inlineAgents = findDynamicAgents()

        val globalAgents = fileSystemAgentScanner.scanGlobalAgents()
        val projectAgents = if (effectiveProjectPath != null) {
            fileSystemAgentScanner.scanProjectAgents(effectiveProjectPath)
        } else {
            emptyList()
        }

        return (cachedBuiltinAgents + globalAgents + projectAgents + inlineAgents)
            .sortedBy { it.name }
    }

    override suspend fun delete(id: AgentDefinition.Id) {
        val deleted = dbQuery {
            Agents.deleteWhere { Agents.id eq id.value }
        }
        if (deleted > 0) {
            return
        }

        throw UnsupportedOperationException(
            "File-based agents cannot be deleted via repository. " +
            "Use file system to delete agents."
        )
    }

    override suspend fun count(): Int {
        return findAll(null).size
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinAgents = builtinAgentLoader.loadBuiltinAgents()
    }

    private suspend fun saveInlineAgent(agent: AgentDefinition): AgentDefinition = dbQuery {
        val exists = Agents.selectAll().where { Agents.id eq agent.id.value }.count() > 0
        if (exists) {
            Agents.update({ Agents.id eq agent.id.value }) {
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = INLINE_TYPE
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        } else {
            Agents.insert {
                it[id] = agent.id.value
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = INLINE_TYPE
                it[createdAt] = agent.createdAt.toKotlin()
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        }
        agent
    }

    private suspend fun findDynamicAgent(id: AgentDefinition.Id): AgentDefinition? = dbQuery {
        Agents.selectAll()
            .where { Agents.id eq id.value }
            .singleOrNull()
            ?.toAgent()
    }

    private suspend fun findDynamicAgents(): List<AgentDefinition> = dbQuery {
        Agents.selectAll()
            .map { it.toAgent() }
    }

    private fun ResultRow.toAgent(): AgentDefinition {
        val type = when (this[Agents.type]) {
            INLINE_TYPE -> AgentDefinition.Type.Inline
            else -> error("Unsupported database-backed agent type: ${this[Agents.type]}")
        }
        return AgentDefinition(
            id = AgentDefinition.Id(this[Agents.id]),
            name = this[Agents.name],
            prompts = json.decodeFromString<List<Prompt.Id>>(this[Agents.promptsJson]),
            runtimeSelection = json.decodeFromString<AiRuntimeSelection>(this[Agents.runtimeSelectionJson]),
            runtimeOverrides = json.decodeFromString<AiRuntimeOverrides>(this[Agents.runtimeOverridesJson]),
            tools = json.decodeFromString<List<String>>(this[Agents.toolsJson]),
            description = this[Agents.description],
            type = type,
            createdAt = this[Agents.createdAt].toKotlinx(),
            updatedAt = this[Agents.updatedAt].toKotlinx(),
        )
    }

    private companion object {
        const val INLINE_TYPE = "inline"
    }
}
