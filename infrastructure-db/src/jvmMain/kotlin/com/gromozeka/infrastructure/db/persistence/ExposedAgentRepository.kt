package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Project
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
    private val json: Json,
) : AgentRepository {
    override suspend fun save(agent: AgentDefinition): AgentDefinition = dbQuery {
        val existing = Agents.selectAll()
            .where { Agents.id eq agent.id.value }
            .singleOrNull()

        if (existing == null) {
            Agents.insert { statement -> statement.write(agent) }
        } else {
            require(existing[Agents.projectId] == agent.projectId?.value) {
                "Agent scope cannot be changed: ${agent.id.value}"
            }
            Agents.update({ Agents.id eq agent.id.value }) { statement ->
                statement[Agents.name] = agent.name
                statement[Agents.promptsJson] = json.encodeToString(agent.prompts)
                statement[Agents.skillsJson] = json.encodeToString(agent.skills)
                statement[Agents.runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
                statement[Agents.runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
                statement[Agents.toolsJson] = json.encodeToString(agent.tools)
                statement[Agents.description] = agent.description
                statement[Agents.type] = agent.type.databaseValue()
                statement[Agents.updatedAt] = agent.updatedAt
            }
        }
        agent
    }

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? = dbQuery {
        Agents.selectAll()
            .where { Agents.id eq id.value }
            .singleOrNull()
            ?.toAgent()
    }

    override suspend fun findAll(): List<AgentDefinition> = dbQuery {
        Agents.selectAll()
            .map { it.toAgent() }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        findAll().filter { it.type is AgentDefinition.Type.Global || it.projectId == projectId }

    override suspend fun delete(id: AgentDefinition.Id) {
        val deleted = dbQuery {
            Agents.deleteWhere { Agents.id eq id.value }
        }
        require(deleted > 0) { "Agent not found: ${id.value}" }
    }

    override suspend fun count(): Int = dbQuery {
        Agents.selectAll().count().toInt()
    }

    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(agent: AgentDefinition) {
        this[Agents.id] = agent.id.value
        this[Agents.projectId] = agent.projectId?.value
        this[Agents.name] = agent.name
        this[Agents.promptsJson] = json.encodeToString(agent.prompts)
        this[Agents.skillsJson] = json.encodeToString(agent.skills)
        this[Agents.runtimeSelectionJson] = json.encodeToString(agent.runtimeSelection)
        this[Agents.runtimeOverridesJson] = json.encodeToString(agent.runtimeOverrides)
        this[Agents.toolsJson] = json.encodeToString(agent.tools)
        this[Agents.description] = agent.description
        this[Agents.type] = agent.type.databaseValue()
        this[Agents.createdAt] = agent.createdAt
        this[Agents.updatedAt] = agent.updatedAt
    }

    private fun ResultRow.toAgent(): AgentDefinition {
        val type = when (this[Agents.type]) {
            GLOBAL_TYPE -> AgentDefinition.Type.Global
            PROJECT_TYPE -> AgentDefinition.Type.Project
            else -> error("Unsupported agent scope: ${this[Agents.type]}")
        }
        return AgentDefinition(
            id = AgentDefinition.Id(this[Agents.id]),
            projectId = this[Agents.projectId]?.let(Project::Id),
            name = this[Agents.name],
            prompts = json.decodeFromString<List<Prompt.Id>>(this[Agents.promptsJson]),
            skills = json.decodeFromString<List<AgentSkill.Id>>(this[Agents.skillsJson]),
            runtimeSelection = json.decodeFromString<AiRuntimeSelection>(this[Agents.runtimeSelectionJson]),
            runtimeOverrides = json.decodeFromString<AiRuntimeOverrides>(this[Agents.runtimeOverridesJson]),
            tools = json.decodeFromString<List<String>>(this[Agents.toolsJson]),
            description = this[Agents.description],
            type = type,
            createdAt = this[Agents.createdAt],
            updatedAt = this[Agents.updatedAt],
        )
    }

    private fun AgentDefinition.Type.databaseValue(): String =
        when (this) {
            AgentDefinition.Type.Global -> GLOBAL_TYPE
            AgentDefinition.Type.Project -> PROJECT_TYPE
        }

    private companion object {
        const val GLOBAL_TYPE = "global"
        const val PROJECT_TYPE = "project"
    }
}
