package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition

interface DefaultAgentProvider {
    suspend fun getDefault(): AgentDefinition
}
