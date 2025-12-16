package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.AgentDomainService
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class DefaultAgentProvider(
    private val agentService: AgentDomainService
) {
    private val log = KLoggers.logger(this)

    suspend fun getDefault(): AgentDefinition {
        val defaultAgent = agentService.findAll()
            .firstOrNull { it.type is AgentDefinition.Type.Builtin && it.name == "Gromozeka" }
            ?: error("Default agent 'Gromozeka' not found. Check resources/agents/default-gromozeka.json exists.")

        log.debug("Retrieved default agent: ${defaultAgent.name} (${defaultAgent.id.value})")
        return defaultAgent
    }
}
