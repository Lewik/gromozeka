package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.Agents
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentRepository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.datetime.Instant

class ExposedAgentRepository : AgentRepository {

    override suspend fun save(agent: Agent): Agent = dbQuery {
        val exists = Agents.selectAll().where { Agents.id eq agent.id.value }.count() > 0

        if (exists) {
            Agents.update({ Agents.id eq agent.id.value }) {
                it[name] = agent.name
                it[systemPrompt] = agent.systemPrompt
                it[description] = agent.description
                it[isBuiltin] = agent.isBuiltin
                it[usageCount] = agent.usageCount
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        } else {
            Agents.insert {
                it[id] = agent.id.value
                it[name] = agent.name
                it[systemPrompt] = agent.systemPrompt
                it[description] = agent.description
                it[isBuiltin] = agent.isBuiltin
                it[usageCount] = agent.usageCount
                it[createdAt] = agent.createdAt.toKotlin()
                it[updatedAt] = agent.updatedAt.toKotlin()
            }
        }
        agent
    }

    override suspend fun findById(id: Agent.Id): Agent? = dbQuery {
        Agents.selectAll().where { Agents.id eq id.value }
            .map { it.toAgent() }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Agent> = dbQuery {
        Agents.selectAll()
            .orderBy(Agents.name, SortOrder.ASC)
            .map { it.toAgent() }
    }

    override suspend fun delete(id: Agent.Id): Unit = dbQuery {
        Agents.deleteWhere { Agents.id eq id.value }
    }

    override suspend fun count(): Int = dbQuery {
        Agents.selectAll().count().toInt()
    }

    private fun ResultRow.toAgent() = Agent(
        id = Agent.Id(this[Agents.id]),
        name = this[Agents.name],
        systemPrompt = this[Agents.systemPrompt],
        description = this[Agents.description],
        isBuiltin = this[Agents.isBuiltin],
        usageCount = this[Agents.usageCount],
        createdAt = this[Agents.createdAt].toKotlinx(),
        updatedAt = this[Agents.updatedAt].toKotlinx()
    )
}
