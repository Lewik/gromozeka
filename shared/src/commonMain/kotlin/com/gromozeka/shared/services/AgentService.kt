package com.gromozeka.shared.services

import com.gromozeka.shared.domain.Agent
import com.gromozeka.shared.repository.AgentRepository
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Instant

class AgentService(
    private val agentRepository: AgentRepository,
) {
    private val log = KLoggers.logger(this)

    suspend fun createAgent(
        name: String,
        systemPrompt: String,
        description: String? = null,
        isBuiltin: Boolean = false
    ): Agent {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val agent = Agent(
            id = Agent.Id(uuid7()),
            name = name,
            systemPrompt = systemPrompt,
            description = description,
            isBuiltin = isBuiltin,
            usageCount = 0,
            createdAt = now,
            updatedAt = now
        )

        return agentRepository.save(agent)
    }

    suspend fun findById(id: Agent.Id): Agent? =
        agentRepository.findById(id)

    suspend fun findAll(): List<Agent> =
        agentRepository.findAll()

    suspend fun update(
        id: Agent.Id,
        systemPrompt: String? = null,
        description: String? = null
    ): Agent? {
        val agent = agentRepository.findById(id) ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val updated = agent.copy(
            systemPrompt = systemPrompt ?: agent.systemPrompt,
            description = description ?: agent.description,
            updatedAt = now
        )

        return agentRepository.save(updated)
    }

    suspend fun delete(id: Agent.Id) {
        val agent = agentRepository.findById(id)
        if (agent?.isBuiltin == true) {
            log.warn("Cannot delete builtin agent: ${agent.name}")
            return
        }
        agentRepository.delete(id)
    }

    suspend fun count(): Int =
        agentRepository.count()
}
