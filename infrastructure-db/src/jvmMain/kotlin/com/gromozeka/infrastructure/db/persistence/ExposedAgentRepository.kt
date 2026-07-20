package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.infrastructure.db.persistence.tables.Agents
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
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
    private val json: Json,
) : AgentRepository {

    private var cachedBuiltinAgents: List<AgentDefinition> = emptyList()

    override suspend fun save(agent: AgentDefinition): AgentDefinition {
        return when (agent.type) {
            is AgentDefinition.Type.Project -> saveProjectAgent(agent)
            is AgentDefinition.Type.Builtin ->
                throw UnsupportedOperationException("Builtin agents are immutable")
        }
    }

    override suspend fun createWithPrompts(
        agent: AgentDefinition,
        prompts: List<Prompt>,
    ): AgentDefinition = dbQuery {
        val projectId = checkNotNull(agent.projectId) { "Project agent must have projectId" }
        require(agent.type is AgentDefinition.Type.Project) { "Only project agents can be persisted" }
        require(
            Agents.selectAll().where { Agents.id eq agent.id.value }.singleOrNull() == null
        ) { "Agent already exists: ${agent.id.value}" }

        prompts.forEach { prompt ->
            require(prompt.type is Prompt.Type.Project && prompt.projectId == projectId) {
                "Copied prompt ${prompt.id.value} must belong to project ${projectId.value}"
            }
            require(
                Prompts.selectAll().where { Prompts.id eq prompt.id.value }.singleOrNull() == null
            ) { "Prompt already exists: ${prompt.id.value}" }
            insertProjectPrompt(prompt, projectId)
        }
        insertProjectAgent(agent, projectId)
        agent
    }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? {
        val idValue = id.value

        findDynamicAgent(id)?.let { return it }

        if (idValue.startsWith("builtin:")) {
            return cachedBuiltinAgents.find { it.id == id }
        }

        return null
    }

    override suspend fun findAll(): List<AgentDefinition> {
        return (cachedBuiltinAgents + findDynamicAgents())
            .sortedBy { it.name }
    }

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        (cachedBuiltinAgents + findDynamicAgents(projectId))
            .sortedBy { it.name }

    override suspend fun delete(id: AgentDefinition.Id) {
        val deleted = dbQuery {
            Agents.deleteWhere { Agents.id eq id.value }
        }
        if (deleted > 0) {
            return
        }

        throw IllegalArgumentException("Agent not found: ${id.value}")
    }

    override suspend fun count(): Int {
        return findAll().size
    }

    @jakarta.annotation.PostConstruct
    fun initialize() {
        cachedBuiltinAgents = builtinAgentLoader.loadBuiltinAgents()
    }

    private suspend fun saveProjectAgent(agent: AgentDefinition): AgentDefinition = dbQuery {
        val projectId = checkNotNull(agent.projectId) { "Project agent must have projectId" }
        val existing = Agents.selectAll()
            .where { Agents.id eq agent.id.value }
            .singleOrNull()
        if (existing != null) {
            require(existing[Agents.projectId] == projectId.value) {
                "Agent ${agent.id.value} belongs to another project"
            }
            Agents.update({ Agents.id eq agent.id.value }) {
                it[name] = agent.name
                it[promptsJson] = json.encodeToString(agent.prompts)
                it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                it[toolsJson] = json.encodeToString(agent.tools)
                it[description] = agent.description
                it[type] = PROJECT_TYPE
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        } else {
            insertProjectAgent(agent, projectId)
        }
        agent
    }

    private fun insertProjectAgent(agent: AgentDefinition, projectId: Project.Id) {
        Agents.insert {
            it[id] = agent.id.value
            it[Agents.projectId] = projectId.value
            it[name] = agent.name
            it[promptsJson] = json.encodeToString(agent.prompts)
            it[runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
            it[runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
            it[toolsJson] = json.encodeToString(agent.tools)
            it[description] = agent.description
            it[type] = PROJECT_TYPE
            it[createdAt] = agent.createdAt.toKotlin()
            it[updatedAt] = agent.updatedAt.toKotlin()
        }
    }

    private fun insertProjectPrompt(prompt: Prompt, projectId: Project.Id) {
        Prompts.insert {
            it[id] = prompt.id.value
            it[Prompts.projectId] = projectId.value
            it[name] = prompt.name
            it[content] = prompt.content
            it[sourceType] = PROJECT_TYPE
            it[sourcePath] = null
            it[createdAt] = prompt.createdAt.toKotlin()
            it[updatedAt] = prompt.updatedAt.toKotlin()
        }
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

    private suspend fun findDynamicAgents(projectId: Project.Id): List<AgentDefinition> = dbQuery {
        Agents.selectAll()
            .where { Agents.projectId eq projectId.value }
            .map { it.toAgent() }
    }

    private fun ResultRow.toAgent(): AgentDefinition {
        val type = when (this[Agents.type]) {
            PROJECT_TYPE -> AgentDefinition.Type.Project
            else -> error("Unsupported database-backed agent type: ${this[Agents.type]}")
        }
        return AgentDefinition(
            id = AgentDefinition.Id(this[Agents.id]),
            projectId = Project.Id(this[Agents.projectId]),
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
        const val PROJECT_TYPE = "project"
    }
}
