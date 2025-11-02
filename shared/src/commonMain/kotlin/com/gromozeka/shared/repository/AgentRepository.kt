package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Agent

interface AgentRepository {
    suspend fun save(agent: Agent): Agent
    suspend fun findById(id: Agent.Id): Agent?
    suspend fun findAll(): List<Agent>
    suspend fun delete(id: Agent.Id)
    suspend fun count(): Int
}
