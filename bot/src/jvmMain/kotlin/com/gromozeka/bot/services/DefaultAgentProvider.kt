package com.gromozeka.bot.services

import com.gromozeka.shared.domain.Agent
import com.gromozeka.shared.services.AgentService
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class DefaultAgentProvider(
    private val agentService: AgentService
) {
    private val log = KLoggers.logger(this)

    suspend fun getDefault(): Agent {
        val defaultAgent = agentService.findAll()
            .firstOrNull { it.isBuiltin && it.name == "Gromozeka" }
            ?: error("Default agent 'Gromozeka' not found in database. Database may be corrupted.")

        log.debug("Retrieved default agent: ${defaultAgent.name} (${defaultAgent.id})")
        return defaultAgent
    }
}
