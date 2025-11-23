package com.gromozeka.application.service

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentDomainService
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class DefaultAgentProvider(
    private val agentService: AgentDomainService
) {
    private val log = KLoggers.logger(this)

    suspend fun getDefault(): Agent {
        val defaultAgent = agentService.findAll()
            .firstOrNull { it.type == Agent.Type.BUILTIN && it.name == "Gromozeka" }
            ?: error("Default agent 'Gromozeka' not found in database. Database may be corrupted.")

        log.debug("Retrieved default agent: ${defaultAgent.name} (${defaultAgent.id})")
        return defaultAgent
    }
}
